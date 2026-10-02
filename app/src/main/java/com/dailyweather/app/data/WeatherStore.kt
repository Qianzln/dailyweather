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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("longitude", longitude).put("latitude", latitude)
        .put("isCurrentLocation", isCurrentLocation).put("sortOrder", sortOrder)
        .put("isBookmarked", isBookmarked)
        .put("cityName", cityName).put("districtName", districtName)
        .put("coordinateVerified", coordinateVerified)

    companion object {
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

    suspend fun putSnapshot(cityId: String, source: String, snapshot: WeatherSnapshot) {
        ds.edit {
            it[weatherRawKey(cityId)] = SnapshotCodec.toJson(snapshot).put("source", source).toString()
            it[weatherTsKey(cityId)] = snapshot.fetchedAt
        }
    }

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
