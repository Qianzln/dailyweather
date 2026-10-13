package com.dailyweather.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.data.City
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.ui.theme.WeatherGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/**
 * 覆盖顶栏 + 城市指示器胶囊 + 播报均衡器 + 刷新状态图标（南风 `CurrentWeather.kt` 的等价物）。
 *
 * 参数全部来自南风 v4.3.79 逆向核实（C1051o3；计划 §3 批次 3）：
 * - 顶栏玻璃：blur **16dp** / noise **0.02** / progressive 底部渐隐（南风 `y0()`）；
 *   不填 backgroundColor、不设 alpha（避免实色矩形与随滚动忽深忽浅）。
 * - 下拉偏移：南风 `T5` —— 线性段 72% 跟手 + 剩余 28% 指数衰减（系数 1.35），progress>3 封顶。
 * - 指示器胶囊（LiquidGlassCityIndicator, CurrentWeather.kt:412）：横向 padding **16dp**；
 *   点 **8dp** / 间距 **8dp**；`b(i,page) = clamp(1-|i-page|,0,1)`；
 *   点半径 `4dp·(b+7)/8`、alpha `0.5b+0.42`；
 *   定位城市用「当前定位城市」10sp 文字标记（graphicsLayer alpha `0.2b+0.8`、scaleY `0.5b+0.46`）。
 * - 两端溢出点（CityOverflowMarker, :439）：10dp 实心圆，alpha **0.34↔0**（140ms）——
 *   表示城市列表不在头/尾。
 * - 胶囊横滑 **24dp** 阈值 → ±1 城市（南风 class d PointerInput）；点按 → 城市列表。
 * - plus **21dp** / more-horizontal **22dp**，图标 alpha **0.92**（南风 `x7.a.B()`）。
 * - 播报均衡器（SpeakingEqualizer, :790）：6 根，每根 **4×26dp**、alpha 0.92；
 *   每根循环时长 [620,470,740,530,680,430]ms，基础高度比 [0.3,0.65,1.0,0.45,0.8,0.55] ↔ 1.0。
 * - 刷新状态图标（RefreshStatusIcon, :138）：**14dp**，idle 时不渲染
 *   （南风 refreshPhase=Idle/Refreshing/Success/UpToDate 四态，本工程现只有 refreshing 布尔，四态随 P5.4 落地）。
 */

/** 顶栏小控件玻璃模糊半径（南风 y0(): 16dp，区别于卡片的 22dp）。 */
internal val TopBlurRadius = 16.dp

/** 顶栏控件行高度（南风 46dp）；顶栏总高 = 状态栏 + 本值。 */
internal val TopBarContentHeight = 46.dp

/** 南风 T5 下拉偏移（sv.java:3767 还原）。 */
internal fun pullRefreshOffset(progress: Float, maxOffsetPx: Float): Float {
    val d = maxOf(progress, 0f)
    if (d <= 1f) return maxOffsetPx * 0.72f * d
    val excess = minOf(d - 1f, 2f)
    val decay = 1f - exp(-excess * 1.35f)
    return minOf(0.72f * maxOffsetPx + 0.28f * maxOffsetPx * decay, maxOffsetPx)
}

/**
 * 覆盖顶栏（南风 LiquidGlassHomeControls 等价物）。
 *
 * - 背景 = hazeEffect(skyHaze)：16dp 模糊 + 噪点 0.02 + 玻璃 tint + 底部 progressive 渐隐；
 * - 下拉刷新时按 T5 曲线下移；
 * - 居中：城市指示器胶囊（仅多城市，南风 showCityIndicator）；
 * - 右侧：plus（加城市 → 城市列表）+ more-horizontal（设置）。
 */
@Composable
internal fun HomeControls(
    cities: List<City>,
    pageIndex: Int,
    pageOffsetFraction: Float,
    hazeState: HazeState,
    pullProgress: Float,
    onOpenCityList: () -> Unit,
    onOpenSettings: () -> Unit,
    onCitySwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sky = LocalSky.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { 80.dp.toPx() }
    val phasePx = pullRefreshOffset(pullProgress, maxOffsetPx)

    // 顶栏表面 = Haze 毛玻璃 + 一层竖向压暗兜底。
    //
    // ⚠️ 关于"顶栏一会儿深一会儿浅 / 边缘有方形"（用户实机反馈，已修）：
    //   1. 「深浅跳变」来自我早先自创的 `alpha = 0.08 + 0.84*strength` 滚动渐入——
    //      南风源码**根本没设 alpha**（默认 1.0），已删除。
    //   2. 「边缘方形」来自 `backgroundColor = 主题中间色`——那是不透明底色，会把整条顶栏
    //      填成实色矩形（实测边界正好落在 `statusTop + 46dp`，带内外差 Δ=112），已删除。
    //
    // 另一处曾被误判的点：v0.1.36 顶栏呈"亮带"，当时归因于「Haze 采不到 TextureView」，
    // 实际主因是 **tint 用了浅天蓝 #A5C9E6 叠在深色天空上**（见 Theme.kt 的 glassTint 注释）。
    // tint 改为跟随天空色后，这条差异化就消失了，所以这里恢复南风的 Haze 配置。
    //
    // 压暗层的存在理由：南风靠"真模糊 + 玻璃色"保证白字可读，而 GL 天空在白天晴天很亮，
    // 单靠同色调 tint 不够 → 补一层竖向压暗（末段淡到全透明，所以不留硬边）。
    val barSurface = Modifier
        .hazeEffect(hazeState) {
            // 南风 y0()：blur 16dp / noise 0.02 / tint + fallbackTint / progressive 底部渐隐。
            blurRadius = TopBlurRadius
            noiseFactor = WeatherGlass.CONTROL_NOISE_FACTOR
            tints = listOf(HazeTint(sky.glassTint))
            fallbackTint = HazeTint(sky.glassFallbackTint)
            // ⚠️ 底部渐隐必须用 `mask`，**不能**用 `progressive`（实测两次确认）：
            //   `progressive = HazeProgressive.verticalGradient(...)` 在本工程里**对像素零影响**——
            //   把 `endY` 从默认的 +∞ 改成宿主机高（247px）后，前后两帧同场景截图的逐行亮度剖面
            //   完全相同（δ<0.3，仅系统状态栏时钟不同）。原因未在 bytecode 中定位，
            //   但 `HazeProgressive` 走的是 Android `Shader`（`forShader` → `LinearGradient`），
            //   其坐标空间与节点局部坐标不一致才会既不衰减也看不出变化。
            //
            //   `mask: Brush` 则由 Compose 直接按节点尺寸铺开（`Brush.verticalGradient` 默认
            //   `startY=0 / endY=size.height`），单位确定是节点 px，实测有效。
            //
            // 现象回顾（用户反馈「每一个页面都有一条像特意做的高斯模糊横条」）：
            //   带内（y 0..247px）天空被抹平成均匀色块，宿主下边界 y=247 处是一条**硬边**
            //   （1080×2400 @420dpi：statusTop 126px + 46dp 121px = 247px，与实测 250 吻合）。
            //   模糊不会溢出节点图层，所以节点外立刻恢复锐利 → 视觉上就是一条"加出来的条"。
            mask = Brush.verticalGradient(
                0.00f to Color.White,
                0.45f to Color.White,
                0.75f to Color.White.copy(alpha = 0.45f),
                1.00f to Color.Transparent,
            )
        }
        .drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.22f),
                    0.60f to Color.Black.copy(alpha = 0.08f),
                    0.90f to Color.Black.copy(alpha = 0.02f),
                    1.00f to Color.Transparent,
                ),
            )
        }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusTop + TopBarContentHeight)
            .graphicsLayer { translationY = phasePx * 0.45f }
            .then(barSurface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TopBarContentHeight)
                .align(Alignment.BottomCenter),
        ) {
            if (cities.size > 1) {
                CityIndicatorPill(
                    cities = cities,
                    pageIndex = pageIndex,
                    pageOffsetFraction = pageOffsetFraction,
                    color = sky.textPrimary,
                    onOpenCityList = onOpenCityList,
                    onCitySwipe = onCitySwipe,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 南风 AddCityControlIcon（:619）：plus 21dp α0.92 → 城市列表（加城市）
                GlassIconChip(
                    name = "plus",
                    iconSize = 21.dp,
                    color = sky.textPrimary,
                    contentDescription = "添加城市",
                    onClick = onOpenCityList,
                )
                // 南风 EllipsisControlIcon（:609）：more-horizontal 22dp α0.92 → 设置
                GlassIconChip(
                    name = "more-horizontal",
                    iconSize = 22.dp,
                    color = sky.textPrimary,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

/**
 * 圆形玻璃按钮（南风 LiquidGlassControlButton 的等价物）：
 * 30dp 圆底（cardFill α0.30 + 0.5dp 白描边）+ lucide 图标。
 * 图标尺寸按南风逐项给（plus 21dp / more 22dp）。
 */
@Composable
internal fun GlassIconChip(
    name: String,
    iconSize: Dp,
    color: Color,
    contentDescription: String,
    onClick: () -> Unit,
    chipSize: Dp = 30.dp,
) {
    val sky = LocalSky.current
    val icon = rememberLucide(name)
    Box(
        modifier = Modifier
            .size(chipSize)
            .clip(CircleShape)
            .background(sky.cardFill.copy(alpha = 0.30f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = contentDescription,
                tint = color.copy(alpha = 0.92f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 城市指示器胶囊（南风 LiquidGlassCityIndicator, CurrentWeather.kt:412）。
 *
 * - 玻璃底 16dp 圆角（Tokens.PillRadius）+ 横向 padding 16dp；
 * - 内容：[溢出点] 8dp [点列/当前定位城市] 8dp [溢出点]；
 * - 横滑 24dp 阈值 → onCitySwipe(±1)（南风 class d）；点按 → 城市列表；
 * - semantics：「切换城市」/「第 X 个城市，共 N 个」/「暂无城市」+ 上一个/下一个城市动作（南风 m0）。
 */
@Composable
internal fun CityIndicatorPill(
    cities: List<City>,
    pageIndex: Int,
    pageOffsetFraction: Float,
    color: Color,
    onOpenCityList: () -> Unit,
    onCitySwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sky = LocalSky.current
    val count = cities.size
    val density = LocalDensity.current
    // 视窗中心的浮点页位置（Compose：currentPage + currentPageOffsetFraction 恒为视口中心所在页）。
    // 南风同款：b1(index, pageFloat) 驱动点的大小/透明度随滑动连续变化。
    val pageFloat = remember(pageIndex, pageOffsetFraction) { pageIndex + pageOffsetFraction }
    val currentLocationIndex = remember(cities) { cities.indexOfFirst { it.isCurrentLocation } }
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription =
                    if (count > 0) "第 ${pageIndex + 1} 个城市，共 $count 个" else "暂无城市"
                if (count > 0) {
                    // Compose 1.12：customActions 是 List<CustomAccessibilityAction>，动作随对象一起携带。
                    val actions = mutableListOf<CustomAccessibilityAction>()
                    if (pageIndex > 0) actions += CustomAccessibilityAction("上一个城市") { onCitySwipe(-1); true }
                    if (pageIndex < count - 1) actions += CustomAccessibilityAction("下一个城市") { onCitySwipe(1); true }
                    customActions = actions
                }
            }
            .clip(RoundedCornerShape(Tokens.PillRadius))
            .background(sky.cardFill.copy(alpha = 0.34f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(Tokens.PillRadius))
            .pointerInput(count) {
                if (count > 1) {
                    val thresholdPx = with(density) { 24.dp.toPx() }
                    var accumulated = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount
                        },
                        onDragEnd = {
                            // 南风 class d.f：Δx ≤ -24dp → 下一城市；Δx ≥ +24dp → 上一城市
                            if (accumulated <= -thresholdPx) onCitySwipe(1)
                            else if (accumulated >= thresholdPx) onCitySwipe(-1)
                            accumulated = 0f
                        },
                        onDragCancel = { accumulated = 0f },
                    )
                }
            }
            .clickable(onClick = onOpenCityList)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pageIndex > 0) {
                CityOverflowMarker(color)
                Spacer(Modifier.width(8.dp))
            }
            CityMarkerRow(
                cities = cities,
                currentLocationIndex = currentLocationIndex,
                pageFloat = pageFloat,
                color = color,
            )
            if (pageIndex < count - 1) {
                Spacer(Modifier.width(8.dp))
                CityOverflowMarker(color)
            }
        }
    }
}

/**
 * 城市标记列（南风 CityMarkerRow, CurrentWeather.kt:461）。
 *
 * 每个城市一个标记：定位城市是「当前定位城市」文字，其余是 8dp 圆点；
 * 点的大小/透明度随与视窗中心页的接近度 b = clamp(1-|i-page|,0,1) 连续变化。
 */
@Composable
internal fun CityMarkerRow(
    cities: List<City>,
    currentLocationIndex: Int,
    pageFloat: Float,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        cities.forEachIndexed { index, _ ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            val b = (1f - abs(index - pageFloat)).coerceIn(0f, 1f)
            if (index == currentLocationIndex) {
                // 南风：定位城市用文字标记（10sp，α0.92；graphicsLayer α=0.2b+0.8、scaleY=0.5b+0.46）
                Text(
                    text = "当前定位城市",
                    color = color.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = 0.2f * b + 0.8f
                        scaleY = 0.5f * b + 0.46f
                    },
                )
            } else {
                // 南风点：8dp 盒内画圆，半径 (s/2)·(b+7)/8、paint α 0.5b+0.42
                Box(Modifier.size(8.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = (size.width / 2f) * ((b + 7f) / 8f)
                        drawCircle(
                            color = color.copy(alpha = 0.5f * b + 0.42f),
                            radius = radius,
                            center = Offset(size.width / 2f, size.height / 2f),
                        )
                    }
                }
            }
        }
    }
}

/** 南风 CityOverflowMarker（:439）：10dp 实心圆，alpha 0.34↔0（140ms）。 */
@Composable
internal fun CityOverflowMarker(color: Color) {
    val overflowAlpha by animateFloatAsState(
        targetValue = 0.34f,
        animationSpec = tween(140),
        label = "city_overflow_alpha",
    )
    Box(
        Modifier
            .size(10.dp)
            .graphicsLayer { alpha = this.alpha * overflowAlpha },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = color.copy(alpha = 0.92f),
                radius = size.width / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
    }
}

/** 南风 SpeakingEqualizer 每根循环时长（ms）与基础高度比（Q0 内联常量）。 */
private val EqBarDurationsMs = intArrayOf(620, 470, 740, 530, 680, 430)
private val EqBarBaseScales = floatArrayOf(0.3f, 0.65f, 1.0f, 0.45f, 0.8f, 0.55f)

/**
 * 播报中均衡器（南风 SpeakingEqualizer, CurrentWeather.kt:790）：
 * 6 根 4×26dp 圆条（α0.92），每根在基础高度比 ↔ 1.0 间无限往返，周期各异。
 */
@Composable
internal fun SpeakingEqualizer(color: Color, modifier: Modifier = Modifier) {
    val scales = remember { EqBarBaseScales.map { Animatable(it) } }
    LaunchedEffect(Unit) {
        // 每根一个协程（南风 eq_$i 各自 animateFloat）；离开组合即全部取消。
        scales.forEachIndexed { i, s ->
            launch {
                s.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(EqBarDurationsMs[i]),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
            }
        }
    }
    Canvas(
        modifier = modifier
            .width(34.dp) // 6×4dp 条 + 5×2dp 隙
            .height(26.dp),
    ) {
        val barW = 4.dp.toPx()
        val slot = size.width / 6f
        scales.forEachIndexed { i, s ->
            val bh = (size.height * s.value).coerceAtLeast(barW)
            val by = (size.height - bh) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.92f),
                topLeft = Offset(i * slot + (slot - barW) / 2f, by),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * 播报按钮（南风 TTS 控件 d0 + Q0 的等价物）：
 * 未播报 = 30dp 玻璃圆 + volume-2 图标；播报中 = 玻璃胶囊 + 6 柱均衡器。
 * semantics 随状态切换（南风 d0：「正在播报，点击停止」/「播报当前天气」）。
 */
@Composable
internal fun TtsButton(speaking: Boolean, color: Color, onClick: () -> Unit) {
    val sky = LocalSky.current
    Box(
        modifier = Modifier
            .semantics {
                role = Role.Button
                stateDescription = if (speaking) "正在播报，点击停止" else "播报当前天气"
            }
            .clickable(onClick = onClick),
    ) {
        AnimatedContent(
            targetState = speaking,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "ttsButton",
        ) { sp ->
            if (sp) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(Tokens.PillRadius))
                        .background(sky.cardFill.copy(alpha = 0.30f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(Tokens.PillRadius)),
                ) {
                    SpeakingEqualizer(color)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(sky.cardFill.copy(alpha = 0.30f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    rememberLucide("volume-2")?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = color.copy(alpha = 0.92f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 刷新状态图标（南风 RefreshStatusIcon, CurrentWeather.kt:138）：
 * refreshPhase=Idle → 不渲染；Refreshing → 14dp 旋转 refresh-cw。
 * （Success/UpToDate 两态与四态 refreshPhase 随 P5.4 落地。）
 */
@Composable
internal fun RefreshStatusIcon(refreshing: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (!refreshing) return
    val icon = rememberLucide("refresh-cw") ?: return
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        rotation.animateTo(
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        )
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            icon,
            contentDescription = "正在刷新",
            tint = color.copy(alpha = 0.92f),
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation.value },
        )
    }
}
