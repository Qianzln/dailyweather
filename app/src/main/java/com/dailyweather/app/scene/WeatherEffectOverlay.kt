package com.dailyweather.app.scene

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import com.dailyweather.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * 天气前景效果层 —— 对应南风的 `WeatherEffectOverlay.kt`。
 *
 * ## 为什么是三个 Canvas 而不是一个
 *
 * 南风的 `WeatherEffectFps(precip=…)` 说明它的云与降水**不在同一个帧率上**。
 * 若画在同一个 Canvas 里，只要有一层想跑 60fps，整个 Canvas 就得 60fps 重绘。
 * 拆成三层后每层读自己的时钟，各自失效、各自限帧，"分图层帧率"才真的成立。
 *
 * ## 与南风的代码量分配对照
 *
 * 它是 晴 ~88 行 / 多云 ~90 行 / 雪 ~83 行，而 **阴 ~275 行、雨 ~326 行**。
 * 本仓把"晴 / 多云 / 阴"的差异收进 [cloudFieldsFor] 的云场表（是数据不是代码），
 * 于是代码量分布变成：**雨最重，氛围层次之，晴最轻** —— 观感取向一致，
 * 但新增"多云的第 N 层"不必再写一个 Effect 函数。
 */
@Composable
fun WeatherEffectOverlay(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
    band: CloudBand = DefaultCloudBand,
) {
    val drawEnabled = LocalWeatherEffectDrawEnabled.current
    val quality = LocalEffectQuality.current
    if (!drawEnabled || quality.particleScale <= 0f) return

    val context = LocalContext.current
    val fields = remember(state.kind, state.wind) { cloudFieldsFor(state) }
    val rig = state.rig

    // 物种是固定的四个，无条件取；烘焙结果按 CloudBakeKey 缓存，未用到的物种不会进绘制路径。
    val sprites: Map<CloudSpecies, CloudSpriteSet> = mapOf(
        CloudSpecies.CUMULUS to rememberCloudSpriteSet(context, CloudSpecies.CUMULUS, rig),
        CloudSpecies.STRATUS to rememberCloudSpriteSet(context, CloudSpecies.STRATUS, rig),
        CloudSpecies.PHOTO_CUMULUS to rememberCloudSpriteSet(context, CloudSpecies.PHOTO_CUMULUS, rig),
        CloudSpecies.PHOTO_WISP to rememberCloudSpriteSet(context, CloudSpecies.PHOTO_WISP, rig),
        CloudSpecies.PHOTO_BANK to rememberCloudSpriteSet(context, CloudSpecies.PHOTO_BANK, rig),
    )
    val streak = remember { CloudSpriteCache.raw(context, R.drawable.weather_rain_streak_mgl) }

    val cloudClock = rememberEffectClock(quality.fps.cloud)
    val precipClock = rememberEffectClock(quality.fps.precip)
    val ambientClock = rememberEffectClock(quality.fps.ambient)

    // ① 云层
    Canvas(modifier) {
        drawCloudFields(fields, sprites, cloudClock.floatValue, band, quality, state.photoTint)
    }

    // ② 降水层
    val precip = state.kind.precipitation()
    if (precip != PrecipKind.NONE && quality.fps.precip > 0) {
        val intensity = if (state.intensity > 0f) state.intensity else state.kind.defaultIntensity()
        Canvas(modifier) {
            drawPrecipitation(precip, intensity, state.wind, state.isNight, precipClock.floatValue, quality, streak)
        }
    }

    // ③ 氛围层：晴夜的星、雾天的霾带、雷暴的闪光
    if (quality.fps.ambient > 0) {
        Canvas(modifier) {
            drawAmbience(state, sprites, ambientClock.floatValue, quality)
        }
    }
}

private fun DrawScope.drawCloudFields(
    fields: List<CloudFieldSpec>,
    sprites: Map<CloudSpecies, CloudSpriteSet>,
    t: Float,
    band: CloudBand,
    quality: EffectQuality,
    tint: Color,
) {
    for (field in fields) {
        if (!quality.drawPhotoForeground && field.species.isPhoto) continue
        val set = sprites[field.species] ?: continue
        if (!set.ready) continue

        val count = scaledCount(field.count, quality)
        for (i in 0 until count) {
            val img = set.at(i + field.seed)
            val jitter = hash01(field.seed * 31 + i)
            // 漂移：分数坐标循环，跨屏宽行为一致。1.3 的系数让云在屏外也有分布，
            // 不至于出现"每隔一段时间整屏空一次"的呼吸感。
            val drift = (t * field.driftSpeed + jitter * 1.3f) % 1.3f
            val xFraction = drift - 0.15f
            val yFraction = band.topFraction + (band.bottomFraction - band.topFraction) *
                lerpF(field.yFractionRange.start, field.yFractionRange.endInclusive, jitter)

            val w = field.widthFraction * size.width
            val h = w * (img.height.toFloat() / img.width.toFloat())
            drawImage(
                image = img,
                dstOffset = Offset((xFraction * size.width - w / 2f), yFraction * size.height - h / 2f)
                    .let { androidx.compose.ui.unit.IntOffset(it.x.toInt(), it.y.toInt()) },
                dstSize = IntSize(w.toInt(), h.toInt()),
                alpha = field.alpha * edgeFade(xFraction),
                colorFilter = if (field.species.isMask) null else ColorFilter.tint(tint, BlendMode.Modulate),
            )
        }
    }
}

internal enum class PrecipKind { NONE, RAIN, SNOW }

private fun WeatherKind.precipitation(): PrecipKind = when (this) {
    WeatherKind.RAIN, WeatherKind.THUNDER -> PrecipKind.RAIN
    WeatherKind.SNOW -> PrecipKind.SNOW
    else -> PrecipKind.NONE
}

private fun WeatherKind.defaultIntensity(): Float = when (this) {
    WeatherKind.RAIN, WeatherKind.THUNDER -> 0.55f
    WeatherKind.SNOW -> 0.40f
    else -> 0f
}

/**
 * 雨丝用**素材**而不是画线。
 *
 * 南风的 `weather_rain_streak_mgl` 实测只有 20×68、`8-bit gray+alpha`，
 * 是一条**边缘不规则**的竖直雨丝。每滴雨旋转后贴这张图，得到的"不均匀"
 * 是 `drawLine` 画不出来的 —— 直线雨永远像代码画的。
 *
 * 灰度素材本身没有颜色，绘制时用 `BlendMode.SrcIn` 的 tint 换色：
 * 保留素材 alpha，只替换 RGB。夜里换成冷蓝，不用第二套素材。
 */
private fun DrawScope.drawPrecipitation(
    kind: PrecipKind,
    intensity: Float,
    wind: Float,
    night: Boolean,
    t: Float,
    quality: EffectQuality,
    streak: ImageBitmap?,
) {
    val base = if (kind == PrecipKind.RAIN) 90 else 70
    val count = (base * intensity.coerceIn(0.1f, 1f) * quality.particleScale).toInt().coerceAtLeast(8)
    val tiltDeg = (wind * 26f - 4f).coerceIn(-12f, 34f)
    val tanTilt = tan((tiltDeg * PI / 180.0).toFloat())
    val aspect = size.height / size.width
    val tint = if (night) Color(0xFF9FB6D8) else Color(0xFFE8F2FF)

    for (i in 0 until count) {
        val r1 = hash01(i * 7 + 1)
        val r2 = hash01(i * 13 + 5)
        val r3 = hash01(i * 19 + 9)

        val fall = (t * (if (kind == PrecipKind.RAIN) 1.15f + r3 * 0.85f else 0.16f + r3 * 0.12f) + r2) % 1f
        val x = if (kind == PrecipKind.SNOW) {
            // 雪花的横向摆动 —— 对应素材参数里的 wobbleAmplitude / wobbleSpeed。
            (r1 + 0.05f * sin(t * 1.6f + r3 * 6.28f)).mod1()
        } else {
            // 雨丝的横向位移 = 下落距离 × 倾角正切，与旋转角严格一致，
            // 否则会出现"线是斜的、走是直的"这种一眼假的错位。
            (r1 + fall * tanTilt / aspect).mod1()
        }

        val len = (if (kind == PrecipKind.RAIN) 0.022f + r3 * 0.020f else 0.012f + r3 * 0.010f) * size.height

        if (streak != null && kind == PrecipKind.RAIN) {
            val wPx = len * 0.22f
            val cx = x * size.width
            val cy = fall * size.height
            rotate(degrees = tiltDeg, pivot = Offset(cx, cy)) {
                drawImage(
                    image = streak,
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - wPx / 2f).toInt(), (cy - len / 2f).toInt()),
                    dstSize = IntSize(wPx.toInt(), len.toInt()),
                    alpha = (0.16f + 0.24f * r3) * edgeFade(x),
                    colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
                )
            }
        } else {
            drawCircle(
                color = tint.copy(alpha = (0.45f + 0.4f * r2) * edgeFade(x)),
                radius = 2.2f + r3 * 3.4f,
                center = Offset(x * size.width, fall * size.height),
            )
        }
    }
}

private fun DrawScope.drawAmbience(
    state: WeatherSceneState,
    sprites: Map<CloudSpecies, CloudSpriteSet>,
    t: Float,
    quality: EffectQuality,
) {
    when (state.kind) {
        WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY -> if (state.isNight) drawStars(t, quality)
        WeatherKind.FOG -> drawFogBands(sprites, t, quality)
        WeatherKind.THUNDER -> drawThunder(t, state, quality)
        else -> Unit
    }
}

/** 晴夜的星星：对应南风的 `Star(x=…)` 与 `twinkleSpeed` / `twinkleOffset`。 */
private fun DrawScope.drawStars(t: Float, quality: EffectQuality) {
    val count = (46 * quality.particleScale).toInt().coerceAtLeast(10)
    for (i in 0 until count) {
        val r1 = hash01(i * 23 + 3)
        val r2 = hash01(i * 29 + 7)
        val r3 = hash01(i * 37 + 11)
        val twinkle = 0.35f + 0.65f * abs(sin(t * (0.6f + r3 * 1.4f) + r2 * 6.28f))
        drawCircle(
            color = Color(0xFFEFF4FF).copy(alpha = 0.25f + 0.55f * r1 * twinkle),
            radius = 0.8f + r3 * 1.7f,
            center = Offset(r1 * size.width, r2 * size.height * 0.42f),
        )
    }
}

/**
 * 雾带：对应南风的 `FogBank(xFraction=…)` 与 `FogWispShadeDay/Night`。
 *
 * 不额外出素材 —— 直接拿烘焙后的层云物种，压低 alpha、放大尺度、放慢漂移。
 * 这正是"物种 × 光照档"这套结构的收益：雾是**同一种形状换一套光**。
 */
private fun DrawScope.drawFogBands(
    sprites: Map<CloudSpecies, CloudSpriteSet>,
    t: Float,
    quality: EffectQuality,
) {
    val set = sprites[CloudSpecies.STRATUS] ?: return
    if (!set.ready) return
    val count = scaledCount(4, quality)
    for (i in 0 until count) {
        val img = set.at(i)
        val r = hash01(i * 41 + 17)
        val x = ((t * 0.006f + r) % 1.3f) - 0.15f
        val y = 0.42f + r * 0.34f
        val w = (1.4f + r * 0.5f) * size.width
        val h = w * (img.height.toFloat() / img.width.toFloat())
        drawImage(
            image = img,
            dstOffset = androidx.compose.ui.unit.IntOffset((x * size.width - w / 2f).toInt(), (y * size.height - h / 2f).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
            alpha = 0.22f + 0.16f * r,
        )
    }
}

/** 雷暴闪光：周期由强度决定，越强越频繁。双闪比单闪更像真的。 */
private fun DrawScope.drawThunder(t: Float, state: WeatherSceneState, quality: EffectQuality) {
    val period = (7f - 4f * state.intensity).coerceAtLeast(2.5f)
    val phase = (t % period) / period
    val flash = when {
        phase < 0.04f -> 1f - phase / 0.04f
        phase in 0.06f..0.10f -> (phase - 0.06f) / 0.04f * 0.6f
        else -> 0f
    }
    if (flash > 0.01f) drawRect(color = Color(0xFFEAF2FF), alpha = flash * 0.5f)
}

/** 确定性伪随机：同一 seed 永远同一个数，粒子分布可复现，截图才能逐版对比。 */
internal fun hash01(seed: Int): Float {
    var x = seed * 0x9E3779B9
    x = x xor (x ushr 15)
    x *= 0x85EBCA6B
    x = x xor (x ushr 13)
    x *= 0xC2B2AE35
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777216f
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 归一到 [0,1)，对负数也正确（Kotlin 的 % 会保留被除数符号）。 */
private fun Float.mod1(): Float {
    val r = this - floor(this)
    return if (r < 0f) r + 1f else r
}
