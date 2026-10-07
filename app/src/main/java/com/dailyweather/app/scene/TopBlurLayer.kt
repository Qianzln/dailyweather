package com.dailyweather.app.scene

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 顶部可读性压暗带。
 *
 * 唯一职责：内容滚动到状态栏区域时，保证城市名/状态栏在任意天空色上仍然可读。
 *
 * 为什么不做「真模糊」：`Modifier.graphicsLayer { renderEffect = ... }` 只能模糊**自身
 * 图层**，无法采样背后的兄弟节点（天空 + 滚动卡片）。要「采样内容再模糊」必须用
 * backdrop-android 那类跨图层采样库，而它正是此前顶部方框/黑带与硬边的来源。
 * 这里改为纯渐变压暗：
 * - tint 由调用方传入「当前天空天顶色再压暗」，与天空同色系，不会出现灰黑色跳变；
 * - 底部 1/3 线性淡到全透明，四条边里左右通栏、下方软收，不存在任何硬边；
 * - 强度随滚动渐入：静止时几乎不可见，滚动越深越压暗。
 *
 * 用 `drawWithCache` 缓存渐变 brush，滚动过程中只在 alpha 变化时重建一次。
 *
 * @param scrollState 滚动状态，用于计算压暗强度
 * @param tint 压暗色（应与当前天空同色系）
 * @param bandHeight 压暗带高度，默认 108dp（覆盖状态栏 + 顶栏）
 * @param maxAlpha 滚动到底时的最大不透明度，默认 0.38
 */
@Composable
fun TopBlurLayer(
    scrollState: ScrollState,
    tint: Color,
    modifier: Modifier = Modifier,
    bandHeight: Dp = 108.dp,
    maxAlpha: Float = 0.38f,
) {
    val progress = (scrollState.value / 240f).coerceIn(0f, 1f)
    val alpha = progress * maxAlpha
    // 强度过小时不渲染，避免无效绘制。
    if (alpha <= 0.004f) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight)
            .drawWithCache {
                val brush = Brush.verticalGradient(
                    0f to tint.copy(alpha = alpha),
                    0.38f to tint.copy(alpha = alpha * 0.72f),
                    0.72f to tint.copy(alpha = alpha * 0.26f),
                    1f to Color.Transparent,
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}
