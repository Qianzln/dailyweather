package com.dailyweather.app.data

import android.content.Context
import com.dailyweather.app.data.model.WeatherSnapshot
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 视觉校准用的固定数据（仅 debug 构建生效）。
 *
 * 为什么需要它：实时数据每个小时都在变，拿它跟南风的某一张截图逐像素比，
 * 比出来的差分不清是"我画错了"还是"天气变了"。喂一份写死的快照，
 * 才能把 晴/多云/阴/雨/雪/雾/雷 每个状态稳定复现，一次只改一个因素。
 *
 * 关键约定：**时间一律写相对偏移**（小时用 `m` = 距今多少小时，日用 `d` = 距今多少天），
 * 不写绝对时间戳。写死时间戳的 fixture 过一天就错位，"现在"那一格会跑到别的钟点上。
 */
object Fixture {

    /** null = 走真实数据。由 MainActivity 从 intent extra 读入，release 构建永远为 null。 */
    var state: String? = null

    /** fixture 里写 `hour` 就能固定时段，不必等真实时钟到那个点才能对夜档。 */
    var hourOverride: Int? = null

    fun load(context: Context, name: String, nowEpochMs: Long): WeatherSnapshot? = runCatching {
        val json = JSONObject(context.assets.open("fixture/$name.json").use { it.readBytes().decodeToString() })
        parse(json, name, nowEpochMs)
    }.getOrNull()

    private fun parse(root: JSONObject, name: String, now: Long): WeatherSnapshot {
        val zone = ZoneId.systemDefault()
        Fixture.hourOverride = root.optInt("hour", -1).takeIf { it >= 0 }
        val today = LocalDate.now(zone)
        val skycon = root.getString("skycon")

        val hourly = root.getJSONArray("hourly").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WeatherSnapshot.HourlyPoint(
                    time = now + o.getLong("m") * 3600_000L,
                    temperature = o.getDouble("t"),
                    skycon = o.optString("s").ifBlank { skycon },
                    precipitationProbability = o.optDouble("p", 0.0),
                )
            }
        }
        val daily = root.getJSONArray("daily").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WeatherSnapshot.DailyPoint(
                    date = today.plusDays(o.getLong("d")).toString(),
                    skycon = o.optString("s").ifBlank { skycon },
                    tempMin = o.getDouble("min"),
                    tempMax = o.getDouble("max"),
                    windSpeed = o.optDouble("wind", 0.0),
                    windDirection = o.optInt("windDir", 0),
                    sunrise = o.optString("sunrise", "06:12"),
                    sunset = o.optString("sunset", "18:04"),
                    uvIndex = o.optInt("uv", 0),
                    aqiAvg = o.optInt("aqi", 0),
                    precipitation = 0.0,
                    precipitationProbability = o.optDouble("p", 0.0),
                )
            }
        }
        val alerts = root.optJSONArray("alerts").let { arr ->
            if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WeatherSnapshot.AlertItem(
                    title = o.optString("title"),
                    status = o.optString("status", "预警中"),
                    description = o.optString("desc"),
                    source = "fixture",
                    pubTime = now,
                )
            }
        }
        val temp = root.getDouble("temp")
        return WeatherSnapshot(
            cityId = "current",
            fetchedAt = now,
            serverTime = now,
            forecastKey = root.optString("forecastKey"),
            realtime = WeatherSnapshot.Realtime(
                temperature = temp,
                apparentTemperature = root.optDouble("feels", temp),
                humidity = root.optDouble("humidity", 0.6),
                skycon = skycon,
                windSpeed = root.optDouble("wind", 6.0),
                windDirection = root.optInt("windDir", 0),
                aqi = root.optInt("aqi", 0),
                pm25 = root.optDouble("pm25", 0.0),
                uvIndex = root.optDouble("uv", 0.0),
                comfortDesc = root.optString("comfort"),
                airQualityDesc = root.optString("airDesc"),
                pressureHpa = root.optDouble("pressure", 0.0),
                visibilityKm = root.optDouble("visibility", 0.0),
            ),
            minutelyDesc = root.optString("minutely"),
            hourly = hourly,
            daily = daily,
            alerts = alerts,
        ).also { FixtureState.lastLoaded = name }
    }
}

/** 只为让界面上能看出当前吃的是哪份 fixture（调试条用），不参与任何业务判断。 */
object FixtureState {
    var lastLoaded: String? = null
}

