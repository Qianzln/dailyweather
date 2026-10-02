package com.dailyweather.app.ui.components

/**
 * 彩云 skycon →（中文描述, 图标资产名）映射。
 * 图标资产名对齐 Meteocons 命名（MIT，assets/weather 与 assets/weather_blue 两套）。
 */
object SkyconMap {

    private val table = mapOf(
        "CLEAR_DAY" to ("晴" to "clear-day"),
        "CLEAR_NIGHT" to ("晴夜" to "clear-night"),
        "PARTLY_CLOUDY_DAY" to ("多云" to "partly-cloudy-day"),
        "PARTLY_CLOUDY_NIGHT" to ("多云夜" to "partly-cloudy-night"),
        "CLOUDY" to ("阴" to "overcast"),
        "OVERCAST" to ("阴" to "overcast"),
        "LIGHT_HAZE" to ("轻度雾霾" to "haze"),
        "MODERATE_HAZE" to ("中度雾霾" to "haze"),
        "HEAVY_HAZE" to ("重度雾霾" to "haze"),
        "LIGHT_RAIN" to ("小雨" to "drizzle"),
        "MODERATE_RAIN" to ("中雨" to "rain"),
        "HEAVY_RAIN" to ("大雨" to "rain"),
        "STORM_RAIN" to ("暴雨" to "extreme-rain"),
        "FOG" to ("雾" to "fog"),
        "LIGHT_SNOW" to ("小雪" to "snow"),
        "MODERATE_SNOW" to ("中雪" to "snow"),
        "HEAVY_SNOW" to ("大雪" to "snow"),
        "STORM_SNOW" to ("暴雪" to "extreme-snow"),
        "DUST" to ("浮尘" to "haze"),
        "SAND" to ("沙尘" to "haze"),
        "WIND" to ("大风" to "wind"),
        "RAIN_SNOW" to ("雨夹雪" to "rain-snow"),
        "THUNDER_SHOWER" to ("雷阵雨" to "thunderstorms-rain"),
        "RAIN" to ("雨" to "rain"),
        "SNOW" to ("雪" to "snow"),
    )

    fun desc(skycon: String): String = table[skycon]?.first ?: skycon

    fun asset(skycon: String): String = table[skycon]?.second ?: "overcast"

    /** 夜间的雨用带月亮的变体；其余夜间态 skycon 本身已带 _NIGHT。 */
    fun asset(skycon: String, isNight: Boolean): String =
        if (isNight && (skycon == "LIGHT_RAIN" || skycon == "DRIZZLE")) "night-drizzle" else asset(skycon)

    fun aqiLevel(aqi: Int): String = when {
        aqi <= 0 -> "—"
        aqi <= 50 -> "优"
        aqi <= 100 -> "良"
        aqi <= 150 -> "轻度污染"
        aqi <= 200 -> "中度污染"
        aqi <= 300 -> "重度污染"
        else -> "严重污染"
    }
}
