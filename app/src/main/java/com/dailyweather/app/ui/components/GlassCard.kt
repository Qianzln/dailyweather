package com.dailyweather.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.ui.theme.WeatherGlass
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 玻璃卡片 —— 南风 `GlassCard` / `PairedGlassHaze` 的等价物。
 *
 * 三层构成（对齐南风）：
 * 1. **Haze 毛玻璃**：`hazeEffect` 采样天空层，**22dp + noiseFactor 0.035** +
 *    `tints=[glassTint]` + `fallbackTint` + `backgroundColor=主题中间色`
 *    （南风 `C1046n6.java:414-422` 的完整配置）。采样源由 [LocalCardHaze] 下发，
 *    为 null 时（无采样源的画面）回退纯色卡面。
 * 2. **卡面填色**：随天空变亮变暗的主题色；有 Haze 时降到 0.62 让模糊透上来。
 * 3. **高光 / 内阴影 / 描边**：自研 Canvas 层（南风这层由 `LiquidGlassSurface` 负责，
 *    形态相近，保留）。
 *
 * 效果层（Canvas）用 `matchParentSize` 覆盖整个卡片区域，不受 contentPadding 限制；
 * 只有内容 Column 被 padding 包裹。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Tokens.CardRadius,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    accentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sky = LocalSky.current
    val hazeState = LocalCardHaze.current

    // ---- 颜色系统 ----
    // 有 Haze 时压低调色 alpha，让 22dp 模糊真的透上来（否则等于没接）。
    val fillAlpha = if (hazeState != null) {
        WeatherGlass.CARD_HAZE_FILL_ALPHA
    } else {
        WeatherGlass.CARD_SOLID_FILL_ALPHA
    }
    val flatFill = sky.cardFill.copy(alpha = fillAlpha)
    val baseTint = if (accentColor != null) {
        val skyAlpha = flatFill.alpha
        val accentAlpha = accentColor.alpha
        val mixedAlpha = skyAlpha * 0.7f + accentAlpha * 0.3f
        Color(
            red = (flatFill.red * skyAlpha * 0.7f + accentColor.red * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            green = (flatFill.green * skyAlpha * 0.7f + accentColor.green * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            blue = (flatFill.blue * skyAlpha * 0.7f + accentColor.blue * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            alpha = mixedAlpha,
        )
    } else flatFill
    // v0.1.33：加强卡缘——原 0.02/0.06 几乎不可见，圆角显不出来，卡片像贴在一起的正色块。
    val edgeHighlight = Color.White.copy(alpha = 0.10f)
    val borderGlow = if (accentColor != null) accentColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)
    val innerShadow = Color.Black.copy(alpha = 0.05f)

    Box(
        modifier = modifier
            // ⚠️ `.clip()` 必须在 `.hazeEffect()` **之前**（用户反馈「卡片是圆角但边缘能看到正方形直角」的根因）：
            //   `Modifier.hazeEffect` 自己会绘制那层模糊底（`drawContentBehind`），
            //   修饰符链的绘制顺序是「先写的先画、且在外层」——把 hazeEffect 写在前面时，
            //   它画出的模糊底落在 `clip` 图层**之外**，于是不受圆角裁剪 → 四角露出方形直角
            //   （haze 1.6.1 默认 `blurredEdgeTreatment = BlurredEdgeTreatment.Rectangle`，
            //    即模糊底被裁成**矩形**，不是圆角）。
            //   把 `clip` 提到最外层后，haze 的绘制被圆角图层裁掉，方角消失。
            //   （Compose 的 `BlurredEdgeTreatment` 构造器是 internal，只能取 Rectangle/Unbounded，
            //    无法直接传 RoundedCornerShape，所以只能靠顺序解决。）
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(hazeState) {
                        blurRadius = WeatherGlass.CardBlurRadius
                        noiseFactor = WeatherGlass.CARD_NOISE_FACTOR
                        tints = listOf(HazeTint(sky.glassTint))
                        fallbackTint = HazeTint(sky.glassFallbackTint)
                        backgroundColor = sky.glassBackdrop
                    }
                } else {
                    Modifier
                },
            )
            .background(baseTint, RoundedCornerShape(cornerRadius))
            .border(0.5.dp, borderGlow, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.TopStart,
    ) {
        // Layer 1: 折射高光 —— 左上角扩散柔光
        // Layer 2: 顶部极弱高光条
        // Layer 3: 底部内阴影（厚度感）
        // 合并为单个 Canvas，减少 draw call
        //
        // ⚠️ 原 Layer 1 还有第二段「从 (w*0.3, h) 到 (w*0.8, 0) 的斜向白光」，
        // 圆角裁剪后表现为**卡片底部一条浅色大圆弧**（装机截图上很明显）。
        // 那一段是自研添加层（南风对应实现是 `LiquidGlassSurface`，形态里没有这条底部反光），
        // 卡面变透后它更显眼 → 已移除。
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Layer 1: 折射高光（左上角柔光扇区）
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent,
                        Color.Transparent,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.7f, h * 0.6f),
                ),
            )

            // Layer 2: 顶部极弱高光条
            drawLine(
                color = edgeHighlight,
                start = Offset(0f, 0.5f),
                end = Offset(w, 0.5f),
                strokeWidth = 0.5f,
            )

            // Layer 3: 底部内阴影
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        innerShadow,
                    ),
                    start = Offset(0f, h * 0.6f),
                    end = Offset(0f, h),
                ),
            )
        }

        // Layer 4: 内容层（仅此层受 padding 约束）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            content = content,
        )
    }
}
