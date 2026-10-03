package com.dailyweather.app.repository

import android.util.Log
import com.dailyweather.app.data.City
import com.dailyweather.app.data.WeatherStore
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.data.remote.CaiyunCloudProvider
import com.dailyweather.app.data.remote.CloudProxy
import com.dailyweather.app.data.remote.ProxyNotConfiguredException
import com.dailyweather.app.data.remote.WeatherProvider
import com.dailyweather.app.data.Fixture
import com.dailyweather.app.data.remote.XiaomiProvider
import kotlinx.coroutines.flow.Flow

/**
 * 天气仓库：**小米为主、彩云为辅**（和风已去掉）。
 *
 * 不是"谁先成功用谁"的降级链，而是主源 + 补字段：
 * - 小米一次给全逐小时 / 15 天逐日 / AQI 六项，这是首页卡片的地基；
 * - 彩云只补小米给不了的三样——分钟级短临描述、气象预警、逐小时降水概率。
 *
 * 主源失败时退化成"彩云单独支撑"（逐日会只剩 3 天，但页面照常出数）；
 * 辅源失败不影响主快照，只是那三项为空——UI 对空值已有诚实占位。
 */
class WeatherRepository(
    private val weatherStore: WeatherStore,
    private val cityRepository: CityRepository,
    private val providers: List<WeatherProvider>,
    /** debug 视觉校准用的固定快照；非空则整轮不走网络。由 AppContainer 决定是否注入。 */
    private val fixtureProvider: (Long) -> WeatherSnapshot? = { null },
) {

    suspend fun cached(cityId: String): WeatherSnapshot? = weatherStore.getSnapshot(cityId)

    fun observe(cityId: String): Flow<WeatherSnapshot?> = weatherStore.observeSnapshot(cityId)

    suspend fun lastUpdated(cityId: String): Long = weatherStore.getSnapshot(cityId)?.fetchedAt ?: 0L

    suspend fun source(cityId: String): String? = weatherStore.snapshotSource(cityId)

    suspend fun refresh(city: City): WeatherSnapshot {
        Fixture.state?.let { name ->
            val fx = fixtureProvider(System.currentTimeMillis())
            if (fx != null) {
                weatherStore.putSnapshot(city.id, "fixture:$name", fx)
                Log.i(TAG, "doRefreshWeather: fixture=$name 已注入，不走网络")
                return fx
            }
            Log.w(TAG, "fixture=$name 加载失败，回落真实数据")
        }
        val errors = mutableListOf<String>()
        var base: WeatherSnapshot? = null
        var baseName = ""
        val merged = mutableListOf<String>()

        for (provider in providers) {
            val snapshot = try {
                provider.fetch(city)
            } catch (e: ProxyNotConfiguredException) {
                throw DataSourceNotConfiguredException("数据源尚未配置，暂时无法获取天气")
            } catch (e: Exception) {
                Log.w(TAG, "${provider.name}源失败: ${e.message}")
                errors += "${provider.name}: ${e.message}"
                continue
            }
            if (base == null) {
                base = snapshot
                baseName = provider.name
                merged += provider.name
            } else {
                base = mergeGaps(base!!, snapshot)
                merged += "${provider.name}(补)"
            }
        }
        val result = base ?: throw IllegalStateException("全部天气源失败 — ${errors.joinToString("; ")}")
        weatherStore.putSnapshot(city.id, merged.joinToString(" + "), result)
        Log.i(TAG, "doRefreshWeather: 完成, source=${merged.joinToString(" + ")}, cityId=${city.id}")
        return result
    }

    /** 用 [extra] 填 [base] 的空缺字段；base 已有的值一律不覆盖。 */
    private fun mergeGaps(base: WeatherSnapshot, extra: WeatherSnapshot): WeatherSnapshot {
        val hourly = if (base.hourly.any { it.precipitationProbability > 0 } || extra.hourly.isEmpty()) {
            base.hourly
        } else {
            base.hourly.mapIndexed { i, p ->
                val xh = extra.hourly.getOrNull(i)
                p.copy(
                    precipitationProbability = xh?.precipitationProbability ?: 0.0,
                    aqi = p.aqi.takeIf { it > 0 } ?: xh?.aqi ?: 0,
                )
            }
        }
        val daily = if (base.daily.size >= extra.daily.size) base.daily else extra.daily
        return base.copy(
            realtime = base.realtime?.let { rt ->
                val x = extra.realtime
                rt.copy(
                    aqi = if (rt.aqi > 0) rt.aqi else x?.aqi ?: rt.aqi,
                    pm25 = if (rt.pm25 > 0) rt.pm25 else x?.pm25 ?: rt.pm25,
                    airQualityDesc = rt.airQualityDesc.ifBlank { x?.airQualityDesc ?: "" },
                    comfortDesc = rt.comfortDesc.ifBlank { x?.comfortDesc ?: "" },
                )
            },
            forecastKey = base.forecastKey.ifBlank { extra.forecastKey },
            minutelyDesc = base.minutelyDesc.ifBlank { extra.minutelyDesc },
            minutely = base.minutely ?: extra.minutely,
            hourly = hourly,
            daily = daily,
            alerts = if (base.alerts.isEmpty()) extra.alerts else base.alerts,
        )
    }

    companion object {
        private const val TAG = "WeatherRepository"
    }
}

class DataSourceNotConfiguredException(message: String) : IllegalStateException(message)

/** 标准链：小米（主）→ 彩云（补短临 / 预警 / 逐时概率）。 */
object ProviderChain {
    fun standard(proxy: CloudProxy, caiyunToken: String): List<WeatherProvider> = listOf(
        XiaomiProvider(proxy),
        CaiyunCloudProvider(proxy, caiyunToken),
    )
}
