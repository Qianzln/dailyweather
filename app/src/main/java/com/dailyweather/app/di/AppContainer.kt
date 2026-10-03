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

    /** 应用更新检查（经代理读 GitHub 版本元数据）。返回最新版本号，检测失败返回 null。 */
    suspend fun checkLatestVersion(): String? {
        if (!proxyConfigured) return null
        return runCatching {
            val body = proxy.get("/weather/app/latest")
            val root = org.json.JSONObject(body)
            // 云函数透传 GitHub releases/latest 的 tag_name 等，只取版本号。
            root.optString("tag_name").ifBlank { root.optString("version").ifBlank { "" } }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
