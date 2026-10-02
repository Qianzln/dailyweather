package com.dailyweather.app.advice

import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.scene.SceneBridge
import com.dailyweather.app.scene.WeatherKind

/**
 * 首页生活建议的短标签。
 *
 * **阈值全部取自微风天气已验证的派生表**（`WeatherDerivation.kt` 的
 * dressing / uv / umbrella / cold / drive / skin 六段），不是这里拍的。
 * 上游三源在这个订阅下都拿不到生活指数——彩云免费档的 `life_index` 只有
 * ultraviolet + comfort 两项，和风的 `indices` 返回 400，小米的 `indices`
 * 只有一个 `uvIndex`——所以派生是唯一出路，微风也是这么做的。
 *
 * 微风给的是「等级 + 长文案」两级，南风首页是四字短语，这里只做最后一层措辞映射。
 */
data class AdviceCell(val label: String, val glyph: String)

object LifeAdviceFrom {

    fun of(s: WeatherSnapshot): List<AdviceCell> {
        val rt = s.realtime ?: return emptyList()
        val today = s.daily.firstOrNull()
        val kind = SceneBridge.kindOf(s.currentSkycon)

        val rainy = kind == WeatherKind.RAIN || kind == WeatherKind.THUNDER
        val snowy = kind == WeatherKind.SNOW
        val foggy = kind == WeatherKind.FOG
        val uv = maxOf(rt.uvIndex, today?.uvIndex?.toDouble() ?: 0.0).toFloat().coerceIn(0f, 12f)
        val hum = (rt.humidity * 100).toFloat().coerceIn(0f, 100f)
        val feelsLike = rt.apparentTemperature.toFloat()
        val temperature = rt.temperature.toFloat()
        val wind = rt.windSpeed.toFloat()
        val pop = s.hourly.take(6).maxOfOrNull { it.precipitationProbability.toFloat() } ?: 0f
        val spread = (today?.let { it.tempMax - it.tempMin } ?: 0.0).toFloat().coerceIn(0f, 20f)
        val wetAhead = !rainy && !snowy && pop >= 40f

        val dressing = when {
            feelsLike >= 30 -> "清凉短袖"
            feelsLike >= 25 -> "短袖即可"
            feelsLike >= 18 -> "适宜长袖"
            feelsLike >= 10 -> "外加外套"
            feelsLike >= 0 -> "厚外套"
            else -> "羽绒保暖"
        }

        val umbScore = if (rainy || snowy) 0.90f else pop / 100f
        val umbrella = when {
            umbScore >= 0.70f -> "有雨带伞"
            umbScore >= 0.40f -> "建议带伞"
            umbScore >= 0.20f -> "可备雨伞"
            else -> "无需带伞"
        }

        val coldScore = (
            ((20f - feelsLike).coerceIn(0f, 20f) / 20f) * 0.45f +
                (spread / 20f) * 0.20f +
                (if (rainy || snowy) 0.20f else 0f) +
                (if (wind >= 25f) 0.15f else 0f)
            ).coerceIn(0f, 1f)
        val cold = when {
            coldScore >= 0.70f -> "谨防感冒"
            coldScore >= 0.50f -> "较易着凉"
            coldScore >= 0.30f -> "注意作息"
            else -> "不易感冒"
        }

        val driveScore = (
            1f -
                (if (rainy) 0.35f else 0f) -
                (if (foggy) 0.55f else 0f) -
                (if (snowy) 0.55f else 0f) -
                (if (wind >= 35f) 0.25f else 0f) -
                (if (wetAhead) 0.25f else 0f)
            ).coerceIn(0f, 1f)
        val drive = when {
            driveScore <= 0.25f -> "不宜出行"
            driveScore <= 0.55f -> "谨慎驾驶"
            driveScore <= 0.80f -> "注意路滑"
            else -> "适宜行驶"
        }

        val oilScore = (
            ((temperature - 18f).coerceIn(0f, 18f) / 18f) * 0.6f +
                ((100f - hum).coerceIn(0f, 60f) / 60f) * 0.4f
            ).coerceIn(0f, 1f)
        val skin = when {
            uv >= 6 -> "先要防晒"
            hum <= 35 -> "干燥保湿"
            oilScore >= 0.60f -> "清爽控油"
            hum >= 70 -> "皮肤滋润"
            else -> "常规护肤"
        }

        val sun = when {
            uv >= 8 -> "避免暴晒"
            uv >= 6 -> "涂防晒霜"
            uv >= 3 -> "注意防晒"
            else -> "无需防晒"
        }

        return listOf(
            AdviceCell(dressing, "tshirt"),
            AdviceCell(sun, "sun.max"),
            AdviceCell(umbrella, "umbrella"),
            AdviceCell(cold, "pills"),
            AdviceCell(drive, "car"),
            AdviceCell(skin, "face.smiling"),
        )
    }
}
