package com.dailyweather.app.scene

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 天空渐变的三段色标：天顶 → 中段 → 地平线。
 *
 * 结构照南风的 `SkyGradientStops`。它包里唯一能直接读到的真实色值是启动图那张
 * `drawable/splash_gradient`：`#256FD0 → #437ECB → #90ACD0`，angle=270，即
 * 「天顶饱和蓝 → 中段 → 地平线泛白」。这个**上深下浅、地平线提亮**的骨架被沿用，
 * 但每格的具体色值分两种来源，注释里区分：
 * - `[实测]`：从南风真机截图逐像素采下来的；
 * - 其余：照同一骨架自定，等更多天气的截图再校。
 */
data class SkyGradientStops(
    val zenith: Color,
    val mid: Color,
    val horizon: Color,
    /** 中段色标所在位置（占屏高分数）。[实测] 阴天拟合出来是 0.40。 */
    val midStop: Float = 0.40f,
) {
    fun lerpTo(other: SkyGradientStops, t: Float): SkyGradientStops = SkyGradientStops(
        zenith = lerp(zenith, other.zenith, t),
        mid = lerp(mid, other.mid, t),
        horizon = lerp(horizon, other.horizon, t),
        midStop = midStop + (other.midStop - midStop) * t,
    )

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
    )
}

/** 每个时段的「晴」与「转阴」两张色标；overcast 在两者之间插值。 */
private data class SkyRow(val clear: SkyGradientStops, val overcast: SkyGradientStops)

private val skyRows = mapOf(
    // 清晨：地平线泛暖，天顶还留着夜里的蓝。
    SkyPhase.MORNING to SkyRow(
        clear = SkyGradientStops(Color(0xFF3D5A9E), Color(0xFF7E8FC4), Color(0xFFF0B27A)),
        overcast = SkyGradientStops(Color(0xFF6F7A87), Color(0xFF818B97), Color(0xFFA6AAB0)),
    ),
    SkyPhase.DAY to SkyRow(
        // 晴：比阴天亮得多——更浅更通透的天蓝，与阴天灰蓝拉开明显差异（对齐南风）。
        clear = SkyGradientStops(Color(0xFF3A8BDF), Color(0xFF5FA6E8), Color(0xFFA8CDF0)),
        // [实测] 阴天：天顶 #3F515F → 0.40 处 #4A5B6D → 屏底 #6D8496（逐像素线性变亮）。
        overcast = SkyGradientStops(
            zenith = Color(0xFF3E5060),
            mid = Color(0xFF4A5B6D),
            horizon = Color(0xFF6D8496),
            midStop = 0.40f,
        ),
    ),
    // 傍晚：太阳低角度的黄金与蓝调时段。
    SkyPhase.EVENING to SkyRow(
        clear = SkyGradientStops(Color(0xFF3A3A78), Color(0xFF9A5D8C), Color(0xFFFF8A4C)),
        overcast = SkyGradientStops(Color(0xFF3A3648), Color(0xFF4C4759), Color(0xFF7A6A6C)),
    ),
    // 夜：天顶近黑，地平线留一点城市反光。
    SkyPhase.NIGHT to SkyRow(
        clear = SkyGradientStops(Color(0xFF070D24), Color(0xFF12204A), Color(0xFF2A3A62)),
        overcast = SkyGradientStops(Color(0xFF22323F), Color(0xFF283848), Color(0xFF445A6E), midStop = 0.35f),
    ),
)

fun skyStopsFor(phase: SkyPhase, overcast: Float): SkyGradientStops {
    val row = skyRows.getValue(phase)
    return row.clear.lerpTo(row.overcast, overcast.coerceIn(0f, 1f))
}

/** 背景切换时长。南风的 `AnimatedSkyGradient` 具体数值取不到，这里定 800ms。 */
const val SKY_TRANSITION_MILLIS = 800

private val SkyEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * 天空层。对应南风的 `WeatherBackground (WeatherBackground.kt:87)` 与
 * `AnimatedSkyGradient (WeatherBackground.kt:272)`。
 *
 * 切换必须动画化：时段跨边界或天气突变时三段色各自补间，不允许「整屏啪一下换色」。
 * `Brush` 不是可插值类型，所以补间它的三个组成色，每帧用当前色重组 `Brush`。
 */
@Composable
fun AnimatedSkyGradient(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
) {
    val target = skyStopsFor(state.phase, state.overcast)
    val spec = tween<Color>(durationMillis = SKY_TRANSITION_MILLIS, easing = SkyEasing)
    val zenith = animateColorAsState(target.zenith, spec, label = "skyZenith")
    val mid = animateColorAsState(target.mid, spec, label = "skyMid")
    val horizon = animateColorAsState(target.horizon, spec, label = "skyHorizon")

    Canvas(modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to zenith.value,
                target.midStop to mid.value,
                1f to horizon.value,
                startY = 0f,
                endY = size.height,
            ),
        )
    }
}

/**
 * 顶部渐进压暗，保证状态栏与城市名在任意天空下都可读。
 *
 * 南风的对应物是 `TopProgressiveBlur (WeatherBackground.kt:307)` —— 真·渐进模糊，
 * 要接 API 33+ 的 `RenderEffect`；这里先用压暗把可读性做对，不冒充模糊。
 */
@Composable
fun TopProgressiveScrim(
    modifier: Modifier = Modifier,
    bandFraction: Float = 0.22f,
) {
    Canvas(modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0x1A000000),
                bandFraction to Color(0x00000000),
                startY = 0f,
                endY = size.height,
            ),
        )
    }
}

/** 云场在天空画布上的活动带（分数，分辨率无关）。 */
data class CloudBand(val topFraction: Float, val bottomFraction: Float)

/** [实测] 阴天云带占据屏高 0.11–0.25；留出上方余量给漂移。 */
val DefaultCloudBand = CloudBand(0.05f, 0.42f)

/** 天体（太阳/月亮）在天空画布上的中心点。 */
fun sunPose(state: WeatherSceneState, widthPx: Float, heightPx: Float): Offset {
    val xFrac = when (state.phase) {
        SkyPhase.MORNING -> 0.22f
        SkyPhase.DAY, SkyPhase.NIGHT -> 0.5f
        SkyPhase.EVENING -> 0.78f
    }
    val yFrac = when (state.phase) {
        SkyPhase.MORNING -> 0.30f
        SkyPhase.DAY -> 0.12f
        SkyPhase.EVENING -> 0.34f
        SkyPhase.NIGHT -> 0.16f
    }
    return Offset(xFrac * widthPx, yFrac * heightPx)
}
