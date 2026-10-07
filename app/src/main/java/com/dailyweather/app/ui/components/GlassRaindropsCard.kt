package com.dailyweather.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.scene.RainRefractionShader
import com.dailyweather.app.scene.rememberEffectClock
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.Tokens

/**
 * 玻璃雨滴卡（v0.1.30 · P1）—— 南风 GlassRainDropsCard 的复刻。
 *
 * 雨/雷/雨夹雪天气时出现在卡片流顶部：雨滴打在玻璃上的沉浸卡。
 * - **水珠高光层**：MIUI rain_drop_new 的静止水珠 + 滑落水滴（AGSL，与全屏折射
 *   共用同一函数库 [RainRefractionShader.COMMON]），输出「亮核 + 暗缘」的水珠
 *   体积光叠在玻璃底色上；
 * - **SDF 边缘折射**（文档 3.3 LiquidGlassSurface）：整卡内容经 SDF 圆角折射——
 *   距边缘 [SdfRefractionHeight] 内的内容沿圆角法线偏折，卡片有了"玻璃厚度"；
 * - 显示/隐藏动画（1000ms 入 / 600ms 出）由外层 AnimatedVisibility 驱动；
 * - API < 33（RuntimeShader 不可用）自动退化为纯玻璃卡 + 说明行。
 */
@Composable
fun GlassRaindropsCard(
    intensity: Float,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val sky = LocalSky.current
    val api33 = remember { RainRefractionShader.supported() }
    GlassCard(
        modifier = modifier.fillMaxWidth().height(176.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        if (!api33) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "玻璃雨滴动效需要 Android 13+",
                    color = sky.textSecondary,
                    fontSize = 13.sp,
                )
            }
            return@GlassCard
        }

        // 水珠高光层：30fps 独立时钟（水珠是慢变量，30fps 足够且省电）。
        val highlight = remember { RainRefractionShader.newShader(RainRefractionShader.HIGHLIGHT)!! }
        val highlightBrush = remember(highlight) { ShaderBrush(highlight) }
        val clock = rememberEffectClock(30)
        // SDF 边缘折射：作用于整卡内容（水珠层 + 文字一起被玻璃厚度折射）。
        val sdf = remember { RainRefractionShader.newShader(RainRefractionShader.SDF)!! }
        val sdfEffect = remember(sdf) { RainRefractionShader.composeEffect(sdf, "content") }
        val density = LocalDensity.current
        val cornerPx = with(density) { Tokens.CardRadius.toPx() }
        val refractHeightPx = with(density) { SdfRefractionHeight.toPx() }
        val refractAmountPx = with(density) { SdfRefractionAmount.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    RainRefractionShader.applySdfUniforms(
                        shader = sdf,
                        width = size.width,
                        height = size.height,
                        cornerRadiusPx = cornerPx,
                        refractionHeightPx = refractHeightPx,
                        refractionAmountPx = refractAmountPx,
                    )
                    renderEffect = sdfEffect
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                RainRefractionShader.applyHighlightUniforms(
                    shader = highlight,
                    width = size.width,
                    height = size.height,
                    timeSec = clock.floatValue,
                    intensity = intensity,
                    night = night,
                    visibility = 1f,
                )
                drawRect(brush = highlightBrush)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rememberLucide("cloud-rain")?.let {
                        Icon(it, contentDescription = null, tint = sky.textPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "雨滴打在玻璃上",
                        color = sky.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        intensity >= 0.75f -> "暴雨倾盆，出门记得带伞"
                        intensity >= 0.45f -> "雨打窗棂，适合窝在家里"
                        else -> "细雨拂窗，空气很清爽"
                    },
                    color = sky.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** SDF 折射带厚度：距边缘此距离内参与折射（玻璃厚度）。 */
private val SdfRefractionHeight = 20.dp

/** SDF 折射偏移幅度：边缘处内容最大偏移像素（玻璃"鼓起"的折射量）。 */
private val SdfRefractionAmount = 12.dp
