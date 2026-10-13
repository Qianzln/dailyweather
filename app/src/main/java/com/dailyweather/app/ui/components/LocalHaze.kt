package com.dailyweather.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

/**
 * 南风「双 HazeState」架构的落点。
 *
 * 南风建了两个彼此独立的 HazeState（`PairedGlassHaze` 的 "Paired" 即指此）：
 * - **天空层**：顶栏毛玻璃的采样源（模糊半径 16dp / 噪点 0.02）
 * - **场景层**：卡片/玻璃面的采样源（模糊半径 22dp / 噪点 0.035）
 *
 * 两个状态**采样的是同一片天空**（卡片下面就是天空+动效），但分开持有是为了两处参数互不影响。
 * 这里用 CompositionLocal 下发，任何卡片（含详情页复用）都能直接拿到，不必层层传参。
 *
 * 值为 `null` 表示当前没有采样源（例如未包在首页内的画面）——此时 [GlassCard] 回退到纯色卡面。
 */
val LocalSkyHaze = staticCompositionLocalOf<HazeState?> { null }

val LocalCardHaze = staticCompositionLocalOf<HazeState?> { null }
