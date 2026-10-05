package com.dailyweather.app.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.withFrameNanos

/**
 * 动效治理与帧率控制 —— 名字尽量照南风：
 * `WeatherEffectFps(precip=…)` / `EffectQuality(particleScale=…)` /
 * `LocalWeatherEffectDrawEnabled` / `WeatherEffectFadeDistance` /
 * `rememberEffectTimeline (WeatherEffectConfig.kt:154)`。
 *
 * ## 为什么"分图层帧率"是一根独立的杠杆
 *
 * 云在飘和雨在下，对帧率的敏感度完全不同：云是低频大面积位移，24 fps 看不出来；
 * 雨是高频小面积位移，低于 45 fps 会明显开始"跳"。把它们绑在同一个 vsync 上，
 * 等于为了雨把云的功耗也付满。所以这里按图层分开限。
 *
 * ## 与"质量档"的分工
 *
 * [EffectQuality.particleScale] 只缩**数量**，[WeatherEffectFps] 只限**频率**，
 * [LocalWeatherEffectDrawEnabled] 一刀关掉整个粒子层。三件事各自有唯一出处，
 * 消费点相乘 —— 不在这里造一个新的"场景真相"。
 */
data class WeatherEffectFps(
    val precip: Int,
    val cloud: Int,
    val ambient: Int,
) {
    companion object {
        // 高刷适配（120/144/165Hz）：Full 档的 precip/cloud 只是"上限"，
        // 实际运行时由 WeatherScreen 按设备刷新率注入（refreshFor()），
        // 云不再被锁 30fps —— 那是高刷屏上"云一卡一卡"的根因。
        val Full = WeatherEffectFps(precip = 120, cloud = 120, ambient = 60)
        val Balanced = WeatherEffectFps(precip = 90, cloud = 90, ambient = 45)
        val Low = WeatherEffectFps(precip = 30, cloud = 15, ambient = 0)
        val Static = WeatherEffectFps(precip = 0, cloud = 0, ambient = 0)

        /** 设备刷新率 → 动效时钟（precip/cloud 跟满屏，ambient 折半省电）。上限 120。 */
        fun refreshFor(refreshRateHz: Int): WeatherEffectFps {
            val r = refreshRateHz.coerceIn(60, 120)
            return WeatherEffectFps(precip = r, cloud = r, ambient = (r / 2).coerceAtLeast(24))
        }
    }
}

data class EffectQuality(
    /** 粒子数量倍率：只乘 count，不改结构、不改速度。 */
    val particleScale: Float,
    val fps: WeatherEffectFps,
    /** 低档下近景照片云不再绘制，只留烘焙后的白模云，省一次大纹理采样。 */
    val drawPhotoForeground: Boolean,
) {
    companion object {
        val High = EffectQuality(1.00f, WeatherEffectFps.Full, true)
        val Balanced = EffectQuality(0.70f, WeatherEffectFps.Balanced, true)
        val Low = EffectQuality(0.40f, WeatherEffectFps.Low, false)
        val Off = EffectQuality(0f, WeatherEffectFps.Static, false)
    }
}

/**
 * 全局开关：关掉后整个粒子/云层不绘制，天空渐变仍走动画。
 * 对应设置项「天气动效」与「无背景」。
 */
val LocalWeatherEffectDrawEnabled = compositionLocalOf { true }

/** 当前生效的质量档。由外部（省电/热状态/刷新率）算好后注入，本层不做决策。 */
val LocalEffectQuality = compositionLocalOf { EffectQuality.High }

/**
 * 边缘淡出距离（占画布短边的分数）。
 * 对应南风的 `WeatherEffectFadeDistance`：贴边的元素淡出，避免被硬切成一条直线。
 */
const val WEATHER_EFFECT_FADE_DISTANCE = 0.12f

/** 按"距最近水平边缘的距离"算淡出系数，1=完全不透明。 */
fun edgeFade(xFraction: Float, fade: Float = WEATHER_EFFECT_FADE_DISTANCE): Float {
    val d = minOf(xFraction, 1f - xFraction)
    return (d / fade).coerceIn(0f, 1f)
}

/**
 * 一个按目标帧率推进的时钟，返回值是**已经过的秒数**。
 *
 * 刻意返回 `MutableFloatState` 而不是 `Float`：调用方在 `DrawScope` 里读
 * `.floatValue`，只有绘制阶段失效，不触发重组。若返回 Float，每帧都会重组整棵子树。
 *
 * `fps <= 0` 时时钟完全不起 —— 不是"跑着但不动"，是不启动循环。
 */
@Composable
fun rememberEffectClock(fps: Int): MutableFloatState {
    val elapsed = remember { mutableFloatStateOf(0f) }
    val inspection = LocalInspectionMode.current
    LaunchedEffect(fps, inspection) {
        if (fps <= 0 || inspection) return@LaunchedEffect
        val periodNs = 1_000_000_000L / fps
        var last = -1L
        var start = -1L
        withFrameNanos { } // 拿到第一帧的时间基准
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                if (last < 0L || now - last >= periodNs) {
                    last = now
                    elapsed.floatValue = (now - start) / 1_000_000_000f
                }
            }
        }
    }
    return elapsed
}

/** 把 count 按质量档缩放，并且**至少留 1**（0 会让整层消失，观感上像 bug）。 */
fun scaledCount(base: Int, quality: EffectQuality, min: Int = 1): Int =
    if (quality.particleScale <= 0f) 0 else (base * quality.particleScale).toInt().coerceAtLeast(min)

/** 时间线：一个效果在自己生命周期内的阶段。对应南风的 `rememberEffectTimeline`。 */
enum class EffectPhase { ENTER, STEADY, EXIT }

data class EffectTimeline(val phase: EffectPhase, val progress: Float)

/**
 * 由"进入时刻"与"当前时刻"推出时间线。
 *
 * 入场用 900ms 把粒子从 0 淡入到满，退场用 450ms 淡出 ——
 * 天气切换时新旧两层交叉淡出，而不是"雨突然停了"。
 */
fun effectTimeline(sinceStart: Float, duration: Float, enterMs: Int = 900, exitMs: Int = 450): EffectTimeline {
    val enterS = enterMs / 1000f
    val exitStart = duration - exitMs / 1000f
    return when {
        sinceStart < enterS -> EffectPhase.ENTER.let { EffectTimeline(it, (sinceStart / enterS).coerceIn(0f, 1f)) }
        sinceStart > exitStart -> EffectTimeline(EffectPhase.EXIT, ((sinceStart - exitStart) / (duration - exitStart)).coerceIn(0f, 1f))
        else -> EffectTimeline(EffectPhase.STEADY, 1f)
    }
}
