package com.dailyweather.app.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 顶区防抖收尾（v0.1.29）：云量渐变 + kind 滞回。
 *
 * 背景（用户实机反馈）：数据源在晴/多云间翻牌时，顶部天空"一会儿深一会儿浅"。
 * v0.1.24 的 kind 滞回按**快照次数**确认，防得住快照翻牌，防不住"云量渐变途中"
 * 的档位抖动——本文件把防抖下沉到**连续云量**这一层：
 *
 * 1. [rememberWeatherCloudiness]：目标云量（scene.overcast）用 1.5s 时间常数的指数
 *    曲线渐变过去（`0.3 + 0.7 * (1 - e^(-t/1.5))`，4.5s 收敛到 95%）。顶部亮度
 *    随云量平滑过渡，不再随快照突变。
 * 2. [rememberWeatherKindState]：档位由**渐变后的云量**经双阈值滞回派生——
 *    升多云档须 ≥0.20、退晴档须 ≤0.12，中间 0.08 是缓冲带；配合双帧确认，
 *    云量在 0.12–0.20 区间来回摆动时 kind 不再在 CLEAR/CLOUDY 间跳变。
 * 3. 降水/雾类（RAIN/THUNDER/SNOW/SLEET/FOG）**立即生效**（安全优先），
 *    不参与云量滞回——与 SceneBridge 的 IMMEDIATE_KINDS 口径一致。
 */

/** 降水/雾类：立即生效，不走云量滞回。 */
private val IMMEDIATE_KINDS = setOf(
    WeatherKind.RAIN, WeatherKind.THUNDER,
    WeatherKind.SNOW, WeatherKind.SLEET, WeatherKind.FOG,
)

/**
 * 云量渐变：目标值 [target]（0–1，通常传 scene.overcast）经 1.5s 时间常数的
 * 指数曲线平滑过渡。用 Animatable + 近似指数的 CubicBezier 实现（避免逐帧
 * withFrameNanos 手写循环触发的整树重组）；4500ms ≈ 3 个时间常数，收敛到 95%。
 */
@Composable
fun rememberWeatherCloudiness(target: Float): Float {
    val anim = remember { Animatable(target.coerceIn(0f, 1f)) }
    LaunchedEffect(target) {
        val goal = target.coerceIn(0f, 1f)
        if ((goal - anim.value).let { it < 0.005f && it > -0.005f }) {
            anim.snapTo(goal) // 差异可忽略：直接钉住，不起动画
        } else {
            // 1-e^(-t/1.5) 的形态：起步快（前 1.5s 走 63%）、长尾慢收。
            // CubicBezier(0.3, 0, 0.3, 1) 在 30% 时间走 ~55% 行程，形态最接近。
            anim.animateTo(
                goal,
                animationSpec = tween(
                    durationMillis = 4500,
                    easing = CubicBezierEasing(0.30f, 0f, 0.30f, 1f),
                ),
            )
        }
    }
    return anim.value
}

/**
 * kind 滞回：由渐变云量 [cloudiness] 派生晴/多云档位。
 *
 * - 快照档是降水/雾类 → 直接返回（立即生效，安全优先）；
 * - 当前非多云档且云量 ≥ [CLOUDY_ENTER]（0.20）→ 计数确认，连续 [CONFIRM_FRAMES]
 *   帧后切 CLOUDY；
 * - 当前多云档且云量 ≤ [CLOUDY_EXIT]（0.12）→ 同样确认后退回 [snapshotKind]
 *   （保留 PARTLY_CLOUDY/WIND 等中间档，不强设 CLEAR）；
 * - 缓冲带（0.12–0.20）内来回摆动 → 计数被清零，档位纹丝不动。
 */
@Composable
fun rememberWeatherKindState(
    cloudiness: Float,
    snapshotKind: WeatherKind,
): WeatherKind {
    if (snapshotKind in IMMEDIATE_KINDS) return snapshotKind

    var kind by rememberSaveable { mutableStateOf(snapshotKind) }
    var confirm by remember { mutableIntStateOf(0) }

    LaunchedEffect(cloudiness, snapshotKind) {
        val isCloudyNow = kind == WeatherKind.CLOUDY
        val want: WeatherKind? = when {
            !isCloudyNow && cloudiness >= CLOUDY_ENTER -> WeatherKind.CLOUDY
            isCloudyNow && cloudiness <= CLOUDY_EXIT ->
                snapshotKind.takeIf { it != WeatherKind.CLOUDY } ?: WeatherKind.CLEAR
            else -> null
        }
        if (want == null) {
            confirm = 0
        } else if (want == kind) {
            confirm = 0
        } else {
            confirm++
            if (confirm >= CONFIRM_FRAMES) {
                kind = want
                confirm = 0
            }
        }
    }
    return kind
}

/** 升多云档阈值：云量 ≥0.20 才进（v0.1.29）。 */
const val CLOUDY_ENTER = 0.20f

/** 退晴档阈值：云量 ≤0.12 才退（v0.1.29）。与 ENTER 之间是 0.08 缓冲带。 */
const val CLOUDY_EXIT = 0.12f

/** 双帧确认：阈值越过后连续 2 帧才切换（防抖最后一道闸）。 */
const val CONFIRM_FRAMES = 2
