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
    /** 天空渐变中间色 —— 南风玻璃的 `backgroundColor`（`weatherTheme.a()[size/2]`）。 */
    val glassBackdrop: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val cardFill: Color,
    val cardStroke: Color,
    val accent: Color,
    /** 毛玻璃 tint（南风 `GlassColors.getTint()`：昼 #A5C9E6@0.32，夜为深蓝推断值）。 */
    val glassTint: Color,
    /** 毛玻璃低版本回退 tint（南风 `getTintFallback()`）。 */
    val glassFallbackTint: Color,
) {
    companion object {
        fun of(state: WeatherSceneState): SkyPalette {
            val stops = skyStopsFor(state.phase, state.overcast)
            // 卡片随天空变亮变暗（南风同款行为）：取天空中部色，压一点亮度后作为卡面。
            // 不再用写死的深蓝灰——那是「晴天卡片也偏深」的根因。
            val mid = stops.mid
            val cardFill = Color(
                red = (mid.red * 0.90f).coerceIn(0f, 1f),
                green = (mid.green * 0.90f).coerceIn(0f, 1f),
                blue = (mid.blue * 0.95f).coerceIn(0f, 1f),
                alpha = 0.35f,
            )
            // 玻璃 tint 取色。
            //
            // 南风真身取自 `x7.WeatherTheme.getGlass()`，该类的色值表**不在参考包内**
            // （见重构计划 §5.2），所以这里一直是推断值。此前用「昼 #A5C9E6 / 夜 #1B2C52」
            // 两档硬编码，实测暴露一个问题：**清晨/黄昏的天空已经转深紫，却仍被判成昼**，
            // 于是玻璃叠的是浅天蓝 → 卡片比天空亮一截、与天空割裂（装机截图已见）。
            //
            // 南风的 `WeatherTheme` 其实是按**当前天空色**动态算的（这正是"玻璃随天空呼吸"的来源），
            // 所以改为跟随天空中部色推导：色相始终与天空一致，alpha 沿用南风的 0.32 / 0.45 量级。
            val isDay = state.phase != SkyPhase.NIGHT
            val glassTint = mid.copy(alpha = if (isDay) 0.32f else 0.38f)
            val glassFallback = mid.copy(alpha = if (isDay) 0.45f else 0.50f)
            return SkyPalette(
                isDay = isDay,
                skyTop = stops.zenith,
                skyBottom = stops.horizon,
                glassBackdrop = mid,
                textPrimary = Tokens.TextPrimary,
                textSecondary = Tokens.TextSecondary,
                cardFill = cardFill,
                cardStroke = Tokens.CardStroke,
                accent = Tokens.BarEnd,
                glassTint = glassTint,
                glassFallbackTint = glassFallback,
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
