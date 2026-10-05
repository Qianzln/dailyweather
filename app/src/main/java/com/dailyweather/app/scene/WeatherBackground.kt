package com.dailyweather.app.scene

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

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
    // 清晨：地平线泛暖（日出前的橙金），天顶还留着夜里的蓝。
    SkyPhase.MORNING to SkyRow(
        clear = SkyGradientStops(Color(0xFF2E4A8A), Color(0xFF7A9BC4), Color(0xFFF5C070)),
        overcast = SkyGradientStops(Color(0xFF6A7580), Color(0xFF7D8892), Color(0xFF9EA5AD)),
    ),
    SkyPhase.DAY to SkyRow(
        // 晴：天顶深蓝，地平线暖金——对齐南风晴天截图的"上冷下暖"对比。
        clear = SkyGradientStops(Color(0xFF1E5AA8), Color(0xFF5FA4E4), Color(0xFFD4A860)),
        // [实测] 阴天：深灰蓝，上深下浅，比晴天整体暗一档，和晴朗拉开明显差异。
        overcast = SkyGradientStops(
            zenith = Color(0xFF344550),
            mid = Color(0xFF425565),
            horizon = Color(0xFF657B8F),
            midStop = 0.40f,
        ),
    ),
    // 傍晚：太阳低角度，暖橙主导；阴天则偏冷紫灰。
    SkyPhase.EVENING to SkyRow(
        clear = SkyGradientStops(Color(0xFF2A2060), Color(0xFFB06070), Color(0xFFFF9040)),
        overcast = SkyGradientStops(Color(0xFF322E42), Color(0xFF484358), Color(0xFF6E5E60)),
    ),
    // 夜：天顶近墨黑，地平线留城市反光微暖。
    SkyPhase.NIGHT to SkyRow(
        clear = SkyGradientStops(Color(0xFF050C1C), Color(0xFF0E1A38), Color(0xFF1E2D50)),
        overcast = SkyGradientStops(Color(0xFF1A2530), Color(0xFF223040), Color(0xFF354858), midStop = 0.35f),
    ),
)

fun skyStopsFor(phase: SkyPhase, overcast: Float): SkyGradientStops {
    val row = skyRows.getValue(phase)
    return row.clear.lerpTo(row.overcast, overcast.coerceIn(0f, 1f))
}

/**
 * 天空层 —— 1:1 南风的精灵帧方案。
 *
 * 南风的 `AnimatedSkyGradient`：整幅天空（含云）是预渲染 PNG 帧序列，运行时逐帧轮播；
 * 渐变色只作为**帧解码前的兜底**与色调基座存在。本实现：
 * 1. 渐变底（`SkyGradientStops` 实测色，帧加载/解码期间可见）
 * 2. 天空精灵帧轮播（[SpriteSkyBackground]：8 帧积云 / 3 帧薄云 + Crossfade）
 * 3. 时段 × 天气 tint（清晨暖金 / 傍晚橙 / 夜深蓝；雨雪雾压灰）
 */
@Composable
fun AnimatedSkyGradient(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
) {
    SpriteSkyBackground(state = state, modifier = modifier, overcast = state.overcast)
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

/**
 * 滚动顶部渐进毛玻璃（南风 TopProgressiveBlur 的 1:1 复刻）。
 *
 * 与"再画一遍天空套 blur"的区别（实测南风截图）：玻璃带采样的是**整个内容层**
 * ——滚动时卡片/文字从状态栏区域穿过，它们也被真·模糊成雾状，而不仅是天空。
 * 实现用项目既有依赖 backdrop-android（南风同款库）：
 * - 内容层 `Modifier.layerBackdrop(backdrop)` 捕获绘制内容（含天空+滚动卡片）
 * - 顶部玻璃带 `Modifier.drawBackdrop(backdrop) { blur(22f) }` 采样并模糊
 * - 强度随 scrollY 渐入（南风：刚滚动时带子淡、滚深了变实）
 */
@Composable
fun TopProgressiveGlass(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    bandHeight: androidx.compose.ui.unit.Dp = 128.dp,
) {
    val strength = (scrollState.value / 260f).coerceIn(0f, 1f)
    if (strength <= 0.02f) return
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(bandHeight)
                .graphicsLayer { this.alpha = strength }
                .drawBackdrop(
                    backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = { blur(22f) },
                ),
        )
    }
}
