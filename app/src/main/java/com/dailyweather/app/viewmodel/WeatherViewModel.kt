package com.dailyweather.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dailyweather.app.BuildConfig
import com.dailyweather.app.data.City
import com.dailyweather.app.data.SettingsStore
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.data.HomeCards
import com.dailyweather.app.data.HomeCardId
import com.dailyweather.app.di.AppContainer
import com.dailyweather.app.sync.RefreshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 屏幕级状态 Owner（对齐南风 `WeatherViewModel` 的职责：选中城市、快照订阅、刷新动作）。
 * 阶段一锚定在 Activity 生命周期上（remember + 容器），阶段二换 Hilt+ViewModelStore。
 */
class WeatherViewModel(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var selectedCityId by mutableStateOf<String?>(null)
        private set

    var refreshing by mutableStateOf(false)
        private set
    
    /** 定位城市是否首次定位中（用于组件占位态）。 */
    var locatingFirstTime by mutableStateOf(false)
        private set

    // ---- 检查更新（走本 App 自己的 GitHub 仓库，不混入其它项目）----
    /** 检查更新结果：null=检测中/未知；非空字符串=提示文案。 */
    var updateMessage by mutableStateOf<String?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set

    /** 本次检测到的最新版本（含 APK 直链），供"下载并安装"使用。 */
    var updateInfo: com.dailyweather.app.di.UpdateInfo? = null
        private set
    /** 远端确实比本地新（才应显示"下载并安装"）。 */
    var updateHasNewer by mutableStateOf(false)
        private set
    var updateDownloading by mutableStateOf(false)
        private set
    var updateInstallHint by mutableStateOf<String?>(null)
        private set

    /** 检测最新版本（直连本项目 GitHub 仓库），与当前 BuildConfig 版本号比对。 */
    fun checkUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateMessage = null
        scope.launch {
            val info = container.checkLatestUpdate()
            updateChecking = false
            if (info == null) {
                updateMessage = "暂时无法连接更新服务（需联网读取 GitHub 仓库）"
            } else {
                updateInfo = info
                val cur = BuildConfig.VERSION_NAME.removePrefix("v")
                val hasNewer = compareVersions(info.version, cur) > 0
                updateHasNewer = hasNewer
                updateMessage = if (hasNewer) {
                    "发现新版本 v${info.version}（当前 v$cur）"
                } else {
                    "已是最新版本 v$cur"
                }
            }
        }
    }

    /** 语义版本比较（仅数字段）：a>b 返回正，a==b 返回 0，a<b 返回负。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 在 app 内下载更新 APK 并拉起系统安装器（不跳浏览器）。 */
    fun installUpdate() {
        val info = updateInfo ?: return
        if (updateDownloading) return
        updateDownloading = true
        updateInstallHint = "正在下载 v${info.version} …"
        scope.launch {
            val apk = container.downloadUpdateApk(info)
            if (apk == null) {
                updateDownloading = false
                updateInstallHint = "下载失败，请检查网络后重试"
                return@launch
            }
            val ctx = container.appContext
            val pm = ctx.packageManager
            val canInstall = android.os.Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()
            if (!canInstall) {
                updateDownloading = false
                updateInstallHint = "需授予「允许安装未知应用」权限后重试"
                val open = android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(open) }
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", apk)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            updateDownloading = false
            updateInstallHint = "已下载，正在打开安装器…"
            runCatching { ctx.startActivity(intent) }
        }
    }

    var message by mutableStateOf<String?>(null)
        private set

    val cities = container.cityRepository.cities
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val weatherBlue = container.settings.weatherBlueIcons
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    private val snapshots = MutableStateFlow<Map<String, WeatherSnapshot>>(emptyMap())
    val snapshotCache: kotlinx.coroutines.flow.StateFlow<Map<String, WeatherSnapshot>> = snapshots

    init {
        scope.launch {
            container.cityRepository.cities.collect { list ->
                val current = selectedCityId
                if (current == null || list.none { it.id == current }) {
                    selectedCityId = list.firstOrNull { it.isCurrentLocation }?.id
                        ?: list.firstOrNull()?.id
                }
                list.forEach { city -> observeCity(city.id) }
                if (list.isNotEmpty()) maybeColdStartRefresh()
            }
        }
        scope.launch {
            container.settings.locationEnabled.collect { enabled ->
                if (enabled) {
                    if (container.cityRepository.currentLocationCity() == null) locateAndRefresh()
                    // 持续位移监听：真实位置移动超过阈值（默认 500m）就重新逆地理命名并刷新天气，
                    // 让"定位"随移动自动更新，而非只在启动/手动刷新时定一次位。
                    if (container.locationCoordinator.hasPermission()) {
                        container.locationCoordinator.startMoveWatcher(onMove = { _ ->
                            scope.launch {
                                container.refreshManager.refreshCurrentLocation("location-moved")
                            }
                        })
                    }
                } else {
                    container.locationCoordinator.stopMoveWatcher()
                    if (container.cityRepository.currentLocationCity() != null) {
                        container.cityRepository.remove("current")
                    }
                }
            }
        }
    }

    private val observed = HashSet<String>()
    private var coldStartRefreshDone = false

    private fun observeCity(cityId: String) {
        if (!observed.add(cityId)) return
        scope.launch {
            container.weatherRepository.observe(cityId).collect { snap ->
                if (snap != null) {
                    snapshots.value = snapshots.value + (cityId to snap)
                }
            }
        }
    }

    /** 冷启动补一次刷新（对齐南风 onResume 自动同步；5s 去重与城市锁兜底）。 */
    private fun maybeColdStartRefresh() {
        if (coldStartRefreshDone) return
        coldStartRefreshDone = true
        scope.launch {
            val outcome = container.refreshManager.refreshDefault("app_cold_start")
            if (outcome is RefreshManager.Outcome.Failed) message = outcome.reason
        }
    }

    fun snapshotFor(cityId: String?): WeatherSnapshot? = cityId?.let { snapshots.value[it] }

    fun cityFor(cityId: String?): City? = cityId?.let { id -> cities.value.firstOrNull { it.id == id } }

    // ---- 语音播报 ----
    /** 播报当前天气（温度/现象/昼夜）。设置里「开启语音播报」开启后由刷新调用。 */
    fun speakWeather() {
        val snap = snapshotFor(selectedCityId) ?: return
        val desc = com.dailyweather.app.ui.components.SkyconMap.desc(snap.currentSkycon)
        val temp = snap.currentTemp.toInt()
        val text = "当前${temp}度，$desc。"
        container.ttsSpeaker.speak(text)
    }

    // ---- 雷达帧加载 ----
    /** 雷达帧序列（按时间升序，用于轮播）。 */
    var radarFrames by mutableStateOf<List<Pair<Long, ByteArray>>>(emptyList())
        private set
    var radarFrameTime by mutableStateOf<Long?>(null)
        private set
    var radarIntervalSec by mutableStateOf(600L)
        private set
    var radarError by mutableStateOf<String?>(null)
        private set
    /** 用于雷达卡触发重新拉帧的版本号（每次刷新 +1）。 */
    var radarRefreshKey by mutableStateOf(0)
        private set

    /** 拉取雷达元数据 + 最近若干帧，供雷达卡缩略图轮播。meta 偶发 502，重试 2 次。 */
    fun loadRadar() {
        scope.launch {
            radarError = null
            try {
                var meta: com.dailyweather.app.data.remote.RadarGateway.RadarMetadata? = null
                repeat(3) { attempt ->
                    runCatching { container.radarGateway.metadata() }
                        .onSuccess { meta = it }
                    if (meta != null) return@repeat
                    delay(800L * (attempt + 1))
                }
                val m = meta ?: run {
                    radarError = "雷达服务暂不可用"
                    return@launch
                }
                if (m.frameTimes.isEmpty()) {
                    radarError = "暂无雷达帧"
                    return@launch
                }
                radarIntervalSec = m.frameIntervalSec
                val city = cityFor(selectedCityId)
                    ?: cities.value.firstOrNull { it.isCurrentLocation }
                    ?: cities.value.firstOrNull()
                val lat = city?.latitude ?: 32.06
                val lon = city?.longitude ?: 118.78
                // 取最近 4 帧轮播（省流：只拉一小段），失败帧跳过。
                val times = m.frameTimes.takeLast(4)
                val frames = buildList {
                    for (t in times) {
                        runCatching { container.radarGateway.frame(t, lat, lon) }
                            .getOrNull()?.let { add(t to it) }
                    }
                }
                if (frames.isNotEmpty()) {
                    radarFrames = frames
                    radarFrameTime = frames.last().first
                    radarRefreshKey += 1
                } else {
                    radarError = "雷达帧拉取失败"
                }
            } catch (e: Exception) {
                radarError = "雷达加载失败：${e.message?.take(40)}"
            }
        }
    }

    fun refreshCurrent() {
        val id = selectedCityId ?: return
        if (refreshing) return
        refreshing = true
        scope.launch {
            val outcome = container.refreshManager.refresh(id, "app_home_refresh")
            refreshing = false
            when (outcome) {
                is RefreshManager.Outcome.Failed -> message = outcome.reason
                is RefreshManager.Outcome.Skipped -> {}
                is RefreshManager.Outcome.Success -> {
                    message = null
                    maybeSpeakAfterRefresh()
                }
            }
        }
    }

    /** 语音播报开启时，刷新成功自动播报当前天气。 */
    private suspend fun maybeSpeakAfterRefresh() {
        if (container.settings.speechEnabled.first()) speakWeather()
    }

    fun locateAndRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val isFirstTime = container.cityRepository.currentLocationCity() == null
            locatingFirstTime = isFirstTime
            val outcome = container.refreshManager.refreshCurrentLocation("app_locate_refresh")
            refreshing = false
            locatingFirstTime = false
            when (outcome) {
                is RefreshManager.Outcome.Failed -> message = outcome.reason
                is RefreshManager.Outcome.Skipped -> {}
                is RefreshManager.Outcome.Success -> {
                    selectedCityId = "current"
                    message = null
                }
            }
        }
    }

    fun selectCity(cityId: String) {
        selectedCityId = cityId
    }

    fun addCity(city: City) {
        scope.launch {
            container.cityRepository.add(city)
            selectedCityId = city.id
            refresh(city.id)
        }
    }

    private fun refresh(cityId: String) {
        scope.launch {
            refreshing = true
            container.refreshManager.refresh(cityId, "city_added")
            refreshing = false
        }
    }

    fun removeCity(cityId: String) {
        scope.launch { container.cityRepository.remove(cityId) }
    }

    /** 拖拽排序落库（CityListScreen 手柄拖动时逐格调用）。 */
    fun reorderCities(orderedIds: List<String>) {
        scope.launch { container.cityRepository.reorder(orderedIds) }
    }

    fun toggleBookmark(cityId: String) {
        scope.launch {
            val city = container.cityRepository.byId(cityId) ?: return@launch
            container.cityRepository.setBookmarked(cityId, !city.isBookmarked)
        }
    }

    fun searchCities(keyword: String, onResult: (List<Triple<String, Double, Double>>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            // 高德优先（key 注入后），内置城市表兜底；Triple(名称, 纬度, 经度)
            val remote = container.searchPlaces(keyword) ?: emptyList()
            val local = com.dailyweather.app.util.BuiltinCities.search(keyword).map {
                Triple("${it.name} · ${it.province}", it.lat, it.lng)
            }
            val merged = (remote + local).distinctBy { it.first }.take(12)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onResult(merged) }
        }
    }

    // ---- 设置项（键控，避免字符串匹配）----

    enum class SettingKey { LOCATION, BACKGROUND, DAILY_MORNING, DAILY_EVENING, URGENT, PERSISTENT, BLUE, MOTION, TEMP_CHANGE, WIND, EXTREME, SPEECH }

    fun settingFlow(key: SettingKey): kotlinx.coroutines.flow.Flow<Boolean> {
        val s = container.settings
        return when (key) {
            SettingKey.LOCATION -> s.locationEnabled
            SettingKey.BACKGROUND -> s.allowBackgroundLocation
            SettingKey.DAILY_MORNING -> s.dailyMorningNotification
            SettingKey.DAILY_EVENING -> s.dailyEveningNotification
            SettingKey.URGENT -> s.urgentNotification
            SettingKey.PERSISTENT -> s.persistentNotification
            SettingKey.BLUE -> s.weatherBlueIcons
            SettingKey.MOTION -> s.weatherMotion
            SettingKey.TEMP_CHANGE -> s.tempChangeNotification
            SettingKey.WIND -> s.windNotification
            SettingKey.EXTREME -> s.extremeNotification
            SettingKey.SPEECH -> s.speechEnabled
        }
    }

    fun setSetting(key: SettingKey, value: Boolean) {
        scope.launch {
            val s = container.settings
            when (key) {
                SettingKey.LOCATION -> s.setLocationEnabled(value)
                SettingKey.BACKGROUND -> s.setAllowBackgroundLocation(value)
                SettingKey.DAILY_MORNING -> s.setDailyMorning(value)
                SettingKey.DAILY_EVENING -> s.setDailyEvening(value)
                SettingKey.URGENT -> s.setUrgent(value)
                SettingKey.PERSISTENT -> s.setPersistent(value)
                SettingKey.BLUE -> s.setWeatherBlueIcons(value)
                SettingKey.MOTION -> s.setWeatherMotion(value)
                SettingKey.TEMP_CHANGE -> s.setTempChange(value)
                SettingKey.WIND -> s.setWind(value)
                SettingKey.EXTREME -> s.setExtreme(value)
                SettingKey.SPEECH -> s.setSpeechEnabled(value)
            }
        }
    }

    /** 开关行用：读取当前值并取反。 */
    fun toggleSetting(key: SettingKey) {
        scope.launch {
            val current = settingFlow(key).first()
            setSetting(key, !current)
        }
    }

    /** @return (云代理状态, 备源链说明) 文案 */
    fun dataSourceStatus(): Pair<String, String> =
        (if (container.proxyConfigured) "已打通（小米为主 · 彩云补短临/预警/逐时概率）" else "未配置（keys.properties 的 cloudbase_proxy_url）") to
            (if (container.amapGeo.configured) "高德命名/搜索经云代理可用" else "云代理未配置，仅内置城市表")

    /** 「早晚天气通知」在南风里是一行，实际同时管两个到点任务。 */
    fun setDailyBoth(enabled: Boolean) {
        scope.launch {
            container.settings.setDailyMorning(enabled)
            container.settings.setDailyEvening(enabled)
        }
    }

    // ---- 单位 ----

    val temperatureUnit = container.settings.temperatureUnit.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "C")
    val windUnit = container.settings.windUnit.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "KMH")
    val pressureUnit = container.settings.pressureUnit.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "HPA")
    val precipitationUnit = container.settings.precipitationUnit.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "MM")

    fun setTemperatureUnit(v: String) = scope.launch { container.settings.setTemperatureUnit(v) }
    fun setWindUnit(v: String) = scope.launch { container.settings.setWindUnit(v) }
    fun setPressureUnit(v: String) = scope.launch { container.settings.setPressureUnit(v) }
    fun setPrecipitationUnit(v: String) = scope.launch { container.settings.setPrecipitationUnit(v) }

    // ---- 通用设置（卡片显隐/排序/材质/颜色模式）----

    fun stringSetting(key: String, default: String): Flow<String> =
        container.settings.stringFlow(key, default)

    fun setStringSetting(key: String, v: String) {
        scope.launch { container.settings.setString(key, v) }
    }

    fun boolSetting(key: String, default: Boolean): Flow<Boolean> =
        container.settings.boolFlow(key, default)

    fun setBoolSetting(key: String, v: Boolean) {
        scope.launch { container.settings.setBool(key, v) }
    }

    /** 主页可见卡片（含排序），外观页改完这里立刻生效。 */
    val homeCards = container.settings.homeCardsFlow
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeCards.defaultOrder.filter { HomeCards.defaultVisible(it) })

    /** 首页署名行：取当前城市快照的来源主名（「小米 + 彩云(补)」→「小米」）。 */
    suspend fun sourceOf(cityId: String?): String =
        container.weatherRepository.source(cityId ?: "").orEmpty().substringBefore(" +").trim()
}
