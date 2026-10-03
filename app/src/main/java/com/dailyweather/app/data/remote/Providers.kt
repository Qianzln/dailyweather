package com.dailyweather.app.data.remote

import android.util.Log
import com.dailyweather.app.data.City
import com.dailyweather.app.data.model.CaiyunParser
import com.dailyweather.app.data.model.WeatherSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** 天气源统一接口：一次 fetch 返回解析后的快照；失败抛异常走辅源。 */
interface WeatherProvider {
    val name: String
    /** true = 主体快照来源；false = 只用来补主源拿不到的字段（短临 / 预警 / 逐时概率）。 */
    val auxiliary: Boolean get() = false
    suspend fun fetch(city: City): WeatherSnapshot
}

/**
 * 彩云 v2.6 综合天气 —— **辅源**（经 CloudProxy `/weather/caiyun`）。
 *
 * 它独有的三样东西是小米源给不了的：分钟级短临描述（`minutely.description`）、
 * 气象预警（`alert`）、逐小时降水概率。所以链路是「小米为主、彩云补这三项」，
 * 而不是谁成功就用谁。
 *
 * 已知边界（微风实测）：共享 Key 的 `dailysteps` 硬上限 3 天且静默截断，
 * 所以逐日不再依赖它——那正是交给小米源的部分。
 */
class CaiyunCloudProvider(
    private val proxy: CloudProxy,
    private val userToken: String,
) : WeatherProvider {

    override val name: String get() = "彩云"
    override val auxiliary: Boolean get() = true

    override suspend fun fetch(city: City): WeatherSnapshot {
        val params = buildMap {
            put("lon", city.longitude.toString())
            put("lat", city.latitude.toString())
            put("alert", "true")
            put("dailysteps", "7")
            put("hourlysteps", "48")
            if (userToken.isNotBlank()) put("caiyun_token", userToken.trim())
        }
        val body = proxy.get("/weather/caiyun", params)
        val json = JSONObject(body)
        if (json.optString("status") != "ok") {
            throw IllegalStateException("彩云 status=${json.optString("status")}")
        }
        return CaiyunParser.parse(city.id, System.currentTimeMillis(), json)
    }
}

/**
 * 小米天气（wtr-v3）—— **主源**（经 CloudProxy 两步：`/weather/xiaomi-geo` 反查
 * locationKey，再 `/weather/xiaomi` 取综合天气）。
 *
 * 选它当主源的理由（口径全部来自微风 2026-09-29 的实测样本）：
 * - 一次给全：实况 + 23 条逐小时 + 15 天逐日 + AQI 六项污染物，且**不随 `days` 截断**；
 *   彩云共享 Key 的逐日只有 3 天，这是主源必须给够的部分。
 * - `appKey`/`sign`/`locale` 是社区公开常量、由云函数内置，APK 零凭据。
 * - `isGlobal` 由云函数按 locationKey 前缀推导，客户端不可伪造。
 *
 * 三条不能含糊的口径：
 * - 数值一律包在 `{value, unit}` 里且 value 是**字符串**；单位不认就是缺失，不猜换算。
 * - `current.weather` 是**天气代码字符串**（`"0"`）不是中文，必须走 [WEATHER_TEXT] 码表。
 * - 逐小时**没有降水概率**：该粒度填 0 表示"无此数据"，不拿逐日概率冒充。
 */
class XiaomiProvider(private val proxy: CloudProxy) : WeatherProvider {

    override val name: String get() = "小米"

    override suspend fun fetch(city: City): WeatherSnapshot {
        val lat = "%.2f".format(city.latitude)
        val lon = "%.2f".format(city.longitude)
        val geoArr = JSONArray(
            proxy.get("/weather/xiaomi-geo", mapOf("latitude" to lat, "longitude" to lon))
        )
        val locationKey = (0 until geoArr.length()).mapNotNull { i ->
            geoArr.optJSONObject(i)
                ?.optString("locationKey", "")
                ?.takeIf { it.startsWith("weathercn:") || it.startsWith("accu:") }
        }.firstOrNull() ?: throw IllegalStateException("小米 geo 无可用 locationKey")

        val root = JSONObject(
            proxy.get(
                "/weather/xiaomi",
                mapOf("latitude" to lat, "longitude" to lon, "locationKey" to locationKey, "days" to "7"),
            )
        )
        return parse(city.id, root)
    }

    internal fun parse(cityId: String, root: JSONObject): WeatherSnapshot {
        val nowEpoch = System.currentTimeMillis()
        val current = root.optJSONObject("current") ?: throw IllegalStateException("小米无 current")

        val tempNow = valueOf(wrapped(current, "temperature"))
            ?: throw IllegalStateException("小米缺 temperature")
        val feelsLike = valueOf(wrapped(current, "feelsLike")) ?: tempNow
        val humidity = valueOf(wrapped(current, "humidity"))?.div(100.0) ?: 0.0
        val windSpeed = windSpeedOf(wrapped(current, "wind")) ?: 0.0
        val windDir = valueOf(wrapped(current, "wind")?.optJSONObject("direction")) ?: 0.0
        val code = current.optString("weather", "")
        val skycon = weatherCodeToSkycon(code, isNight = !isDayHour(Calendar24.hour()))

        val aqiObj = root.optJSONObject("aqi")
        val aqi = aqiObj?.optString("aqi", "")?.toIntOrNull()?.takeIf { it > 0 } ?: 0

        val sun0 = root.optJSONObject("forecastDaily")?.optJSONObject("sunRiseSet")?.optJSONArray("value")?.optJSONObject(0)
            ?: throw IllegalStateException("小米无 forecastDaily.sunRiseSet")
        val sunrise = hourOf(sun0.optString("from", ""))
        val sunset = hourOf(sun0.optString("to", ""))

        val daily = buildDaily(root.optJSONObject("forecastDaily") ?: JSONObject())
        if (daily.isEmpty()) throw IllegalStateException("小米 daily 为空")
        val hourly = buildHourly(root.optJSONObject("forecastHourly") ?: JSONObject(), nowEpoch)

        val realtime = WeatherSnapshot.Realtime(
            temperature = tempNow,
            apparentTemperature = feelsLike,
            humidity = humidity,
            skycon = skycon,
            windSpeed = windSpeed,
            windDirection = windDir.roundToInt(),
            aqi = aqi,
            pm25 = aqiObj?.optString("pm25", "")?.toDoubleOrNull() ?: 0.0,
            uvIndex = valueOf(wrapped(current, "uvIndex")) ?: 0.0,
            comfortDesc = "",
            airQualityDesc = aqiLevelCn(aqi),
            pressureHpa = pressureHpaOf(wrapped(current, "pressure")),
            visibilityKm = visibilityKmOf(wrapped(current, "visibility")),
        )
        Log.i(TAG, "小米解析完成: skycon=$skycon aqi=$aqi hourly=${hourly.size} daily=${daily.size}")
        return WeatherSnapshot(
            cityId = cityId,
            fetchedAt = nowEpoch,
            serverTime = nowEpoch,
            forecastKey = "",
            realtime = realtime,
            minutelyDesc = "",
            hourly = hourly,
            daily = daily,
            alerts = emptyList(),
        )
    }

    /**
     * 逐小时：只读 `temperature` / `weather` / `wind` 三条数组，
     * 时间取 `wind[i].datetime` 的完整 ISO（**不按下标推导**）。缺时间或缺温度的小时直接跳过。
     */
    private fun buildHourly(hourly: JSONObject, nowEpoch: Long): List<WeatherSnapshot.HourlyPoint> {
        val temps = hourly.optJSONObject("temperature")?.optJSONArray("value") ?: return emptyList()
        val weathers = hourly.optJSONObject("weather")?.optJSONArray("value") ?: return emptyList()
        val winds = hourly.optJSONObject("wind")?.optJSONArray("value") ?: return emptyList()
        val n = minOf(48, temps.length(), weathers.length(), winds.length())
        return (0 until n).mapNotNull { i ->
            val datetime = winds.optJSONObject(i)?.optString("datetime", "").orEmpty()
            if (datetime.length < 16) return@mapNotNull null
            val temp = numAt(temps, i) ?: return@mapNotNull null
            val hourText = datetime.substring(11, 16)
            val skycon = weatherCodeToSkycon(codeAt(weathers, i), isNight = !isDayHour(hourText.take(2).toIntOrNull() ?: 12))
            WeatherSnapshot.HourlyPoint(
                time = isoToEpoch(datetime) ?: (nowEpoch + i * 3600_000L),
                temperature = temp,
                skycon = skycon,
                // 小米逐小时无降水概率：0 = 该粒度无此数据，由彩云辅源补，不拿逐日概率冒充。
                precipitationProbability = 0.0,
            )
        }
    }

    /**
     * 逐日：`sunRiseSet[i].from` 前 10 位是日期；`temperature[i]` 的 **from 是最高温、to 是最低温**；
     * `weather[i]` 可能是 `{from,…}` 对象、数字或字符串，三种都要认。
     */
    private fun buildDaily(dailyRoot: JSONObject): List<WeatherSnapshot.DailyPoint> {
        val suns = dailyRoot.optJSONObject("sunRiseSet")?.optJSONArray("value") ?: return emptyList()
        val temps = dailyRoot.optJSONObject("temperature")?.optJSONArray("value") ?: return emptyList()
        val weathers = dailyRoot.optJSONObject("weather")?.optJSONArray("value") ?: return emptyList()
        val pops = dailyRoot.optJSONObject("precipitationProbability")?.optJSONArray("value") ?: JSONArray()
        val n = minOf(7, suns.length(), temps.length(), weathers.length())
        return (0 until n).mapNotNull { i ->
            val sun = suns.optJSONObject(i) ?: return@mapNotNull null
            val date = sun.optString("from", "").take(10)
            if (date.length < 10) return@mapNotNull null
            val t = temps.optJSONObject(i) ?: return@mapNotNull null
            val high = numAt(t, "from") ?: return@mapNotNull null
            val low = numAt(t, "to") ?: return@mapNotNull null
            WeatherSnapshot.DailyPoint(
                date = date,
                skycon = weatherCodeToSkycon(codeAt(weathers, i), isNight = false),
                tempMin = low,
                tempMax = high,
                windSpeed = 0.0,
                windDirection = 0,
                sunrise = hourOf(sun.optString("from", "")),
                sunset = hourOf(sun.optString("to", "")),
                uvIndex = 0,
                aqiAvg = 0,
                precipitation = 0.0,
                precipitationProbability = pops.optString(i, "").toDoubleOrNull() ?: 0.0,
            )
        }
    }

    private fun numAt(arr: JSONArray, i: Int): Double? = when (val v = arr.opt(i)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        is JSONObject -> v.opt("value")?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
        else -> null
    }

    private fun numAt(obj: JSONObject, key: String): Double? =
        obj.opt(key)?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }

    private fun codeAt(arr: JSONArray, i: Int): String = when (val w = arr.opt(i)) {
        is JSONObject -> w.optString("from", "")
        is Number -> w.toInt().toString()
        is String -> w
        else -> ""
    }

    private fun hourOf(iso: String): String = if (iso.length >= 16) iso.substring(11, 16) else ""

    private fun isoToEpoch(iso: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrNull() ?: runCatching {
        java.time.LocalDateTime.parse(iso.substringBefore('+'))
            .toInstant(java.time.ZoneOffset.systemDefault().rules.getOffset(java.time.LocalDateTime.now()))
            .toEpochMilli()
    }.getOrNull()

    companion object {
        private const val TAG = "XiaomiProvider"

        /** 实况的 `{value, unit}` 包装取 value：数字或字符串都认，转不出 = 缺失（null），不填 0。 */
        private fun wrapped(o: JSONObject, key: String): JSONObject? = o.optJSONObject(key)

        private fun valueOf(w: JSONObject?): Double? =
            w?.opt("value")?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }

        private fun unitOf(w: JSONObject?): String = w?.optString("unit", "").orEmpty()
        /** 气压只认 hPa/mb（实测两种都出现过）；认不出单位 = 0 表示没有，不猜换算。 */
        private fun pressureHpaOf(w: JSONObject?): Double {
            val v = valueOf(w) ?: return 0.0
            return if (unitOf(w).lowercase() in setOf("hpa", "mb", "mbar")) v else 0.0
        }

        /** 能见度只认 km；其他单位一律当缺失。 */
        private fun visibilityKmOf(w: JSONObject?): Double {
            val v = valueOf(w) ?: return 0.0
            return if (unitOf(w).lowercase() == "km") v else 0.0
        }

        /** 风速显式声明单位；认不出来就当作缺失，不猜换算。 */
        private fun windSpeedOf(wind: JSONObject?): Double? {
            val w = wind?.optJSONObject("speed") ?: return null
            val v = valueOf(w) ?: return null
            return when (unitOf(w).lowercase()) {
                "km/h", "kmh" -> v
                "m/s" -> v * 3.6
                else -> null
            }
        }

        /** 代码 → 用户可见文案（未映射码不显示假文案）。 */
        val WEATHER_TEXT: Map<String, String> = mapOf(
            "0" to "晴", "1" to "多云", "2" to "阴", "3" to "阵雨", "4" to "雷阵雨",
            "5" to "雷阵雨伴冰雹", "6" to "雨夹雪", "7" to "小雨", "8" to "中雨", "9" to "大雨",
            "10" to "暴雨", "11" to "大暴雨", "12" to "特大暴雨", "13" to "阵雪", "14" to "小雪",
            "15" to "中雪", "16" to "大雪", "17" to "暴雪", "18" to "雾", "19" to "冻雨",
            "20" to "沙尘暴", "21" to "小雨-中雨", "22" to "中雨-大雨", "23" to "大雨-暴雨",
            "24" to "暴雨-大暴雨", "25" to "大暴雨-特大暴雨", "26" to "小雪-中雪", "27" to "中雪-大雪",
            "28" to "大雪-暴雪", "29" to "浮尘", "30" to "扬沙", "31" to "强沙尘暴", "32" to "飑",
            "33" to "龙卷风", "34" to "吹雪", "35" to "轻雾", "53" to "霾",
        )

        /**
         * 代码 → 内部 skycon 词表（图标与场景引擎都吃这套词）。
         *
         * 一个刻意的降级：`35 轻雾` 归到霾/阴而不是 FOG。雾景会把天空钉成灰黄、
         * 不参与昼夜插值，轻雾不该有这个权重——这是微风踩过的坑。
         */
        fun weatherCodeToSkycon(code: String, isNight: Boolean): String = when (code) {
            "0" -> if (isNight) "CLEAR_NIGHT" else "CLEAR_DAY"
            "1" -> if (isNight) "PARTLY_CLOUDY_NIGHT" else "PARTLY_CLOUDY_DAY"
            "2" -> "CLOUDY"
            "3" -> "LIGHT_RAIN"
            "4", "5" -> "THUNDER_SHOWER"
            "6" -> "RAIN_SNOW"
            "7" -> "LIGHT_RAIN"
            "8", "19", "21" -> "MODERATE_RAIN"
            "9", "10", "11", "12", "22", "23", "24", "25" -> "HEAVY_RAIN"
            "13", "14" -> "LIGHT_SNOW"
            "15", "26", "27" -> "MODERATE_SNOW"
            "16", "17", "28", "34" -> "HEAVY_SNOW"
            "18" -> "FOG"
            "20", "31" -> "SAND"
            "29", "30" -> "DUST"
            "32", "33" -> "WIND"
            "35", "53" -> "MODERATE_HAZE"
            else -> if (isNight) "CLEAR_NIGHT" else "CLEAR_DAY"
        }

        fun isDayHour(hour: Int): Boolean = hour in 6..18

        /** AQI 分档文案（国标 HJ 633 口径），小米只给数值不给档位描述。 */
        fun aqiLevelCn(aqi: Int): String = when {
            aqi <= 0 -> ""
            aqi <= 50 -> "优"
            aqi <= 100 -> "良"
            aqi <= 150 -> "轻度污染"
            aqi <= 200 -> "中度污染"
            aqi <= 300 -> "重度污染"
            else -> "严重污染"
        }
    }
}

/** 设备当前小时（24 制）。抽出来是为了让"当前是否白天"只有一处判定。 */
private object Calendar24 {
    fun hour(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
}
