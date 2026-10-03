package com.dailyweather.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 彩云 v2.6 响应的解析后快照（仅抽取 UI/组件/通知需要的字段）。
 * 多源接入后为归一化结构：三源（彩云/和风/小米）各自映射到本类，缓存经 SnapshotCodec 落盘。
 */
data class WeatherSnapshot(
    val cityId: String,
    val fetchedAt: Long,
    val serverTime: Long,
    val forecastKey: String,
    val realtime: Realtime?,
    val minutelyDesc: String,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    val alerts: List<AlertItem>,
) {
    data class Realtime(
        val temperature: Double,
        val apparentTemperature: Double,
        val humidity: Double,
        val skycon: String,
        val windSpeed: Double,
        val windDirection: Int,
        val aqi: Int,
        val pm25: Double,
        val uvIndex: Double,
        val comfortDesc: String,
        val airQualityDesc: String,
        val pressureHpa: Double = 0.0,
        val visibilityKm: Double = 0.0,
    )

    data class HourlyPoint(
        val time: Long,
        val temperature: Double,
        val skycon: String,
        val precipitationProbability: Double,
    )

    data class DailyPoint(
        val date: String,
        val skycon: String,
        val tempMin: Double,
        val tempMax: Double,
        val windSpeed: Double,
        val windDirection: Int,
        val sunrise: String,
        val sunset: String,
        val uvIndex: Int,
        val aqiAvg: Int,
        val precipitation: Double,
        /** 降水概率 0–100（不是毫米数）：7 日卡图标下那个青色百分比用它。 */
        val precipitationProbability: Double = 0.0,
    )

    data class AlertItem(
        val title: String,
        val status: String,
        val description: String,
        val source: String,
        val pubTime: Long,
    )

    val currentSkycon: String get() = realtime?.skycon ?: hourly.firstOrNull()?.skycon ?: "CLEAR_DAY"
    val currentTemp: Double get() = realtime?.temperature ?: hourly.firstOrNull()?.temperature ?: 0.0
}

object CaiyunParser {

    fun parse(cityId: String, fetchedAt: Long, json: JSONObject): WeatherSnapshot {
        val result = json.optJSONObject("result") ?: json
        val serverTime = json.optLong("server_time", fetchedAt / 1000) * 1000

        val realtime = result.optJSONObject("realtime")?.let { r ->
            val aqiObj = r.optJSONObject("air_quality")
            val aqiChn = aqiObj?.optJSONObject("aqi")
            val desc = aqiObj?.optJSONObject("description")
            val lifeIndex = r.optJSONObject("life_index")
            WeatherSnapshot.Realtime(
                temperature = r.optDouble("temperature", 0.0),
                apparentTemperature = r.optDouble("apparent_temperature", 0.0),
                humidity = r.optDouble("humidity", 0.0),
                skycon = r.optString("skycon", ""),
                windSpeed = r.optJSONObject("wind")?.optDouble("speed", 0.0) ?: 0.0,
                windDirection = r.optJSONObject("wind")?.optDouble("direction", 0.0)?.toInt() ?: 0,
                aqi = aqiChn?.optInt("chn", 0) ?: 0,
                pm25 = aqiObj?.optDouble("pm25", 0.0) ?: 0.0,
                uvIndex = lifeIndex?.optJSONObject("ultraviolet")?.optDouble("index", 0.0) ?: 0.0,
                comfortDesc = lifeIndex?.optJSONObject("comfort")?.optString("desc", "") ?: "",
                airQualityDesc = desc?.optString("chn", "") ?: "",
                pressureHpa = r.optDouble("pressure", 0.0),
                visibilityKm = r.optDouble("visibility", 0.0),
            )
        }

        val hourly = result.optJSONObject("hourly")?.let { h ->
            val temps = h.optJSONArray("temperature") ?: JSONArray()
            val skycons = h.optJSONArray("skycon") ?: JSONArray()
            val probs = h.optJSONArray("probability")
            buildList {
                for (i in 0 until temps.length()) {
                    val t = temps.optJSONObject(i) ?: continue
                    add(
                        WeatherSnapshot.HourlyPoint(
                            time = parseIsoToEpoch(t.optString("datetime")),
                            temperature = t.optDouble("value", 0.0),
                            skycon = skycons.optJSONObject(i)?.optString("value", "") ?: "",
                            precipitationProbability = probs?.optJSONObject(i)?.optDouble("value", 0.0) ?: 0.0,
                        )
                    )
                }
            }
        } ?: emptyList()

        val daily = result.optJSONObject("daily")?.let { d ->
            val temps = d.optJSONArray("temperature") ?: JSONArray()
            val skycons = d.optJSONArray("skycon") ?: JSONArray()
            val winds = d.optJSONArray("wind") ?: JSONArray()
            val astro = d.optJSONArray("astro") ?: JSONArray()
            val uv = d.optJSONObject("life_index")?.optJSONArray("ultraviolet") ?: JSONArray()
            val precip = d.optJSONArray("precipitation") ?: JSONArray()
            val prob = d.optJSONArray("probability")
            buildList {
                for (i in 0 until temps.length()) {
                    val a = astro.optJSONObject(i)
                    add(
                        WeatherSnapshot.DailyPoint(
                            date = temps.optJSONObject(i)?.optString("date")?.take(10) ?: "",
                            skycon = skycons.optJSONObject(i)?.optString("value", "") ?: "",
                            tempMin = temps.optJSONObject(i)?.optDouble("min", 0.0) ?: 0.0,
                            tempMax = temps.optJSONObject(i)?.optDouble("max", 0.0) ?: 0.0,
                            windSpeed = winds.optJSONObject(i)?.optJSONObject("avg")
                                ?.optDouble("speed", 0.0) ?: 0.0,
                            windDirection = winds.optJSONObject(i)?.optJSONObject("avg")
                                ?.optDouble("direction", 0.0)?.toInt() ?: 0,
                            sunrise = a?.optJSONObject("sunrise")?.optString("time", "") ?: "",
                            sunset = a?.optJSONObject("sunset")?.optString("time", "") ?: "",
                            uvIndex = uv.optJSONObject(i)?.optString("index")?.toIntOrNull() ?: 0,
                            aqiAvg = 0,
                            precipitation = precip.optJSONObject(i)?.optDouble("avg", 0.0) ?: 0.0,
                            precipitationProbability = prob?.optJSONObject(i)?.optDouble("value", 0.0) ?: 0.0,
                        )
                    )
                }
            }
        } ?: emptyList()

        val alerts = result.optJSONObject("alert")?.optJSONArray("content")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherSnapshot.AlertItem(
                            title = a.optString("title"),
                            status = a.optString("status"),
                            description = a.optString("description"),
                            source = a.optString("source"),
                            pubTime = a.optLong("pubtimestamp", 0L) * 1000,
                        )
                    )
                }
            }
        } ?: emptyList()

        return WeatherSnapshot(
            cityId = cityId,
            fetchedAt = fetchedAt,
            serverTime = serverTime,
            forecastKey = result.optString("forecast_keypoint"),
            realtime = realtime,
            minutelyDesc = result.optJSONObject("minutely")?.optString("description", "") ?: "",
            hourly = hourly,
            daily = daily,
            alerts = alerts,
        )
    }

    private fun parseIsoToEpoch(iso: String): Long = runCatching {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrElse { 0L }
}
