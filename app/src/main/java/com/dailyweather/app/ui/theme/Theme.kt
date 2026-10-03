package com.dailyweather.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dailyweather.app.scene.SkyPhase
import com.dailyweather.app.scene.WeatherKind
import com.dailyweather.app.scene.WeatherSceneState
import com.dailyweather.app.scene.skyStopsFor

/**
 * 首页配色：一律由场景状态派生，不再有独立的「天气色表」。
 *
 * 天空由 `AnimatedSkyGradient` 画，卡片只是天空上叠 [Tokens.CardFill]，
 * 所以卡片会随天空自然变亮变暗——这正是南风截图里 observed 的行为
 * （同一种卡，在页面上越靠下越亮）。
 */
data class SkyPalette(
    val isDay: Boolean,
    val skyTop: Color,
    val skyBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardFill: Color,
    val cardStroke: Color,
    val accent: Color,
) {
    companion object {
        fun of(state: WeatherSceneState): SkyPalette {
            val stops = skyStopsFor(state.phase, state.overcast)
            // 卡片随天空变亮变暗（南风同款行为）：取天空中部色，压一点亮度后作为卡面。
            // 不再用写死的深蓝灰——那是「晴天卡片也偏深」的根因。alpha 保留 0.78，
            // 让天空能透 22%，又不至于白字失读。
            val mid = stops.mid
            val cardFill = Color(
                red = (mid.red * 0.90f).coerceIn(0f, 1f),
                green = (mid.green * 0.90f).coerceIn(0f, 1f),
                blue = (mid.blue * 0.95f).coerceIn(0f, 1f),
                alpha = 0.78f,
            )
            return SkyPalette(
                isDay = state.phase != SkyPhase.NIGHT,
                skyTop = stops.zenith,
                skyBottom = stops.horizon,
                textPrimary = Tokens.TextPrimary,
                textSecondary = Tokens.TextSecondary,
                cardFill = cardFill,
                cardStroke = Tokens.CardStroke,
                accent = Tokens.BarEnd,
            )
        }
    }
}

private val DefaultScene = WeatherSceneState(WeatherKind.CLOUDY, SkyPhase.DAY)

val LocalSky = staticCompositionLocalOf { SkyPalette.of(DefaultScene) }

@Composable
fun DailyWeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        typography = Typography(
            titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
        ),
        content = content,
    )
}
