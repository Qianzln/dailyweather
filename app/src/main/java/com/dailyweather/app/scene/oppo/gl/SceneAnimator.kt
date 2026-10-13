package com.dailyweather.app.scene.oppo.gl

import com.dailyweather.app.scene.oppo.OppoEntity

/**
 * 场景动画求值器：把渲染器里的动画逻辑（雷闪包络、云漂移、场景淡入）抽离成纯函数。
 *
 * ## 职责
 * - 雷闪双峰包络（按 21_2 预设 thunder_animator 关键帧重建）
 * - 云层平移漂移（右→左 + 环绕回场，按深度视差）
 * - 场景入场淡入（0.9s 全局淡入系数）
 *
 * ## 设计原则
 * 所有方法都是纯函数：输入时间/实体参数，输出动画值，不持有状态。
 * 渲染器只负责调用和上传 uniform，不再关心动画细节。
 */
object SceneAnimator {

    // ---------------- 雷闪 ----------------

    /** 闪电包络长度（秒）：参考 21_2 预设 thunder_animator 关键帧的 0.885s 周期。 */
    const val FLASH_LEN = 0.885f

    /** 两次闪电之间的完整周期（含静默）。 */
    const val FLASH_PERIOD = 4.5f

    /**
     * 闪电双峰包络（按参考关键帧重建）：
     * `u_flashStrength: 0→0.3@0.07→0.8@0.32→0.2@0.38→0.9@0.47→0@0.82`
     * `u_alpha:        0→1@0.32→0.6@0.34→0.8@0.39→…→0@0.885`
     * 两簇合并成一条 0→1→0.55→1→0 的双峰曲线。
     */
    fun thunderEnvelope(s: Float): Float = when {
        s < 0f -> 0f
        s < 0.07f -> s / 0.07f * 0.3f
        s < 0.32f -> 0.3f + (s - 0.07f) / 0.25f * 0.7f
        s < 0.38f -> 1f - (s - 0.32f) / 0.06f * 0.45f
        s < 0.47f -> 0.55f + (s - 0.38f) / 0.09f * 0.45f
        s <= FLASH_LEN -> 1f - (s - 0.47f) / (FLASH_LEN - 0.47f)
        else -> 0f
    }

    /**
     * 雷帧当前状态：返回 (包络值, 溶解值)，null 表示静默期（不绘制）。
     *
     * 调用方用法：
     * ```kotlin
     * val state = thunderState(timeSeconds) ?: return  // 静默期跳过
     * glUniform1f(prog.loc("u_alpha"), state.first)
     * glUniform1f(prog.loc("u_DissolveValue"), state.second)
     * ```
     */
    fun thunderState(timeSeconds: Float): Pair<Float, Float>? {
        val t = timeSeconds % FLASH_PERIOD
        if (t > FLASH_LEN) return null  // 静默期
        val env = thunderEnvelope(t)
        val dissolve = when {
            t < 0.01f -> 1f
            t < 0.42f -> 1f - (t - 0.01f) / 0.41f  // 1→0 溶解显形
            else -> 0f
        }
        return env to dissolve
    }

    // ---------------- 云漂移 ----------------

    /**
     * 云实体当前 X 坐标（含漂移）。
     *
     * 规则（2026-10-13 实机反馈重建）：
     * - 右→左缓慢平移 + 环绕回场
     * - 遍历区间 = 2 × (1 + scaleX)（云完整出屏再从右侧回场）
     * - 速度按深度分层（视差）：z≈-0.7（最远）≈300s 穿屏，z≈0.6（最近）≈150s 穿屏
     * - 云贴图四边 alpha 渐隐到 0（CLAMP_TO_EDGE），滑出/滑入屏幕边缘无硬切
     */
    fun cloudDriftX(e: OppoEntity, timeSeconds: Float): Float {
        val scaleX = kotlin.math.abs(e.scale.getOrElse(0) { 1f }).coerceAtLeast(0.01f)
        val range = 2f * (1f + scaleX)
        val depthFactor = ((e.pos.getOrElse(2) { 0f } + 0.7f) / 1.3f).coerceIn(0f, 1f)
        val period = 300f - 150f * depthFactor
        val drift = timeSeconds * (range / period)
        return ((e.pos[0] + 1f + scaleX - drift) % range + range) % range - (1f + scaleX)
    }

    // ---------------- 场景淡入 ----------------

    /**
     * 场景入场淡入系数。
     *
     * 原版 MainScene 的 cloud_fade/sun_fade/moon_fade/star_fade/wind_fade/fog_fade
     * 在切换场景时把各元素从 0 淡入（0.4~1.1s）。这里统一成 0.9s 的全局淡入。
     *
     * @param timeSeconds 当前时间（渲染器基准）
     * @param fadeStart 淡入起点时间（-1 = 已播完，返回 1f）
     * @return 0..1 淡入系数
     */
    fun sceneFade(timeSeconds: Float, fadeStart: Float): Float {
        if (fadeStart < 0f) return 1f
        val f = (timeSeconds - fadeStart) / 0.9f
        return f.coerceIn(0f, 1f)
    }

    /**
     * 判断淡入是否已播完。
     *
     * @return true 表示已播完，调用方应将 fadeStart 置为 -1
     */
    fun sceneFadeDone(timeSeconds: Float, fadeStart: Float): Boolean {
        if (fadeStart < 0f) return true
        return (timeSeconds - fadeStart) / 0.9f >= 1f
    }
}
