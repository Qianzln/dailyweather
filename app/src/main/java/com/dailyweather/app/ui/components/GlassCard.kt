package com.dailyweather.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.Tokens

/**
 * 玻璃卡。
 *
 * [实测] 南风的卡面就是「天空 + 20% 纯黑」，圆角 22dp，上沿一条细高光，
 * 没有高斯模糊（20% 的叠色下模糊根本看不出来）。所以这里刻意不做模糊。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = Tokens.CardRadius,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val sky = LocalSky.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(sky.cardFill)
            .border(0.5.dp, sky.cardStroke, RoundedCornerShape(cornerRadius))
            .padding(contentPadding),
    ) { content() }
}

/** 卡片标题（次级文字色，小号）。 */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalSky.current.textSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier,
    )
}
