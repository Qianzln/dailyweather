package com.dailyweather.app.di

import android.content.Context
import com.dailyweather.app.BuildConfig
import com.dailyweather.app.data.Fixture
import com.dailyweather.app.data.SettingsStore
import com.dailyweather.app.data.WeatherStore
import com.dailyweather.app.data.location.LocationCoordinator
import com.dailyweather.app.data.remote.AmapGeo
import com.dailyweather.app.data.remote.CloudProxy
import com.dailyweather.app.repository.CityRepository
import com.dailyweather.app.repository.ProviderChain
import com.dailyweather.app.repository.WeatherRepository
import com.dailyweather.app.sync.RefreshManager

/**
 * 手写 DI 容器（阶段二换 Hilt，拓扑不变：单例仓库 + Factory 作用域 ViewModel）。
 *
 * 数据源链路与微风天气同源：彩云/和风/小米/高德全部经 CloudBase 云代理
 * （凭据在云函数环境变量，APK 零 Key）；主源小米，彩云只补短临/预警/逐时降水概率。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** 云代理（唯一外部网络出口）。未配置 = 显示降级态，不白发请求。 */
    val proxy = CloudProxy(BuildConfig.CLOUDBASE_PROXY_URL)

    /** 彩云 BYOK（可选）：用户填了个人 token 就走自己额度，留空走云函数共享签名。 */
    private val caiyunToken: String = BuildConfig.CAIYUN_TOKEN

    val proxyConfigured: Boolean get() = proxy.isConfigured

    val settings = SettingsStore(appContext)
    val weatherStore = WeatherStore(appContext)

    val locationCoordinator = LocationCoordinator(appContext, settings)

    val amapGeo = AmapGeo(proxy)

    /** 雷达数据网关（经同一 CloudProxy，引用 wui 同款链路）。 */
    val radarGateway = com.dailyweather.app.data.remote.RadarGateway(proxy)

    /** 天气语音播报（平台 TTS）。 */
    val ttsSpeaker = com.dailyweather.app.util.TtsSpeaker(appContext)

    val cityRepository = CityRepository(weatherStore, settings)
    val weatherRepository = WeatherRepository(
        weatherStore, cityRepository,
        ProviderChain.standard(proxy, caiyunToken),
        fixtureProvider = { now -> if (BuildConfig.DEBUG) Fixture.state?.let { Fixture.load(appContext, it, now) } else null },
    )

    val refreshManager = RefreshManager(
        weatherRepository, cityRepository, settings, locationCoordinator, amapGeo,
        onRefreshSuccess = { cityId ->
            // 每次刷新成功后检查预警：UrgentNotificationWorker 内部按 城市+标题+发布时间 去重。
            androidx.work.WorkManager.getInstance(appContext).enqueueUniqueWork(
                "urgent_alert_check",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<com.dailyweather.app.notification.UrgentNotificationWorker>()
                    .setInputData(androidx.work.workDataOf(
                        com.dailyweather.app.notification.UrgentNotificationWorker.KEY_CITY_ID to cityId,
                    ))
                    .build(),
            )
        },
    )

    /** 高德行政区划搜索（经代理）；失败/未配置由调用方回落内置城市表。 */
    suspend fun searchPlaces(keyword: String): List<Triple<String, Double, Double>>? =
        if (proxyConfigured) {
            runCatching { amapGeo.searchDistrict(keyword) }.getOrNull()
        } else null

    /** 应用更新检查：直连本项目自己的 GitHub 仓库（[RELEASE_REPO]）读 releases/latest。
     *  不再走共享云函数 /weather/app/latest —— 那会读到微风的版本，与每日天气无关。
     *  仓库已设为 public，匿名即可读（GitHub 限流 60/h，检查更新属低频）。
     *  同时取 APK 资产的 SHA-256 digest（GitHub 于 2023 年起在 assets 里下发），
     *  供多源下载后做完整性校验。 */
    suspend fun checkLatestUpdate(): UpdateInfo? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("https://api.github.com/repos/${RELEASE_REPO}/releases/latest")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "dailyweather-app")
                if (conn.responseCode != 200) { conn.disconnect(); return@runCatching null }
                val root = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val version = root.optString("tag_name").trim().removePrefix("v")
                val assets = root.optJSONArray("assets")
                val apkAsset = if (assets != null) {
                    (0 until assets.length()).firstOrNull {
                        assets.optJSONObject(it)?.optString("name", "")?.endsWith(".apk") == true
                    }?.let { assets.optJSONObject(it) }
                } else null
                val apkUrl = apkAsset?.optString("browser_download_url").orEmpty().ifBlank { null }
                val digest = apkAsset?.optString("digest").orEmpty()
                    .removePrefix("sha256:").ifBlank { null }
                UpdateInfo(version, apkUrl, digest)
            }.getOrNull()?.takeIf { it.version.isNotBlank() }
        }

    /** 在 app 内下载更新 APK 到应用私有目录（供后续 ACTION_VIEW 安装）。
     *
     *  国内网络下 GitHub 官方 CDN（objects.githubusercontent.com）常常不可达，
     *  所以按「直链 → 两个加速镜像」的顺序逐个尝试；每一跳都：
     *  - 仅 https；host 必须在白名单（github / githubusercontent / 两个加速镜像）；
     *  - 拒绝 localhost、环回、私有与保留地址（DNS 解析后检查）；
     *  - 手动跟随 30x 跳转并逐跳复检（HttpURLConnection 不保证跨域跳转）；
     *  下载完成后用 GitHub 下发的 SHA-256 digest 校验完整性——镜像即使被污染/
     *  返回错误内容也会被拒掉，不会拿去安装。 */
    suspend fun downloadUpdateApk(info: UpdateInfo): UpdateDownloadResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val direct = info.apkUrl
            if (direct.isNullOrBlank()) {
                return@withContext UpdateDownloadResult.Failure("该 release 没有 APK 资产", null)
            }
            val candidates = buildList {
                add(direct)
                // 加速镜像（仅国内直连 GitHub 失败时的回退；有 digest 兜底完整性）
                add("https://mirror.ghproxy.com/$direct")
                add("https://ghproxy.net/$direct")
            }
            var lastReason = "未知错误"
            for (url in candidates) {
                val file = runCatching { openAndCopy(url, info.sha256) }.getOrElse {
                    lastReason = friendlyNetError(it, url)
                    null
                }
                if (file != null) {
                    val dir = appContext.getExternalFilesDir(null) ?: appContext.cacheDir
                    val final = java.io.File(dir, "dailyweather-update.apk")
                    final.delete()
                    // kotlin.io 的 copyTo 扩展返回 File（非 Boolean），用 runCatching 归一成布尔
                    val moved = file.renameTo(final) ||
                        runCatching { file.copyTo(final, overwrite = true) }.isSuccess
                    if (!moved) {
                        lastReason = "下载成功但写入安装目录失败"
                        continue
                    }
                    // 版本校验（防"镜像缓存旧文件"）：解包读 manifest 里的 versionName，
                    // 与 release tag 不一致说明这个源吐了旧包——丢弃并换下一个源。
                    val detected = apkVersionName(final)
                    val expected = info.version.trim().removePrefix("v")
                    if (detected != null && expected.isNotBlank() && detected != expected) {
                        final.delete()
                        lastReason = "该下载源提供了旧版文件（v$detected，期望 v$expected）——换源重试中"
                        continue
                    }
                    return@withContext UpdateDownloadResult.Success(final)
                }
            }
            UpdateDownloadResult.Failure(lastReason, direct)
        }

    /** 打开 [url] 并流式写入临时文件；跟跳转逐跳校验 host；有 [sha256] 时校验摘要。返回 null=不可用。 */
    private fun openAndCopy(url: String, sha256: String?): java.io.File? {
        var current = java.net.URI(url)
        var conn: java.net.HttpURLConnection? = null
        try {
            repeat(6) {
                if (current.scheme != "https") return null
                val host = current.host?.lowercase() ?: return null
                if (!isPublicAllowedUpdateHost(host)) return null
                conn?.disconnect()
                conn = current.toURL().openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("User-Agent", "dailyweather-app")
                val code = conn.responseCode
                when {
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return null
                        current = java.net.URL(current.toURL(), loc).toURI()
                        return@repeat
                    }
                    code != 200 -> {
                        if (current.host?.contains("github", true) == true) {
                            throw DownloadAborted("GitHub 直连返回 HTTP $code（可能限流或地区限制）")
                        }
                        return null // 镜像 4xx/5xx 直接换下一个源
                    }
                }
                val dest = java.io.File.createTempFile("dailyweather-update", ".apk")
                val digestCalc = if (sha256 != null) java.security.MessageDigest.getInstance("SHA-256") else null
                var size = 0L
                conn.inputStream.use { ins ->
                    dest.outputStream().use { outs ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            size += n
                            digestCalc?.update(buf, 0, n)
                            outs.write(buf, 0, n)
                        }
                    }
                }
                // APK 是 zip：开头必须是 'P''K'，且体积 > 1MB（我们每个包 3-10MB）
                val fin = dest.inputStream()
                val b1 = fin.read(); val b2 = fin.read(); fin.close()
                if (b1.toInt() and 0xFF != 'P'.code || b2.toInt() and 0xFF != 'K'.code || size < 1_000_000L) {
                    dest.delete(); return null
                }
                if (sha256 != null) {
                    val hex = digestCalc!!.digest().joinToString("") { "%02x".format(it) }
                    if (hex != sha256.lowercase()) { dest.delete(); return null }
                }
                return dest
            }
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 从 APK 读 versionName：解包 AndroidManifest.xml（二进制 XML 的字符串表是明文
     * UTF-8，versionName 就是其中一个字符串），取第一个 x.y.z 形态的数字串。
     * 解析失败返回 null（不阻断——digest/PK/大小校验仍兜底）。
     */
    private fun apkVersionName(apk: java.io.File): String? = runCatching {
        java.util.zip.ZipFile(apk).use { zf ->
            val entry = zf.getEntry("AndroidManifest.xml") ?: return@runCatching null
            val text = String(zf.getInputStream(entry).readBytes(), Charsets.UTF_8)
            Regex("\\d+\\.\\d+\\.\\d+").find(text)?.value
        }
    }.getOrNull()

    /** 更新下载 host 白名单（全部是公开 CDN/官方域；镜像有 SHA-256 兜底）。 */
    private fun isPublicAllowedUpdateHost(host: String): Boolean {
        val h = host.trimEnd('.')
        val allowed = listOf("github.com", "githubusercontent.com", "mirror.ghproxy.com", "ghproxy.net")
        if (allowed.none { h == it || h.endsWith(".$it") }) return false
        // 拒绝 localhost/环回/私有/保留：解析后必须存在至少一个公网地址
        return runCatching {
            java.net.InetAddress.getAllByName(h)
                .none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
        }.getOrDefault(false)
    }

    private fun friendlyNetError(t: Throwable, url: String): String {
        if (t is DownloadAborted) return t.reason
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        val isMirror = host.contains("ghproxy", true)
        val base = when {
            t is java.net.SocketTimeoutException || t.cause is java.net.SocketTimeoutException -> "连接超时"
            t is java.net.UnknownHostException -> "无法解析服务器地址"
            t is java.io.IOException -> "网络中断"
            else -> "下载出错"
        }
        val src = if (isMirror) "镜像" else if (host.contains("github")) "GitHub" else "下载源"
        return "$src$base"
    }

    companion object {
        /** 本 App 自己的 GitHub 仓库（private→public 后可匿名读 release）。 */
        const val RELEASE_REPO = "Qianzln/dailyweather"
    }
}

/** 一次"检查更新"的结果：最新版本号 + 该版本 APK 的下载直链（可能为 null）+ 官方 SHA-256。 */
data class UpdateInfo(val version: String, val apkUrl: String?, val sha256: String?)

/** 主动中止某一下载源（如直链返回 4xx），携带给用户看的友好原因。 */
private class DownloadAborted(val reason: String) : java.io.IOException(reason)

/** 应用内下载 APK 的结果。 */
sealed interface UpdateDownloadResult {
    data class Success(val file: java.io.File) : UpdateDownloadResult
    /** [reason] 给用户看的具体失败原因；[fallbackUrl] 官方直链（可用浏览器/自有网络工具下载）。 */
    data class Failure(val reason: String, val fallbackUrl: String?) : UpdateDownloadResult
}
