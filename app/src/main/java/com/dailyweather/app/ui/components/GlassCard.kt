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

/**
 * 高端 Liquid Glass 卡片 —— 多层折射 + 内发光 + 边缘高光。
 *
 * 结构修复：效果层（Canvas）使用 matchParentSize 覆盖整个卡片区域，
 * 不受 contentPadding 限制；只有内容 Column 被 padding 包裹。
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

    // ---- 颜色系统 ----
    val baseTint = if (accentColor != null) {
        val skyAlpha = sky.cardFill.alpha
        val accentAlpha = accentColor.alpha
        val mixedAlpha = skyAlpha * 0.7f + accentAlpha * 0.3f
        Color(
            red = (sky.cardFill.red * skyAlpha * 0.7f + accentColor.red * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            green = (sky.cardFill.green * skyAlpha * 0.7f + accentColor.green * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            blue = (sky.cardFill.blue * skyAlpha * 0.7f + accentColor.blue * accentAlpha * 0.3f) / mixedAlpha.coerceAtLeast(0.001f),
            alpha = mixedAlpha,
        )
    } else sky.cardFill
    val edgeHighlight = Color.White.copy(alpha = 0.02f)
    val borderGlow = if (accentColor != null) accentColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f)
    val innerShadow = Color.Black.copy(alpha = 0.05f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(baseTint, RoundedCornerShape(cornerRadius))
            .border(0.5.dp, borderGlow, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.TopStart,
    ) {
        // Layer 1: 折射高光 —— 左上角扩散柔光扇区
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
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
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.02f),
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent,
                    ),
                    start = Offset(w * 0.3f, h),
                    end = Offset(w * 0.8f, 0f),
                ),
            )
        }

        // Layer 2: 顶部极弱高光条
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            drawLine(
                color = edgeHighlight,
                start = Offset(0f, 0.5f),
                end = Offset(w, 0.5f),
                strokeWidth = 0.5f,
            )
        }

        // Layer 3: 底部内阴影（厚度感）
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val h = size.height
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
