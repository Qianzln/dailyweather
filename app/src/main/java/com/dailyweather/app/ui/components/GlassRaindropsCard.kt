package com.dailyweather.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
        //
        // ⚠️ 不要用 `!!`：AGSL 在运行时编译，源码一旦有问题（如漏声明 uniform）就会抛
        // IllegalArgumentException，而 `newShader` 会吞掉异常返回 null 以降级。
        // 这里再 `!!` 就变成 NPE 崩溃 —— 雨天进首页必闪退。
        val highlight = remember { RainRefractionShader.newShader(RainRefractionShader.HIGHLIGHT) }
        val sdf = remember { RainRefractionShader.newShader(RainRefractionShader.SDF) }
        if (highlight == null || sdf == null) {
            // 编译失败 / 系统不支持：静默降级为普通玻璃卡（保留文字内容，只是没有雨滴）。
            // 注意这里**不**显示"需要 Android 13+"——那是 API 门槛提示，编译失败不该怪用户。
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.Center,
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
                        fontWeight = FontWeight.Medium,
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
            return@GlassCard
        }
        val highlightBrush = remember(highlight) { ShaderBrush(highlight) }
        val clock = rememberEffectClock(30)
        // SDF 边缘折射：作用于整卡内容（水珠层 + 文字一起被玻璃厚度折射）。
        val sdfEffect = remember(sdf) { RainRefractionShader.composeEffect(sdf, "content") }
        val density = LocalDensity.current
        val cornerPx = with(density) { Tokens.CardRadius.toPx() }
        val refractHeightPx = with(density) { SdfRefractionHeight.toPx() }
        val refractAmountPx = with(density) { SdfRefractionAmount.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ uniforms 用 `onSizeChanged` 驱动，**不要**在 graphicsLayer 的 block 里读 `size`。
                //
                // 为什么：这张卡由 `AnimatedVisibility + expandVertically` 展开，首帧高度是 **0**；
                // 而 graphicsLayer 的 block 只在「布局尺寸」这个上游事件上重跑。实测那一帧
                // `size=0x0` 被写进 uSize，此后不再刷新 → 旧 SDF 里 `gradRadius = min(r*1.5, 0) = 0`
                // → `normalize(0)` = NaN → `content.eval(NaN)` → **整卡（水珠层 + 文字）全透明**，
                // 就是装机截图里那块 176dp 的空白。
                // `onSizeChanged` 在尺寸真正确定时（展开动画的每一帧）都会回调，拿到的必是真实尺寸。
                .onSizeChanged { sz ->
                    if (sz.width > 1 && sz.height > 1) {
                        RainRefractionShader.applySdfUniforms(
                            shader = sdf,
                            width = sz.width.toFloat(),
                            height = sz.height.toFloat(),
                            cornerRadiusPx = cornerPx,
                            refractionHeightPx = refractHeightPx,
                            refractionAmountPx = refractAmountPx,
                        )
                    }
                }
                .graphicsLayer { renderEffect = sdfEffect },
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
                    // 卡片上的水珠要比全屏折射小一号：全屏标定的网格搬到 176dp 卡上
                    // 珠子偏大偏糊（实机截图像灰白斑点），1.6 倍缩放后才是"玻璃雨滴"。
                    dropSizeMul = 1.6f,
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

/** SDF 折射偏移幅度：边缘处内容最大偏移像素（玻璃"鼓起"的折射量）。
 *  12dp 时卡片左下角的标题被横向拉伸得偏重（实测截图），降到 9dp 后仍有玻璃厚度感且文字可读。 */
private val SdfRefractionAmount = 9.dp
