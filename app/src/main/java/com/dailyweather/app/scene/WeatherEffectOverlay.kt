package com.dailyweather.app.scene

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
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
 * 天气前景效果层的绘制原语 —— 1:1 南风架构。
 *
 * ## 南风的真实结构（逆向确认）
 *
 * 南风的天气动画是**混合方案**：
 * - 天空背景 + 云层：预渲染 PNG（`weather_sky_*` 帧序列 + `weather_cloud_*` 精灵），
 *   由 [SpriteSkyBackground] 与 [drawSpriteCloudLayer] 1:1 复刻；
 * - 降水粒子（雨丝/流星/雪花）与辉光：**代码运行时生成位图**（逆向报告 02 章：
 *   "雨、雪、辉光等天气粒子全部由代码运行时生成位图，一张外部图片都没有"），
 *   雨丝贴唯一的例外素材 `weather_rain_streak_mgl`（20×68 灰度+alpha）。
 *
 * 组件装配（每天气一个 Effect 组件）见 [WeatherEffectHost]（WeatherEffects.kt）。
 * 本文件只留下**原语**：雨/雪/星/太阳/风/雾/雷各画法，彼此独立可被任意 Effect 组合。
 *
 * ## 图层与帧率
 *
 * 南风的 `WeatherEffectFps(precip=…)` 说明云与降水**不在同一帧率**：
 * 云是低频大面积位移（30fps 足够），雨是高频小面积位移（45+ 才不跳）。
 * 三层各自读自己的时钟（[rememberEffectClock]），分图层限帧。
 */

internal enum class PrecipKind { NONE, RAIN, SNOW, SLEET }

internal fun WeatherKind.precipitation(): PrecipKind = when (this) {
    WeatherKind.RAIN, WeatherKind.THUNDER -> PrecipKind.RAIN
    WeatherKind.SNOW -> PrecipKind.SNOW
    WeatherKind.SLEET -> PrecipKind.SLEET
    else -> PrecipKind.NONE
}

internal fun WeatherKind.defaultIntensity(): Float = when (this) {
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
internal fun DrawScope.drawPrecipitation(
    kind: PrecipKind,
    intensity: Float,
    wind: Float,
    night: Boolean,
    t: Float,
    quality: EffectQuality,
    streak: Bitmap?,
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
                    dstSize = androidx.compose.ui.unit.IntSize(wPx.toInt().coerceAtLeast(1), len.toInt()),
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
                dstSize = androidx.compose.ui.unit.IntSize(sizePx.toInt().coerceAtLeast(1), sizePx.toInt().coerceAtLeast(1)),
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
                    image = streak.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset((cx - wPx / 2f).toInt(), (cy - len / 2f).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(wPx.toInt().coerceAtLeast(1), len.toInt()),
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

internal fun DrawScope.drawAmbience(
    state: WeatherSceneState,
    wispBitmap: Bitmap?,
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
        WeatherKind.FOG -> drawFogBands(wispBitmap, t, quality)
        WeatherKind.WIND -> drawWindLines(state.wind, t, quality)
        WeatherKind.THUNDER -> drawThunder(t, state, quality)
        else -> Unit
    }
}

/**
 * 太阳：位置由 [WeatherSceneState.sunProgress]（日出 0 → 日落 1）决定，
 * 走一条上拱的弧线；辉光精灵 tint 暖白打底，核心亮盘在上。
 * 夜间/缺日出日落数据时 sunProgress 为 null，不画。
 *
 * 朝晚霞增强：morning/evening phase 时，太阳靠近地平线（progress < 0.2 或 > 0.8），
 * 放大光晕并注入暖橙色 tint，形成霞光效果（对齐南风 SunGlow 逻辑）。
 */
internal fun DrawScope.drawSun(state: WeatherSceneState, quality: EffectQuality) {
    val progress = state.sunProgress ?: return
    val glow = ParticleSprites.glowSprite() ?: return
    // 弧线：x 从屏宽 12% 到 88%，y 在中午(0.5)升到最高。
    val x = size.width * (0.12f + 0.76f * progress)
    val y = size.height * (0.50f - 0.30f * sin(PI.toFloat() * progress))
    val coreR = size.width * 0.038f
    val glowSize = coreR * 7f
    // 朝晚霞判定：日出前20%或日落前20%时段内，太阳靠近地平线，霞光增强。
    val isGlowPhase = state.phase == SkyPhase.MORNING || state.phase == SkyPhase.EVENING
    val (tints, glowAlpha) = if (isGlowPhase) {
        // 朝晚霞：暖橙色 tint，光晕更大更亮。
        listOf(Color(0xAAFF9944), Color(0x66FFB366)) to 0.75f
    } else {
        // 正午：正常暖白 tint。
        listOf(Color(0x55FFD98A), Color(0x33FFE9C0)) to 0.5f
    }
    // 低档质量光晕只画一层小的，省一次大纹理采样。
    val glowLayers = if (quality.particleScale >= 0.7f) 2 else 1
    for (i in 0 until glowLayers) {
        val s = glowSize * (1f + i * 0.55f) * (if (isGlowPhase) 1.5f else 1f)
        drawImage(
            image = glow,
            dstOffset = androidx.compose.ui.unit.IntOffset((x - s / 2f).toInt(), (y - s / 2f).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(s.toInt(), s.toInt()),
            alpha = glowAlpha,
            colorFilter = ColorFilter.tint(tints[i], BlendMode.SrcIn),
        )
    }
    drawCircle(Color(0xCCFFF3CE), radius = coreR, center = Offset(x, y))
    drawCircle(Color(0xFFFFFFFF).copy(alpha = 0.85f), radius = coreR * 0.62f, center = Offset(x, y))
    // 朝晚霞额外添加一道地平线暖色光晕（对齐南风 HorizonGlow）。
    if (isGlowPhase) {
        val horizonY = size.height * 0.88f
        val horizonGlowR = size.width * 0.35f
        drawCircle(
            color = Color(0xCCFF8C42),
            radius = horizonGlowR,
            center = Offset(size.width * 0.5f, horizonY),
        )
        drawCircle(
            color = Color(0x44FFB366),
            radius = horizonGlowR * 1.5f,
            center = Offset(size.width * 0.5f, horizonY),
        )
    }
}

/**
 * 晴夜流星：约每 9 秒一颗（确定性伪随机，同周期同轨迹，可复现）。
 * 斜向划过，头亮尾淡，生命末期整体淡出。
 */
internal fun DrawScope.drawShootingStars(t: Float, quality: EffectQuality) {
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
internal fun DrawScope.drawWindLines(wind: Float, t: Float, quality: EffectQuality) {
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
internal fun DrawScope.drawStars(t: Float, quality: EffectQuality) {
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
 * 雾带：对应南风的 `FogBank(xFraction=…)`。
 *
 * 1:1 精灵化后直接贴 WISP 薄云精灵：压低 alpha、放大尺度、放慢漂移。
 * 雾在素材体系里就是"同一种形状换一套参数"。
 */
internal fun DrawScope.drawFogBands(
    wispBitmap: Bitmap?,
    t: Float,
    quality: EffectQuality,
) {
    if (wispBitmap == null) return
    val img = wispBitmap.asImageBitmap()
    val count = scaledCount(4, quality)
    for (i in 0 until count) {
        val r = hash01(i * 41 + 17)
        val x = ((t * 0.006f + r) % 1.3f) - 0.15f
        val y = 0.42f + r * 0.34f
        val w = (1.4f + r * 0.5f) * size.width
        val h = w * (img.height.toFloat() / img.width.toFloat())
        drawImage(
            image = img,
            dstOffset = androidx.compose.ui.unit.IntOffset((x * size.width - w / 2f).toInt(), (y * size.height - h / 2f).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
            alpha = 0.22f + 0.16f * r,
        )
    }
}

/**
 * 雷暴：随机定时闪 + 闪电折线。
 * 折线按"周期序号"确定性生成（同周期同形状，可复现）：主干 8 段从云底劈下，
 * 带 1–2 条分支；辉光宽线打底、核心细线提亮，配合整屏闪光的双闪包络。
 */
internal fun DrawScope.drawThunder(t: Float, state: WeatherSceneState, quality: EffectQuality) {
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
    val px = x0
    val py = size.height * 0.06f
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
            dstSize = androidx.compose.ui.unit.IntSize(s.toInt(), s.toInt()),
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
