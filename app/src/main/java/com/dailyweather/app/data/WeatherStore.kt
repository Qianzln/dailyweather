package com.dailyweather.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailyweather.app.data.model.SnapshotCodec
import com.dailyweather.app.data.model.WeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.weatherDataStore by preferencesDataStore(name = "daily_weather")

/** 城市（对齐南风天气 `cities` 表的最终列集）。 */
data class City(
    val id: String,
    val name: String,
    val longitude: Double,
    val latitude: Double,
    val isCurrentLocation: Boolean = false,
    val sortOrder: Int = 0,
    val isBookmarked: Boolean = false,
    val cityName: String = "",
    val districtName: String = "",
    val coordinateVerified: Boolean = false,
    /** 城市当地时区（对齐南风 cityTimeContext）；空串时按经度兜底，见 [zone]。 */
    val timeZoneId: String = "",
) {
    /**
     * 城市当地时区。
     *
     * ⚠️ 缺 `timeZoneId` 时**不能**回退设备时区：数据源给的 `sunrise`/`sunset` 是**当地**时刻，
     * 而 `now` 若取设备时区，两者就会「两地时间混用」。实测（模拟器定位在加州、设备时区 UTC、
     * 城市时区为空）：当地下午被当成别的时段，选出 `1_3_sunny-afternoon` 这类**错键**。
     * 所以按经度兜底（每 15° 一个时区），对时段槽位的分段粒度（≈67min）足够。
     */
    val zone: java.time.ZoneId
        get() = if (timeZoneId.isNotBlank()) {
            runCatching { java.time.ZoneId.of(timeZoneId) }.getOrDefault(fallbackZone())
        } else fallbackZone()

    private fun fallbackZone(): java.time.ZoneId =
        runCatching { java.time.ZoneId.of(inferZoneId(longitude, latitude)) }
            .getOrDefault(java.time.ZoneId.systemDefault())

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("longitude", longitude).put("latitude", latitude)
        .put("isCurrentLocation", isCurrentLocation).put("sortOrder", sortOrder)
        .put("isBookmarked", isBookmarked)
        .put("cityName", cityName).put("districtName", districtName)
        .put("coordinateVerified", coordinateVerified)
        .put("timeZoneId", timeZoneId)

    companion object {
        /**
         * 按坐标推断时区 id（用于数据源未提供时区时兜底）。
         *
         * 中国境内统一返回 `Asia/Shanghai`（行政上是单一时区，跨 5 个地理时区）；
         * 其余按经度每 15° 一个时区。误差 ≤1h（夏令时另算），对时段槽位足够。
         */
        fun inferZoneId(longitude: Double, latitude: Double): String {
            if (longitude in 73.0..135.0 && latitude in 3.0..54.0) return "Asia/Shanghai"
            val hours = kotlin.math.round(longitude / 15.0).toInt().coerceIn(-12, 14)
            return java.time.ZoneOffset.ofTotalSeconds(hours * 3600).id
        }

        fun fromJson(o: JSONObject) = City(
            id = o.getString("id"),
            name = o.getString("name"),
            longitude = o.getDouble("longitude"),
            latitude = o.getDouble("latitude"),
            isCurrentLocation = o.optBoolean("isCurrentLocation"),
            sortOrder = o.optInt("sortOrder"),
            isBookmarked = o.optBoolean("isBookmarked"),
            cityName = o.optString("cityName"),
            districtName = o.optString("districtName"),
            coordinateVerified = o.optBoolean("coordinateVerified"),
            timeZoneId = o.optString("timeZoneId"),
        )
    }
}

/**
 * 天气缓存（阶段一用 Preferences DataStore 承载；阶段二换 Room 时只需替换此类，
 * 仓库层与 UI/组件/通知的订阅面不动）：
 * - `cities_json`：全部城市（整包 JSON 数组，对应 `cities` 表）
 * - `weather/<cityId>/raw` + `weather/<cityId>/ts`：整包响应 JSON（对应 `weather` 表 responseJson/lastUpdated）
 */
class WeatherStore(private val context: Context) {

    private val ds = context.weatherDataStore

    // ---- cities ----
    val citiesFlow: Flow<List<City>> = ds.data.map { p ->
        val arr = JSONArray(p[CITIES] ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(City::fromJson)
        }.sortedBy { it.sortOrder }
    }

    suspend fun getCities(): List<City> = citiesFlow.first()

    suspend fun saveCities(cities: List<City>) {
        val arr = JSONArray()
        cities.sortedBy { it.sortOrder }.forEach { arr.put(it.toJson()) }
        ds.edit { it[CITIES] = arr.toString() }
    }

    // ---- weather cache（归一化快照 + 来源标签；多源接入后上游无感）----
    fun observeSnapshot(cityId: String): Flow<WeatherSnapshot?> = ds.data.map { p ->
        snapshotOf(p, cityId)
    }

    suspend fun getSnapshot(cityId: String): WeatherSnapshot? = snapshotOf(ds.data.first(), cityId)

    /**
     * 写快照。按 cityId 加锁串行化 —— 同一城市的并发刷新（手动刷新 / 组件 Worker /
     * 通知 Worker 三路都可能触发）若不串行，DataStore 的读改写会互相覆盖。
     * 阶段二换 Room 后由 SQLite 乐观锁接管（对齐南风 `UPDATE ... WHERE responseJson=?`）。
     */
    suspend fun putSnapshot(cityId: String, source: String, snapshot: WeatherSnapshot) {
        writeLocks.computeIfAbsent(cityId) { Mutex() }.withLock {
            ds.edit {
                it[weatherRawKey(cityId)] = SnapshotCodec.toJson(snapshot).put("source", source).toString()
                it[weatherTsKey(cityId)] = snapshot.fetchedAt
            }
        }
    }

    private val writeLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun snapshotSource(cityId: String): String? =
        ds.data.first()[weatherRawKey(cityId)]
            ?.let { runCatching { JSONObject(it).optString("source") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    suspend fun removeCity(cityId: String) {
        ds.edit {
            it.remove(weatherRawKey(cityId))
            it.remove(weatherTsKey(cityId))
        }
    }

    private fun snapshotOf(p: androidx.datastore.preferences.core.Preferences, cityId: String): WeatherSnapshot? {
        val raw = p[weatherRawKey(cityId)] ?: return null
        return runCatching { SnapshotCodec.fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun weatherRawKey(cityId: String) = stringPreferencesKey("weather/$cityId/raw")
    private fun weatherTsKey(cityId: String) = longPreferencesKey("weather/$cityId/ts")

    private companion object {
        val CITIES = stringPreferencesKey("cities_json")
    }
}
