package com.dailyweather.app.scene

import androidx.compose.ui.graphics.Color

/**
 * 云的重打光光照档。
 *
 * 字段结构取自南风包里的烘焙常量（字符串池原文可查）：`BakeSkyFillColor` /
 * `BakeOvercastSkyFillColor` / `BakeAmbientColor`+`Dir` / `BakeGroundColor`+`Dir` /
 * `BakeSunColor`+`Dir` —— 四组光，每组「颜色 + 方向」。一张白模云 + 一套光照档 =
 * 该场景下该物种的云，所以它不必为「晴天清晨 / 暴雨夜晚 / 阴天傍晚」分别出图。
 *
 * 数值来源分两类：标 `[实测]` 的是从南风真机截图逐像素采下来的，其余是照同一骨架
 * 自定的。南风的具体颜色在方法体常量里、静态取不到，不要把自定值当成逆向事实。
 */
data class CloudLightRig(
    /** 天空填充色：云体接收到的来自上方天空的漫射，也是「晴」的基调。 */
    val skyFill: Color,
    /** 转阴时的天空填充色。[overcast] 在两者之间插值，所以阴天不需要新素材。 */
    val overcastSkyFill: Color,
    /** 环境光：各向同性的底光，控制云的整体明度下限。 */
    val ambient: Color,
    val ambientStrength: Float,
    /** 地面反弹光：夜间偏冷、雪天偏亮，主要作用于云底。 */
    val ground: Color,
    val groundStrength: Float,
    /** 太阳（或月亮）光：唯一有方向的高光项，决定云的「金边」。 */
    val sun: Color,
    val sunStrength: Float,
    /** 太阳方向 -1..1：+1 正午，0 地平线（金边最强），-1 已落到地平线以下。 */
    val sunDir: Float,
    /** 转阴度 0–1：0=晴，1=完全阴天。 */
    val overcast: Float,
) {

    /**
     * 把「晴」光照档压成「阴」光照档。
     *
     * 转阴不是只换天空色：太阳与环境光必须一起塌下去，否则加色合成会把云洗成
     * 一片死白，而南风的阴云只是**比天空略亮一点的灰**。
     */
    fun dampedTo(t: Float): CloudLightRig {
        val k = t.coerceIn(0f, 1f)
        return copy(
            ambientStrength = ambientStrength * (1f - 0.82f * k),
            groundStrength = groundStrength * (1f - 0.40f * k),
            sunStrength = sunStrength * (1f - 0.94f * k),
            overcast = k,
        )
    }

    /** 缓存键：浮点量化到两位小数，避免逐位抖动导致缓存永不命中。 */
    fun bakeQuantum(): String = buildString {
        append(quant(skyFill)); append('|')
        append(quant(overcastSkyFill)); append('|')
        append(quant(ambient)); append('@'); append(q2(ambientStrength)); append('|')
        append(quant(ground)); append('@'); append(q2(groundStrength)); append('|')
        append(quant(sun)); append('@'); append(q2(sunStrength)); append('@'); append(q2(sunDir)); append('|')
        append(q2(overcast))
    }

    companion object {
        private fun quant(c: Color): String =
            "${c.red.toQuant8()},${c.green.toQuant8()},${c.blue.toQuant8()}"

        private fun Float.toQuant8(): Int = (this * 255f + 0.5f).toInt().coerceIn(0, 255)

        private fun q2(v: Float): Int = (v * 100f + 0.5f).toInt()
    }
}

/** 一次烘焙的缓存键：物种 + 形状序号 + 光照档量化串。 */
data class CloudBakeKey(
    val species: CloudSpecies,
    val shapeIndex: Int,
    val rigQuantum: String,
)

/**
 * 时段 → 「晴」基准光照档，转阴统一由 [CloudLightRig.dampedTo] 派生。
 *
 * 矩阵的行（[SkyPhase]）决定光的颜色与方向，列（天气）决定转阴度与云场，
 * 两维正交，于是 4 × 8 = 32 个场景不需要 32 套素材。
 */
fun lightRigFor(phase: SkyPhase, overcast: Float): CloudLightRig = when (phase) {
    SkyPhase.MORNING -> CloudLightRig(
        skyFill = Color(0xFF8FB6E8),
        overcastSkyFill = Color(0xFF8E8F93),
        ambient = Color(0xFF4A5A78), ambientStrength = 0.30f,
        ground = Color(0xFF8A6A50), groundStrength = 0.16f,
        sun = Color(0xFFFFC58A), sunStrength = 0.85f, sunDir = 0.05f,
        overcast = 0f,
    )

    // [实测] 阴天云带在南风截图里落在 #515964 → #5B5C60，取中值 #565A61。
    SkyPhase.DAY -> CloudLightRig(
        skyFill = Color(0xFF6FA6E8),
        overcastSkyFill = Color(0xFF8D8B8E),
        ambient = Color(0xFF5B7295), ambientStrength = 0.34f,
        ground = Color(0xFF6B7A8C), groundStrength = 0.10f,
        sun = Color(0xFFFFFBEA), sunStrength = 0.72f, sunDir = 0.92f,
        overcast = 0f,
    )

    SkyPhase.EVENING -> CloudLightRig(
        skyFill = Color(0xFF7C7FB8),
        overcastSkyFill = Color(0xFF4E4A5C),
        ambient = Color(0xFF46406A), ambientStrength = 0.28f,
        ground = Color(0xFF9A5C3C), groundStrength = 0.26f,
        sun = Color(0xFFFF9A4E), sunStrength = 0.95f, sunDir = -0.08f,
        overcast = 0f,
    )

    SkyPhase.NIGHT -> CloudLightRig(
        skyFill = Color(0xFF2A3A62),
        overcastSkyFill = Color(0xFF505154),
        ambient = Color(0xFF1A2242), ambientStrength = 0.22f,
        ground = Color(0xFF22304E), groundStrength = 0.12f,
        // 夜间的 sun 槽位复用为月光：冷蓝、低强度、有方向。
        sun = Color(0xFFBFD6FF), sunStrength = 0.34f, sunDir = 0.45f,
        overcast = 0f,
    )
}.dampedTo(overcast)
