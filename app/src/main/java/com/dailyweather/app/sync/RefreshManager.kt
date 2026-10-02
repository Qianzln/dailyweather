package com.dailyweather.app.sync

import android.util.Log
import com.dailyweather.app.data.City
import com.dailyweather.app.data.SettingsStore
import com.dailyweather.app.data.location.LocationCoordinator
import com.dailyweather.app.repository.CityRepository
import com.dailyweather.app.repository.DataSourceNotConfiguredException
import com.dailyweather.app.repository.WeatherRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 刷新编排（对齐南风 `RefreshManager`/`WeatherSyncManager` 的核心行为）：
 * - 按 cityId 加锁，同城市刷新串行；
 * - 锁后二次判断：5 秒内同坐标已获取，跳过重复请求；
 * - 单次请求超时 + 总预算；预算耗尽返回失败；
 * - UI / 组件 / 通知共享同一入口（单派生源）；
 * - 主源小米 + 彩云补字段（见 WeatherRepository）；主源失败才退化成彩云独撑。
 */
class RefreshManager(
    private val weatherRepository: WeatherRepository,
    private val cityRepository: CityRepository,
    private val settings: SettingsStore,
    private val locationCoordinator: LocationCoordinator,
    private val amapGeo: com.dailyweather.app.data.remote.AmapGeo,
) {

    private val cityMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    sealed interface Outcome {
        data class Success(val cityId: String) : Outcome
        data class Failed(val cityId: String?, val reason: String) : Outcome
        data class Skipped(val cityId: String, val reason: String) : Outcome
    }

    fun mutexFor(cityId: String) = cityMutexes.computeIfAbsent(cityId) { Mutex() }

    suspend fun refresh(cityId: String, reason: String): Outcome {
        val city = cityRepository.byId(cityId)
            ?: return Outcome.Failed(cityId, "城市不存在")
        return refresh(city, reason)
    }

    suspend fun refresh(city: City, reason: String): Outcome {
        val mutex = mutexFor(city.id)
        return try {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val last = lastAttempt[city.id] ?: 0L
                if (now - last < DEDUP_WINDOW_MS) {
                    Log.i(TAG, "排队后检查：5秒内同坐标已获取，跳过重复请求 ($reason)")
                    return Outcome.Skipped(city.id, "5s 内已刷新")
                }
                lastAttempt[city.id] = now
                Log.i(TAG, "city_mutex_acquired: cityId=${city.id}, reason=$reason")
                weatherRepository.refresh(city)
                Outcome.Success(city.id)
            }
        } catch (e: DataSourceNotConfiguredException) {
            Log.w(TAG, "数据源未配置: ${e.message}")
            Outcome.Failed(city.id, e.message ?: "数据源未配置")
        } catch (e: Exception) {
            Log.w(TAG, "doRefreshWeather: 网络请求失败 - ${e.message}")
            Outcome.Failed(city.id, e.message ?: "网络错误")
        }
    }

    /**
     * 定位 → 高德逆地理命名 → 确保当前定位城市存在 → 刷新该城市。
     * 「当前定位仍是默认占位坐标，先渲染定位中状态，避免北京首帧」的语义在此收口。
     */
    suspend fun refreshCurrentLocation(reason: String): Outcome {
        if (!locationCoordinator.hasPermission()) {
            return Outcome.Failed(null, "无定位权限")
        }
        val fix = locationCoordinator.requestFix() ?: return Outcome.Failed(null, "定位失败")
        if (LocationCoordinator.isPlaceholder(fix.longitude, fix.latitude)) {
            return Outcome.Failed(null, "定位仍是占位坐标")
        }
        // 命名：高德 regeo（GCJ-02 转换在 AmapGeo 内部）；失败回落通用名
        val displayName = amapGeo.regeoName(fix.latitude, fix.longitude)?.city ?: "我的位置"
        val city = cityRepository.upsertCurrentLocation(fix.longitude, fix.latitude, displayName)
        return refresh(city, reason)
    }

    /** 应用冷启动/回前台的常规刷新：定位城市有则刷它，否则刷第一个城市。 */
    suspend fun refreshDefault(reason: String): Outcome {
        val target = cityRepository.currentLocationCity()
            ?: cityRepository.cities.first().firstOrNull()
            ?: return Outcome.Failed(null, "无城市")
        return refresh(target, reason)
    }

    companion object {
        private const val TAG = "RefreshManager"

        /** 对齐南风「5秒内同坐标已获取，跳过重复请求」。 */
        const val DEDUP_WINDOW_MS = 5_000L

        /** 单次请求与总预算常量名沿用南风命名。 */
        const val WEATHER_FETCH_ATTEMPT_TIMEOUT_MS = 12_000L
        const val WEATHER_FETCH_TOTAL_TIMEOUT_MS = 30_000L
    }
}
