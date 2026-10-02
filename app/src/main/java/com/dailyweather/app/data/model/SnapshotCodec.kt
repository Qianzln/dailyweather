package com.dailyweather.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * WeatherSnapshot ⇄ JSON 编解码（缓存标准化格式）。
 *
 * 多源接入后（彩云/和风/小米），缓存里存的不再是任一上游的原始响应，
 * 而是归一化快照 + 来源标签——组件/通知/设置的订阅面对上游完全无感。
 */
object SnapshotCodec {

    fun toJson(snapshot: WeatherSnapshot): JSONObject = JSONObject()
        .put("cityId", snapshot.cityId)
        .put("fetchedAt", snapshot.fetchedAt)
        .put("serverTime", snapshot.serverTime)
        .put("forecastKey", snapshot.forecastKey)
        .put("minutelyDesc", snapshot.minutelyDesc)
        .put("realtime", snapshot.realtime?.let { rt ->
            JSONObject()
                .put("temperature", rt.temperature)
                .put("apparentTemperature", rt.apparentTemperature)
                .put("humidity", rt.humidity)
                .put("skycon", rt.skycon)
                .put("windSpeed", rt.windSpeed)
                .put("windDirection", rt.windDirection)
                .put("aqi", rt.aqi)
                .put("pm25", rt.pm25)
                .put("uvIndex", rt.uvIndex)
                .put("comfortDesc", rt.comfortDesc)
                .put("airQualityDesc", rt.airQualityDesc)
        })
        .put("hourly", JSONArray().apply {
            snapshot.hourly.forEach { h ->
                put(JSONObject().put("time", h.time).put("temperature", h.temperature)
                    .put("skycon", h.skycon).put("precipitationProbability", h.precipitationProbability))
            }
        })
        .put("daily", JSONArray().apply {
            snapshot.daily.forEach { d ->
                put(JSONObject().put("date", d.date).put("skycon", d.skycon)
                    .put("tempMin", d.tempMin).put("tempMax", d.tempMax)
                    .put("windSpeed", d.windSpeed).put("windDirection", d.windDirection)
                    .put("sunrise", d.sunrise).put("sunset", d.sunset)
                    .put("uvIndex", d.uvIndex).put("aqiAvg", d.aqiAvg)
                    .put("precipitation", d.precipitation).put("precipitationProbability", d.precipitationProbability))
            }
        })
        .put("alerts", JSONArray().apply {
            snapshot.alerts.forEach { a ->
                put(JSONObject().put("title", a.title).put("status", a.status)
                    .put("description", a.description).put("source", a.source)
                    .put("pubTime", a.pubTime))
            }
        })

    fun fromJson(root: JSONObject): WeatherSnapshot? = runCatching {
        WeatherSnapshot(
            cityId = root.optString("cityId"),
            fetchedAt = root.optLong("fetchedAt"),
            serverTime = root.optLong("serverTime"),
            forecastKey = root.optString("forecastKey"),
            minutelyDesc = root.optString("minutelyDesc"),
            realtime = root.optJSONObject("realtime")?.let { rt ->
                WeatherSnapshot.Realtime(
                    temperature = rt.optDouble("temperature"),
                    apparentTemperature = rt.optDouble("apparentTemperature"),
                    humidity = rt.optDouble("humidity"),
                    skycon = rt.optString("skycon"),
                    windSpeed = rt.optDouble("windSpeed"),
                    windDirection = rt.optInt("windDirection"),
                    aqi = rt.optInt("aqi"),
                    pm25 = rt.optDouble("pm25"),
                    uvIndex = rt.optDouble("uvIndex"),
                    comfortDesc = rt.optString("comfortDesc"),
                    airQualityDesc = rt.optString("airQualityDesc"),
                )
            },
            hourly = buildList {
                val arr = root.optJSONArray("hourly") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val h = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherSnapshot.HourlyPoint(
                            time = h.optLong("time"),
                            temperature = h.optDouble("temperature"),
                            skycon = h.optString("skycon"),
                            precipitationProbability = h.optDouble("precipitationProbability"),
                        )
                    )
                }
            },
            daily = buildList {
                val arr = root.optJSONArray("daily") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherSnapshot.DailyPoint(
                            date = d.optString("date"),
                            skycon = d.optString("skycon"),
                            tempMin = d.optDouble("tempMin"),
                            tempMax = d.optDouble("tempMax"),
                            windSpeed = d.optDouble("windSpeed"),
                            windDirection = d.optInt("windDirection"),
                            sunrise = d.optString("sunrise"),
                            sunset = d.optString("sunset"),
                            uvIndex = d.optInt("uvIndex"),
                            aqiAvg = d.optInt("aqiAvg"),
                            precipitation = d.optDouble("precipitation"),
                            precipitationProbability = d.optDouble("precipitationProbability"),
                        )
                    )
                }
            },
            alerts = buildList {
                val arr = root.optJSONArray("alerts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    add(
                        WeatherSnapshot.AlertItem(
                            title = a.optString("title"),
                            status = a.optString("status"),
                            description = a.optString("description"),
                            source = a.optString("source"),
                            pubTime = a.optLong("pubTime"),
                        )
                    )
                }
            },
        )
    }.getOrNull()
}
