package com.dailyweather.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * 单位换算与格式化（Compose State 版）。
 *
 * **快照里永远存上游原值**（温度 °C、风速 km/h、气压 hPa、降水 mm），
 * 只在显示这一层换算。理由：派生规则（生活建议、穿衣、感冒风险）全部按 °C 与
 * km/h 定的阈值，一旦把换算后的值存进快照，用户改一次单位这些阈值就全错位了。
 * 所以换算发生在且只发生在 [Units.*Text] 里。
 *
 * 使用 Compose [mutableStateOf] 包裹各字段，设置页切换单位后**首页立即重组**，
 * 不必手动切页面再回来。
 */
object Units {

    var temperature by mutableStateOf("C")
    var wind by mutableStateOf("KMH")
    var pressure by mutableStateOf("HPA")
    var precipitation by mutableStateOf("MM")

    /** 摄氏 → 当前温度单位下的数值。 */
    fun tempValue(celsius: Double): Double =
        if (temperature == "F") celsius * 9.0 / 5.0 + 32.0 else celsius

    /** 首页那种「21°」的写法（不带单位符号，南风的卡片里只给度数）。 */
    fun temp(celsius: Double): String = "${tempValue(celsius).roundToInt()}°"

    /** 带单位的完整写法，给详情页与设置预览用。 */
    fun tempText(celsius: Double): String =
        "${tempValue(celsius).roundToInt()}${if (temperature == "F") "°F" else "°C"}"

    /** 风级（蒲福）：由 km/h 归档，南风的「风级」选项走这条。 */
    fun beaufort(kmh: Double): Int = when {
        kmh < 1 -> 0
        kmh < 6 -> 1
        kmh < 12 -> 2
        kmh < 20 -> 3
        kmh < 29 -> 4
        kmh < 39 -> 5
        kmh < 50 -> 6
        kmh < 62 -> 7
        kmh < 75 -> 8
        kmh < 89 -> 9
        kmh < 103 -> 10
        kmh < 118 -> 11
        else -> 12
    }

    fun windValue(kmh: Double): Double = when (wind) {
        "MS" -> kmh / 3.6
        "MPH" -> kmh * 0.621371
        "KN" -> kmh / 1.852
        else -> kmh
    }

    fun windUnitLabel(): String = when (wind) {
        "MS" -> "米/秒"
        "MPH" -> "英里/小时"
        "KN" -> "节"
        "LEVEL" -> "级"
        else -> "公里/小时"
    }

    fun windText(kmh: Double): String = if (wind == "LEVEL") {
        "${beaufort(kmh)} 级"
    } else {
        "${windValue(kmh).roundToInt()} ${windUnitLabel()}"
    }

    fun pressureText(hpa: Double): String = when (pressure) {
        "INHG" -> "${"%.2f".format(hpa * 0.02953)} inHg"
        "MMHG" -> "${(hpa * 0.750062).roundToInt()} mmHg"
        else -> "${hpa.roundToInt()} hPa"
    }

    fun precipitationText(mm: Double): String = if (precipitation == "IN") {
        "${"%.2f".format(mm / 25.4)} in"
    } else {
        "${"%.1f".format(mm)} mm"
    }
}

/** visibility 固定 km（数据源不稳定，不补齐 MI 开关）。 */
fun visibilityText(km: Double): String = "${km.roundToInt()} km"
