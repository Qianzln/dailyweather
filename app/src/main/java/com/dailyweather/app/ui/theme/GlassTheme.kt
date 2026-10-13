package com.dailyweather.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 玻璃（Haze）参数常量 —— 南风 v4.3.79 里是**两套**，混用会同时毁掉顶栏和卡片：
 *
 * | 位置 | 模糊半径 | noiseFactor | 来源 |
 * |---|---|---|---|
 * | 卡片 / 玻璃面（`PairedGlassHaze`、`GlassCard`） | **22dp** | **0.035** | `va_PairedGlassHazeKt` :390-398、`C1046n6` :414-422 |
 * | 顶栏小控件（`CurrentWeather.kt` 的 `y0()`） | **16dp** | **0.02** | `C1051o3` :1585-1592 |
 *
 * 两者都用 `tints = [HazeTint(glass.tint)]`、`fallbackTint = glass.tintFallback`、
 * `backgroundColor = 天气主题渐变中间色`。南风未设置 `alpha` / `inputScale` /
 * `progressive` / `blurredEdgeTreatment` —— 即全部走库默认值（alpha=1、InputScale.None）。
 */
object WeatherGlass {

    // ---- 卡片 / 玻璃面 ----
    val CardBlurRadius = 26.dp
    const val CARD_NOISE_FACTOR = 0.04f

    // ---- 顶栏小控件 ----
    val ControlBlurRadius = 16.dp
    const val CONTROL_NOISE_FACTOR = 0.02f

    /**
     * 卡片在 Haze 生效时的填色 alpha。
     *
     * 南风卡面是「主题中间色（不透明）+ 22dp 模糊 + 玻璃 tint」三层叠出来的霜面；
     * 我们此前是 `cardFill@0.78` 纯色叠天空。接上 Haze 后如果仍留 0.78，
     * 模糊几乎透不上来（等于没接）。v0.1.32 取 0.62 后真机观感仍偏实色板（填色盖住了
     *  Haze 层大半），v0.1.33 降到 0.45，实测仍比南风截图偏实。
     *
     * 南风吹法：卡面透出天空，模糊纹理清晰可见，颜色随天空渐变自然过渡。
     * GL 3D 场景启用后（v0.1.47+），云层比纯色渐变更丰富，需要进一步降低填充使云层透出。
     * 0.22：Haze 模糊层为绝对主体，填色只做极弱色调倾向，云层清晰可见。
     */
    const val CARD_HAZE_FILL_ALPHA = 0.22f

    /** 无 Haze 环境（详情页等没有采样源的场景）下的填色 alpha —— 与历史行为一致。 */
    const val CARD_SOLID_FILL_ALPHA = 0.78f
}
