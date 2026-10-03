package com.dailyweather.app.scene

import com.dailyweather.app.data.Fixture
import com.dailyweather.app.data.model.WeatherSnapshot
import java.util.Calendar

/**
 * 快照 → 场景状态。首页只认 [WeatherSceneState]，不再自己判时段、判天气。
 *
 * 时段分桶照南风场景键里 `skycon_08h_20h` / `skycon_20h_32h` 的取向：
 * 清晨与傍晚是独立档，不是「白天」暗一点的版本。
 */
object SceneBridge {

    fun phaseOf(cal: Calendar): SkyPhase = when (Fixture.hourOverride ?: cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..8 -> SkyPhase.MORNING
        in 9..16 -> SkyPhase.DAY
        in 17..19 -> SkyPhase.EVENING
        else -> SkyPhase.NIGHT
    }

    fun kindOf(skycon: String): WeatherKind = when {
        skycon.startsWith("THUNDER") -> WeatherKind.THUNDER
        skycon.contains("SLEET") -> WeatherKind.SLEET
        skycon.contains("SNOW") -> WeatherKind.SNOW
        skycon.contains("RAIN") || skycon.contains("DRIZZLE") || skycon.contains("SHOWER") -> WeatherKind.RAIN
        skycon == "CLOUDY" || skycon == "OVERCAST" -> WeatherKind.CLOUDY
        skycon.startsWith("PARTLY_CLOUDY") -> WeatherKind.PARTLY_CLOUDY
        skycon.startsWith("CLEAR") -> WeatherKind.CLEAR
        skycon.contains("HAZE") || skycon.contains("FOG") || skycon.contains("MIST") -> WeatherKind.FOG
        skycon.contains("DUST") || skycon.contains("SAND") -> WeatherKind.FOG
        skycon.contains("WIND") || skycon.contains("GALE") -> WeatherKind.WIND
        else -> WeatherKind.PARTLY_CLOUDY
    }

    /** 降水强度：取未来两小时内的最高降水概率。 */
    fun intensityOf(snapshot: WeatherSnapshot?, nowEpochMs: Long): Float {
        val snap = snapshot ?: return 0f
        return snap.hourly
            .filter { it.time in nowEpochMs until nowEpochMs + 2 * 3600_000L }
            .maxOfOrNull { it.precipitationProbability.toFloat() }
            ?.div(100f)?.coerceIn(0f, 1f) ?: 0f
    }

    /**
     * 日照进度（对应南风 SunPose）：当前时刻在日出→日落区间里的位置 0–1。
     * 夜间/缺数据返回 null，太阳不画。
     */
    fun sunProgressOf(snapshot: WeatherSnapshot?, zone: java.time.ZoneId, nowEpochMs: Long): Float? {
        val today = snapshot?.daily?.firstOrNull() ?: return null
        if (today.sunrise.isBlank() || today.sunset.isBlank()) return null
        val rise = minutesOf(today.sunrise) ?: return null
        val set = minutesOf(today.sunset) ?: return null
        if (set <= rise) return null
        val now = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zone)
        val nowMin = now.hour * 60 + now.minute + now.second / 60f
        return ((nowMin - rise) / (set - rise).toFloat())
            .takeIf { it in 0f..1f }
    }

    /** "HH:mm" → 当天第几分钟；解析失败返回 null。 */
    private fun minutesOf(s: String): Int? {
        val parts = s.take(5).split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    /** 风强 0–1：以 60 km/h 为满档。 */
    fun windOf(snapshot: WeatherSnapshot?): Float =
        ((snapshot?.realtime?.windSpeed ?: 0.0) / 60.0).toFloat().coerceIn(0.05f, 1f)

    fun stateFor(
        snapshot: WeatherSnapshot?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): WeatherSceneState {
        val kind = kindOf(snapshot?.currentSkycon ?: "")
        // 相位必须按城市当地时间判：设备在别的时区时，默认时区会把白天判成夜。
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
        val nowMs = System.currentTimeMillis()
        val wet = kind == WeatherKind.RAIN || kind == WeatherKind.THUNDER ||
            kind == WeatherKind.SNOW || kind == WeatherKind.SLEET
        return WeatherSceneState(
            kind = kind,
            phase = phaseOf(cal),
            intensity = if (wet) intensityOf(snapshot, snapshot?.serverTime ?: 0L) else 0f,
            wind = windOf(snapshot),
            sunProgress = sunProgressOf(snapshot, zone, nowMs),
        )
    }
}
