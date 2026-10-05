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
     *  仓库已设为 public，匿名即可读（GitHub 限流 60/h，检查更新属低频）。 */
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
                val apkUrl = if (assets != null) {
                    (0 until assets.length()).firstOrNull {
                        assets.optJSONObject(it)?.optString("name", "")?.endsWith(".apk") == true
                    }?.let { assets.optJSONObject(it)?.optString("browser_download_url") }
                } else null
                UpdateInfo(version, apkUrl)
            }.getOrNull()?.takeIf { it.version.isNotBlank() }
        }

    /** 在 app 内下载更新 APK 到应用私有目录（供后续 ACTION_VIEW 安装），返回文件；失败返回 null。
     *  仅允许下载 GitHub 官方域名，拒绝任何其它 host（防 SSRF）。 */
    suspend fun downloadUpdateApk(info: UpdateInfo): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val urlStr = info.apkUrl ?: return@withContext null
            runCatching {
                val host = java.net.URI(urlStr).host?.lowercase() ?: ""
                if (!host.endsWith("github.com") && !host.endsWith("githubusercontent.com")) {
                    return@runCatching null
                }
                val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                if (conn.responseCode != 200) { conn.disconnect(); return@runCatching null }
                val dir = appContext.getExternalFilesDir(null) ?: appContext.cacheDir
                val dest = java.io.File(dir, "dailyweather-update.apk")
                conn.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                conn.disconnect()
                dest
            }.getOrNull()
        }

    companion object {
        /** 本 App 自己的 GitHub 仓库（private→public 后可匿名读 release）。 */
        const val RELEASE_REPO = "Qianzln/dailyweather"
    }
}

/** 一次"检查更新"的结果：最新版本号 + 该版本 APK 的下载直链（可能为 null）。 */
data class UpdateInfo(val version: String, val apkUrl: String?)
