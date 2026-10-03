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
    /** 分钟级降水序列（含描述）；上游没给时为 null。 */
    val minutely: Minutely? = null,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
    val alerts: List<AlertItem>,
) {
    /** 内容签名（组件按需渲染用）：取实况温度+天气+小时首点+日卡首点的指纹。
     *  变化时才触发重绘；定位中/无数据返回空签名强制刷新。 */
    fun contentSignature(): String = buildString {
        append(realtime?.temperature?.toInt() ?: "")
        append(realtime?.skycon ?: "")
        append(hourly.firstOrNull()?.temperature?.toInt() ?: "")
        append(daily.firstOrNull()?.date ?: "")
    }
    data class Realtime(
        val temperature: Double,
        val apparentTemperature: Double,
        val humidity: Double,
        val skycon: String,
        val windSpeed: Double,
        val windDirection: Int,
        val aqi: Int,
        val pm25: Double,
        /** 其余污染物 µg/m³（CO 为 mg/m³ 口径时照上游原值显示）。0 = 上游没给。 */
        val pm10: Double = 0.0,
        val o3: Double = 0.0,
        val no2: Double = 0.0,
        val so2: Double = 0.0,
        val co: Double = 0.0,
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
        /** 逐小时降水量 mm（彩云 hourly.precipitation[].value）。0 = 上游没给。 */
        val precipitationMm: Double = 0.0,
        /** 逐小时 AQI（小米 forecastHourly.aqi）。0 = 上游没给。 */
        val aqi: Int = 0,
    )

    /** 分钟级降水（未来两小时，每分钟一个值；小米/彩云都给）。 */
    data class Minutely(
        val description: String,
        /** 120 个值，mm/h 口径照上游原值。 */
        val precipitation2h: List<Double>,
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
                pm10 = aqiObj?.optDouble("pm10", 0.0) ?: 0.0,
                o3 = aqiObj?.optDouble("o3", 0.0) ?: 0.0,
                no2 = aqiObj?.optDouble("no2", 0.0) ?: 0.0,
                so2 = aqiObj?.optDouble("so2", 0.0) ?: 0.0,
                co = aqiObj?.optDouble("co", 0.0) ?: 0.0,
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
            // 共享 key 的综合响应没有独立 probability 数组时，逐小时降水数组里
            // 每项自带 probability（0–100）——这是小时条降水百分比的真正来源。
            val precip = h.optJSONArray("precipitation")
            buildList {
                for (i in 0 until temps.length()) {
                    val t = temps.optJSONObject(i) ?: continue
                    val prob = precip?.optJSONObject(i)?.optDouble("probability", 0.0)
                        ?: probs?.optJSONObject(i)?.optDouble("value", 0.0)
                        ?: 0.0
                    add(
                        WeatherSnapshot.HourlyPoint(
                            time = parseIsoToEpoch(t.optString("datetime")),
                            temperature = t.optDouble("value", 0.0),
                            skycon = skycons.optJSONObject(i)?.optString("value", "") ?: "",
                            precipitationProbability = prob,
                            precipitationMm = precip?.optJSONObject(i)?.optDouble("value", 0.0) ?: 0.0,
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

        val minutelyObj = result.optJSONObject("minutely")
        val minutely = minutelyObj?.let { m ->
            val vals = m.optJSONArray("precipitation_2h")
            WeatherSnapshot.Minutely(
                description = m.optString("description", ""),
                precipitation2h = if (vals != null) (0 until vals.length()).map { vals.optDouble(it, 0.0) } else emptyList(),
            )
        }

        return WeatherSnapshot(
            cityId = cityId,
            fetchedAt = fetchedAt,
            serverTime = serverTime,
            forecastKey = result.optString("forecast_keypoint"),
            realtime = realtime,
            minutelyDesc = minutelyObj?.optString("description", "") ?: "",
            minutely = minutely?.takeIf { it.precipitation2h.isNotEmpty() },
            hourly = hourly,
            daily = daily,
            alerts = alerts,
        )
    }

    private fun parseIsoToEpoch(iso: String): Long = runCatching {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrElse { 0L }
}
