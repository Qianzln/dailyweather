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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

internal enum class PrecipKind { NONE, RAIN, SNOW, SLEET }

private fun WeatherKind.precipitation(): PrecipKind = when (this) {
    WeatherKind.RAIN, WeatherKind.THUNDER -> PrecipKind.RAIN
    WeatherKind.SNOW -> PrecipKind.SNOW
    WeatherKind.SLEET -> PrecipKind.SLEET
    else -> PrecipKind.NONE
}

private fun WeatherKind.defaultIntensity(): Float = when (this) {
    WeatherKind.RAIN, WeatherKind.THUNDER -> 0.55f
    WeatherKind.SNOW, WeatherKind.SLEET -> 0.40f
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
 *
 * 雨还叠一层**雨滴流星**（32×512 程序化长条，对应南风 `rainMeteorNear/Far`）：
 * 远景稀疏、更长更快、更淡，雨的"层次感"来自近景雨丝 + 远景流星的视差。
 * 雪花是 24 帧程序化自旋（`ParticleSprites.snowflakeFrames`），帧循环即自旋。
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

    // ---- 雨滴流星层（远景，只有 RAIN/SLEET 有）----
    val meteor = ParticleSprites.meteorSprite()
    if (meteor != null && kind != PrecipKind.SNOW) {
        val meteorCount = (count / 5).coerceAtLeast(2)
        for (i in 0 until meteorCount) {
            val r1 = hash01(i * 53 + 7)
            val r2 = hash01(i * 61 + 11)
            val r3 = hash01(i * 71 + 13)
            val fall = (t * (1.7f + r3 * 0.9f) + r2) % 1f
            val x = (r1 + fall * tanTilt * 0.8f / aspect).mod1()
            val len = size.height * (0.16f + r3 * 0.14f)
            val wPx = len * (meteor.width.toFloat() / meteor.height.toFloat())
            rotate(degrees = tiltDeg + 2f, pivot = Offset(x * size.width, fall * size.height)) {
                drawImage(
                    image = meteor,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (x * size.width - wPx / 2f).toInt(), (fall * size.height - len / 2f).toInt(),
                    ),
                    dstSize = IntSize(wPx.toInt().coerceAtLeast(1), len.toInt()),
                    alpha = (0.10f + 0.12f * r2) * edgeFade(x),
                    colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
                )
            }
        }
    }

    // ---- 雪花层（SNOW 全量，SLEET 一半粒子）----
    val snowFrames = ParticleSprites.snowflakeFrames()
    val snowCount = when (kind) {
        PrecipKind.SNOW -> count
        PrecipKind.SLEET -> count / 2
        else -> 0
    }
    if (snowFrames != null && snowCount > 0) {
        val frameCount = snowFrames.size
        for (i in 0 until snowCount) {
            val r1 = hash01(i * 7 + 1)
            val r2 = hash01(i * 13 + 5)
            val r3 = hash01(i * 19 + 9)
            val fall = (t * (0.16f + r3 * 0.12f) + r2) % 1f
            val x = (r1 + 0.05f * sin(t * 1.6f + r3 * 6.28f)).mod1()
            val sizePx = size.height * (0.014f + r3 * 0.020f)
            // 帧随时间推进 → 连续自旋；r2 错开相位，避免整屏雪花同步转。
            val frame = (((t * (0.5f + r3) + r2 * frameCount) * frameCount).toInt()) % frameCount
            drawImage(
                image = snowFrames[frame],
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (x * size.width - sizePx / 2f).toInt(), (fall * size.height - sizePx / 2f).toInt(),
                ),
                dstSize = IntSize(sizePx.toInt().coerceAtLeast(1), sizePx.toInt().coerceAtLeast(1)),
                alpha = (0.5f + 0.4f * r2) * edgeFade(x),
            )
        }
    }

    // ---- 雨丝层（RAIN 全量，SLEET 一半）----
    val streakCount = when (kind) {
        PrecipKind.RAIN -> count
        PrecipKind.SLEET -> count / 2
        else -> 0
    }
    for (i in 0 until streakCount) {
        val r1 = hash01(i * 7 + 1)
        val r2 = hash01(i * 13 + 5)
        val r3 = hash01(i * 19 + 9)

        val fall = (t * (1.15f + r3 * 0.85f) + r2) % 1f
        val x = (r1 + fall * tanTilt / aspect).mod1()
        val len = (0.022f + r3 * 0.020f) * size.height

        if (streak != null) {
            val wPx = len * 0.22f
            val cx = x * size.width
            val cy = fall * size.height
            rotate(degrees = tiltDeg, pivot = Offset(cx, cy)) {
                drawImage(
                    image = streak,
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - wPx / 2f).toInt(), (cy - len / 2f).toInt()),
                    dstSize = IntSize(wPx.toInt().coerceAtLeast(1), len.toInt()),
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
        WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY -> {
            if (state.isNight) {
                drawStars(t, quality)
                drawShootingStars(t, quality)
            } else {
                // 晴/多云白天：太阳沿日出→日落弧线走（SunPose），光晕辉光打底。
                drawSun(state, quality)
            }
        }
        WeatherKind.FOG -> drawFogBands(sprites, t, quality)
        WeatherKind.WIND -> drawWindLines(state.wind, t, quality)
        WeatherKind.THUNDER -> drawThunder(t, state, quality)
        else -> Unit
    }
}

/**
 * 太阳：位置由 [WeatherSceneState.sunProgress]（日出 0 → 日落 1）决定，
 * 走一条上拱的弧线；辉光精灵 tint 暖白打底，核心亮盘在上。
 * 夜间/缺日出日落数据时 sunProgress 为 null，不画。
 */
private fun DrawScope.drawSun(state: WeatherSceneState, quality: EffectQuality) {
    val progress = state.sunProgress ?: return
    val glow = ParticleSprites.glowSprite() ?: return
    // 弧线：x 从屏宽 12% 到 88%，y 在中午(0.5)升到最高。
    val x = size.width * (0.12f + 0.76f * progress)
    val y = size.height * (0.50f - 0.30f * sin(PI.toFloat() * progress))
    val coreR = size.width * 0.038f
    val glowSize = coreR * 7f
    // 低档质量光晕只画一层小的，省一次大纹理采样。
    val glowLayers = if (quality.particleScale >= 0.7f) 2 else 1
    val tints = listOf(Color(0x55FFD98A), Color(0x33FFE9C0))
    for (i in 0 until glowLayers) {
        val s = glowSize * (1f + i * 0.55f)
        drawImage(
            image = glow,
            dstOffset = androidx.compose.ui.unit.IntOffset((x - s / 2f).toInt(), (y - s / 2f).toInt()),
            dstSize = IntSize(s.toInt(), s.toInt()),
            alpha = 0.5f,
            colorFilter = ColorFilter.tint(tints[i], BlendMode.SrcIn),
        )
    }
    drawCircle(Color(0xCCFFF3CE), radius = coreR, center = Offset(x, y))
    drawCircle(Color(0xFFFFFFFF).copy(alpha = 0.85f), radius = coreR * 0.62f, center = Offset(x, y))
}

/**
 * 晴夜流星：约每 9 秒一颗（确定性伪随机，同周期同轨迹，可复现）。
 * 斜向划过，头亮尾淡，生命末期整体淡出。
 */
private fun DrawScope.drawShootingStars(t: Float, quality: EffectQuality) {
    val cycle = 9f
    val index = floor(t / cycle)
    val phase = (t % cycle) / cycle
    // 只在周期的前 22% 里活着：其余时间安静，等下一颗。
    if (phase > 0.22f) return
    val life = phase / 0.22f
    val r1 = hash01(index.toInt() * 91 + 3)
    val r2 = hash01(index.toInt() * 97 + 5)
    val x0 = size.width * (0.15f + 0.7f * r1)
    val y0 = size.height * (0.06f + 0.18f * r2)
    val travel = size.width * (0.22f + 0.18f * r1)
    val dx = travel * life
    val dy = travel * life * 0.45f
    val alpha = sin(PI.toFloat() * life) * 0.9f
    val tail = travel * 0.30f
    // 尾迹：头亮尾淡的短线段。
    drawLine(
        color = Color(0xFFE8F2FF).copy(alpha = alpha * 0.75f),
        start = Offset(x0 + dx, y0 + dy),
        end = Offset(x0 + dx - tail * 0.85f, y0 + dy - tail * 0.38f),
        strokeWidth = 2.2f,
    )
    drawCircle(
        color = Color(0xFFFFFFFF).copy(alpha = alpha),
        radius = 2.6f,
        center = Offset(x0 + dx, y0 + dy),
    )
}

/**
 * 风线：横向掠过的细长气流痕，速度与长度随风强走。
 * 对应南风 R2 风特效的最小可行版：风天"看得见风在跑"。
 */
private fun DrawScope.drawWindLines(wind: Float, t: Float, quality: EffectQuality) {
    val count = scaledCount((6 + (wind * 8).toInt()), quality, min = 3)
    val speed = 0.10f + wind * 0.30f
    for (i in 0 until count) {
        val r1 = hash01(i * 43 + 5)
        val r2 = hash01(i * 47 + 9)
        val r3 = hash01(i * 53 + 15)
        val x = ((t * (speed + r3 * 0.06f) + r1) % 1.3f) - 0.15f
        val y = 0.14f + r2 * 0.55f
        val len = size.width * (0.10f + 0.14f * r3 * (0.5f + wind))
        val yDrift = len * 0.06f * (r2 - 0.5f)
        drawLine(
            color = Color(0xFFE8F0FA).copy(alpha = (0.14f + 0.20f * r3) * edgeFade(x)),
            start = Offset(x * size.width, y * size.height),
            end = Offset(x * size.width + len, y * size.height + yDrift),
            strokeWidth = 1.6f,
        )
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

/**
 * 雷暴：随机定时闪 + 闪电折线。
 * 折线按"周期序号"确定性生成（同周期同形状，可复现）：主干 8 段从云底劈下，
 * 带 1–2 条分支；辉光宽线打底、核心细线提亮，配合整屏闪光的双闪包络。
 */
private fun DrawScope.drawThunder(t: Float, state: WeatherSceneState, quality: EffectQuality) {
    val period = (7f - 4f * state.intensity).coerceAtLeast(2.5f)
    val index = floor(t / period).toInt()
    val phase = (t % period) / period
    val flash = when {
        phase < 0.04f -> 1f - phase / 0.04f
        phase in 0.06f..0.10f -> (phase - 0.06f) / 0.04f * 0.6f
        else -> 0f
    }
    if (flash > 0.01f) drawRect(color = Color(0xFFEAF2FF), alpha = flash * 0.5f)
    if (flash <= 0.05f) return

    val glow = ParticleSprites.glowSprite()
    val seed = index * 131 + 17
    val x0 = size.width * (0.18f + 0.64f * hash01(seed))
    val segments = 8
    var px = x0
    var py = size.height * 0.06f
    val strokeGlow = size.width * 0.012f
    val strokeCore = size.width * 0.0035f
    val points = mutableListOf(Offset(px, py))
    val branches = mutableListOf<Pair<Offset, Offset>>()
    for (i in 1..segments) {
        val f = i / segments.toFloat()
        val nx = x0 + (hash01(seed + i * 7) - 0.5f) * size.width * 0.14f + (hash01(seed + i * 3) - 0.5f) * size.width * 0.05f
        val ny = size.height * (0.06f + 0.50f * f)
        points.add(Offset(nx, ny))
        // 分支：从中间某节点斜出，长度中等，只挂一条。
        if (i in 3..5 && branches.isEmpty() && hash01(seed + i * 11) > 0.4f) {
            branches.add(
                Offset(nx, ny) to Offset(
                    nx + (hash01(seed + i * 13) - 0.5f) * size.width * 0.16f,
                    ny + size.height * 0.12f,
                ),
            )
        }
        px = nx
        py = ny
    }
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val branchPath = androidx.compose.ui.graphics.Path().apply {
        branches.forEach { (a, b) ->
            moveTo(a.x, a.y); lineTo(b.x, b.y)
        }
    }
    // 辉光：先画一个起点的辉光精灵，再画宽描边。
    if (glow != null && quality.particleScale >= 0.7f) {
        val s = strokeGlow * 22f
        val y0 = size.height * 0.06f
        drawImage(
            image = glow,
            dstOffset = androidx.compose.ui.unit.IntOffset((x0 - s / 2f).toInt(), (y0 - s / 2f).toInt()),
            dstSize = IntSize(s.toInt(), s.toInt()),
            alpha = flash * 0.8f,
            colorFilter = ColorFilter.tint(Color(0x66CFE4FF), BlendMode.SrcIn),
        )
    }
    drawPath(path, color = Color(0xFFBBD6FF).copy(alpha = flash * 0.55f), style = Stroke(width = strokeGlow, cap = StrokeCap.Round))
    drawPath(branchPath, color = Color(0xFFBBD6FF).copy(alpha = flash * 0.45f), style = Stroke(width = strokeGlow * 0.6f, cap = StrokeCap.Round))
    drawPath(path, color = Color(0xFFF4F9FF).copy(alpha = flash), style = Stroke(width = strokeCore, cap = StrokeCap.Round))
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
