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
        skycon.contains("SNOW") || skycon.contains("SLEET") -> WeatherKind.SNOW
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

    /** 风强 0–1：以 60 km/h 为满档。 */
    fun windOf(snapshot: WeatherSnapshot?): Float =
        ((snapshot?.realtime?.windSpeed ?: 0.0) / 60.0).toFloat().coerceIn(0.05f, 1f)

    fun stateFor(
        snapshot: WeatherSnapshot?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): WeatherSceneState {
        val kind = kindOf(snapshot?.currentSkycon ?: "")
        val wet = kind == WeatherKind.RAIN || kind == WeatherKind.THUNDER || kind == WeatherKind.SNOW
        // 相位必须按城市当地时间判：设备在别的时区时，默认时区会把白天判成夜。
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
        return WeatherSceneState(
            kind = kind,
            phase = phaseOf(cal),
            intensity = if (wet) intensityOf(snapshot, snapshot?.serverTime ?: 0L) else 0f,
            wind = windOf(snapshot),
        )
    }
}
