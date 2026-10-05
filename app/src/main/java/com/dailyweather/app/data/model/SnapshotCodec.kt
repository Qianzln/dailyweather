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
        .put("minutely", snapshot.minutely?.let { m ->
            JSONObject().put("description", m.description)
                .put("values", JSONArray(m.precipitation2h))
        })
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
                .put("pm10", rt.pm10).put("o3", rt.o3).put("no2", rt.no2)
                .put("so2", rt.so2).put("co", rt.co)
                .put("uvIndex", rt.uvIndex)
                .put("comfortDesc", rt.comfortDesc)
                .put("airQualityDesc", rt.airQualityDesc)
                .put("pressureHpa", rt.pressureHpa).put("visibilityKm", rt.visibilityKm)
        })
        .put("hourly", JSONArray().apply {
            snapshot.hourly.forEach { h ->
                put(JSONObject().put("time", h.time).put("temperature", h.temperature)
                    .put("skycon", h.skycon).put("precipitationProbability", h.precipitationProbability)
                    .put("precipitationMm", h.precipitationMm).put("aqi", h.aqi))
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
        .put("typhoons", JSONArray().apply {
            snapshot.typhoons.forEach { t ->
                val pathArr = JSONArray().apply {
                    t.path.forEach { p ->
                        put(JSONObject()
                            .put("time", p.time)
                            .put("lat", p.lat)
                            .put("lon", p.lon)
                            .put("wind", p.windSpeedKmh)
                            .put("pressure", p.pressureHpa))
                    }
                }
                put(JSONObject()
                    .put("typhoonId", t.typhoonId)
                    .put("name", t.name)
                    .put("lat", t.currentLat)
                    .put("lon", t.currentLon)
                    .put("windSpeed", t.windSpeedKmh)
                    .put("pressure", t.pressureHpa)
                    .put("moveSpeed", t.moveSpeedKmh)
                    .put("moveDirection", t.moveDirection)
                    .put("path", pathArr))
            }
        })

    fun fromJson(root: JSONObject): WeatherSnapshot? = runCatching {
        WeatherSnapshot(
            cityId = root.optString("cityId"),
            fetchedAt = root.optLong("fetchedAt"),
            serverTime = root.optLong("serverTime"),
            forecastKey = root.optString("forecastKey"),
            minutelyDesc = root.optString("minutelyDesc"),
            minutely = root.optJSONObject("minutely")?.let { m ->
                val arr = m.optJSONArray("values")
                WeatherSnapshot.Minutely(
                    description = m.optString("description"),
                    precipitation2h = if (arr != null) (0 until arr.length()).map { arr.optDouble(it, 0.0) } else emptyList(),
                )
            }?.takeIf { it.precipitation2h.isNotEmpty() },
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
                    pm10 = rt.optDouble("pm10", 0.0),
                    o3 = rt.optDouble("o3", 0.0),
                    no2 = rt.optDouble("no2", 0.0),
                    so2 = rt.optDouble("so2", 0.0),
                    co = rt.optDouble("co", 0.0),
                    uvIndex = rt.optDouble("uvIndex"),
                    comfortDesc = rt.optString("comfortDesc"),
                    airQualityDesc = rt.optString("airQualityDesc"),
                    pressureHpa = rt.optDouble("pressureHpa", 0.0),
                    visibilityKm = rt.optDouble("visibilityKm", 0.0),
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
                            precipitationMm = h.optDouble("precipitationMm", 0.0),
                            aqi = h.optInt("aqi", 0),
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
            typhoons = buildList {
                val tArr = root.optJSONArray("typhoons") ?: return@buildList
                for (i in 0 until tArr.length()) {
                    val t = tArr.optJSONObject(i) ?: continue
                    val pathArr = t.optJSONArray("path") ?: JSONArray()
                    val path = buildList {
                        for (j in 0 until pathArr.length()) {
                            val p = pathArr.optJSONObject(j) ?: continue
                            add(
                                WeatherSnapshot.TyphoonPoint(
                                    time = p.optLong("time", 0L),
                                    lat = p.optDouble("lat", 0.0),
                                    lon = p.optDouble("lon", 0.0),
                                    windSpeedKmh = p.optDouble("wind", 0.0),
                                    pressureHpa = p.optDouble("pressure", 0.0),
                                )
                            )
                        }
                    }
                    add(
                        WeatherSnapshot.Typhoon(
                            typhoonId = t.optString("typhoonId", ""),
                            name = t.optString("name", ""),
                            currentLat = t.optDouble("lat", 0.0),
                            currentLon = t.optDouble("lon", 0.0),
                            windSpeedKmh = t.optDouble("windSpeed", 0.0),
                            pressureHpa = t.optDouble("pressure", 0.0),
                            moveSpeedKmh = t.optDouble("moveSpeed", 0.0),
                            moveDirection = t.optInt("moveDirection", 0),
                            path = path,
                        )
                    )
                }
            },
        )
    }.getOrNull()
}
