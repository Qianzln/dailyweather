package com.dailyweather.app.scene

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dailyweather.app.R

/**
 * 天气特效组件栈 —— 按南风「天气 × 特效组件」组织界面（逆向确认的组件表：
 * `SunnyDayEffect / ClearNightEffect / PartlyCloudyNightEffect / CloudyEffect / FogEffect /
 * RainEffect / ThunderShowerEffect / SnowEffect / SleetEffect / WindEffect` +
 * `WeatherForegroundClouds / WeatherEffectCanvas / WeatherEffectContent / WeatherEffectOverlay`）。
 *
 * 分层：
 * - [WeatherEffectHost]：统一入口（= 南风 WeatherEffectOverlay 的装配面）；
 * - ① [ForegroundCloudsLayer]：前景云精灵（= 南风 WeatherForegroundClouds +
 *   `rememberCloudSpriteSet` / `rememberForegroundCloudMotion`，数据表见 [spriteCloudsFor]）；
 * - ② 每天气一个特效组件（= 南风 each Effect）：雨/雪/雷/雾/风/晴夜/晴日…
 *   每个组件自治（自带粒子时钟），互不依赖，可独立调参。
 */
@Composable
fun WeatherEffectHost(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
) {
    val drawEnabled = LocalWeatherEffectDrawEnabled.current
    val quality = LocalEffectQuality.current
    if (!drawEnabled || quality.particleScale <= 0f) return

    val context = LocalContext.current
    val specs = remember(state.kind, state.wind) { spriteCloudsFor(state) }
    val cloudBitmaps = SpriteAssets.CloudSprite.entries.map { sprite ->
        sprite to rememberSpriteBitmap(sprite.asset, opaque = false)
    }.toMap()
    val streak = remember { SpriteAssets.loadResource(context, R.drawable.weather_rain_streak_mgl) }

    // ① 前景云层：PNG 精灵漂移（1:1 南风 weather_cloud_* 方案）。
    val cloudClock = rememberEffectClock(quality.fps.cloud)
    Canvas(modifier) {
        for (spec in specs) {
            val sprite = if (state.isNight) spec.night else spec.day
            drawSpriteCloudLayer(spec, state, cloudBitmaps[sprite], cloudClock.floatValue, quality)
        }
    }

    // ② 天气特效：按状况分发到每天气组件（南风 Effect 组件表）。
    when {
        state.kind == WeatherKind.CLEAR && state.isNight ->
            ClearNightEffect(state, quality, modifier)
        state.kind == WeatherKind.CLEAR ->
            SunnyDayEffect(state, quality, modifier)
        state.kind == WeatherKind.PARTLY_CLOUDY ->
            PartlyCloudyEffect(state, quality, modifier)
        state.kind == WeatherKind.CLOUDY ->
            CloudyEffect(state, quality, modifier)
        state.kind == WeatherKind.FOG ->
            FogEffect(state, cloudBitmaps[SpriteAssets.CloudSprite.WISP], quality, modifier)
        state.kind == WeatherKind.RAIN ->
            RainEffect(state, quality, modifier, streak)
        state.kind == WeatherKind.THUNDER ->
            ThunderEffect(state, quality, modifier, streak)
        state.kind == WeatherKind.SNOW ->
            SnowEffect(state, quality, modifier)
        state.kind == WeatherKind.SLEET ->
            SleetEffect(state, quality, modifier)
        state.kind == WeatherKind.WIND ->
            WindEffect(state, quality, modifier)
    }
}

/** 晴·白：太阳沿日出→日落弧线走（SunPose），光晕辉光打底。位置由 sunProgress 驱动，无需动画时钟。 */
@Composable
private fun SunnyDayEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    Canvas(modifier) {
        drawSun(state, quality)
    }
}

/** 晴·夜：星星 + 流星（月晖由天空 tint 表达）。 */
@Composable
private fun ClearNightEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    if (quality.fps.ambient <= 0) return
    val clock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))
    Canvas(modifier) {
        drawStars(clock.floatValue, quality)
        drawShootingStars(clock.floatValue, quality)
    }
}

/** 多云（昼/夜通用）：白天带太阳，夜间带星。 */
@Composable
private fun PartlyCloudyEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    if (quality.fps.ambient <= 0) return
    val clock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))
    Canvas(modifier) {
        if (state.isNight) {
            drawStars(clock.floatValue, quality)
        } else {
            drawSun(state, quality)
        }
    }
}

/** 阴：无粒子，只靠天空帧与云层表达（南风 CloudyEffect 为空壳）。 */
@Composable
private fun CloudyEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    // 阴天无特效粒子；保留空实现以便与南风组件一一对应。
}

/** 雾：低空霾带横流。 */
@Composable
private fun FogEffect(
    state: WeatherSceneState,
    wisp: android.graphics.Bitmap?,
    quality: EffectQuality,
    modifier: Modifier,
) {
    if (quality.fps.ambient <= 0) return
    val clock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))
    Canvas(modifier) {
        drawFogBands(wisp, clock.floatValue, quality)
    }
}

/** 雨：雨丝贴图 + 远景雨滴流星。 */
@Composable
private fun RainEffect(
    state: WeatherSceneState,
    quality: EffectQuality,
    modifier: Modifier,
    streak: android.graphics.Bitmap?,
) {
    val clock = rememberEffectClock(quality.fps.precip)
    Canvas(modifier) {
        val intensity = if (state.intensity > 0f) state.intensity else state.kind.defaultIntensity()
        drawPrecipitation(PrecipKind.RAIN, intensity, state.wind, state.isNight, clock.floatValue, quality, streak)
    }
}

/** 雷阵雨：雨 + 雷暴闪光折线。 */
@Composable
private fun ThunderEffect(
    state: WeatherSceneState,
    quality: EffectQuality,
    modifier: Modifier,
    streak: android.graphics.Bitmap?,
) {
    val precipClock = rememberEffectClock(quality.fps.precip)
    val ambientClock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))
    Canvas(modifier) {
        val intensity = if (state.intensity > 0f) state.intensity else state.kind.defaultIntensity()
        drawPrecipitation(PrecipKind.RAIN, intensity, state.wind, state.isNight, precipClock.floatValue, quality, streak)
        drawThunder(ambientClock.floatValue, state, quality)
    }
}

/** 雪：24 帧雪花位图轮播。 */
@Composable
private fun SnowEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    val clock = rememberEffectClock(quality.fps.precip)
    Canvas(modifier) {
        val intensity = if (state.intensity > 0f) state.intensity else state.kind.defaultIntensity()
        drawPrecipitation(PrecipKind.SNOW, intensity, state.wind, state.isNight, clock.floatValue, quality, null)
    }
}

/** 雨夹雪：雨丝 + 雪花混合。 */
@Composable
private fun SleetEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    val clock = rememberEffectClock(quality.fps.precip)
    Canvas(modifier) {
        val intensity = if (state.intensity > 0f) state.intensity else state.kind.defaultIntensity()
        drawPrecipitation(PrecipKind.SLEET, intensity, state.wind, state.isNight, clock.floatValue, quality, null)
    }
}

/** 风：速度线横掠。 */
@Composable
private fun WindEffect(state: WeatherSceneState, quality: EffectQuality, modifier: Modifier) {
    if (quality.fps.ambient <= 0) return
    val clock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))
    Canvas(modifier) {
        drawWindLines(state.wind, clock.floatValue, quality)
    }
}
