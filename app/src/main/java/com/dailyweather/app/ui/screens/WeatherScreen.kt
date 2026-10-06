package com.dailyweather.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.advice.LifeAdviceFrom
import com.dailyweather.app.data.HomeCardId
import com.dailyweather.app.data.Units
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.scene.AnimatedSkyGradient
import com.dailyweather.app.scene.SceneBridge
import com.dailyweather.app.scene.LocalEffectQuality
import com.dailyweather.app.scene.LocalWeatherEffectDrawEnabled
import com.dailyweather.app.scene.EffectQuality
import com.dailyweather.app.scene.TopProgressiveGlass
import com.dailyweather.app.scene.TopProgressiveScrim
import com.dailyweather.app.scene.WeatherEffectFps
import com.dailyweather.app.scene.WeatherEffectHost
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.SkyconMap
import com.dailyweather.app.ui.components.WeatherBitmapIcon
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.components.rememberSf
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.SkyPalette
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.viewmodel.WeatherViewModel
import com.dailyweather.app.util.Lunar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 首页：分层天空（渐变 → 云层 → 降水/氛围 → 顶部压暗）+ 居中 Hero + 卡片流。
 *
 * 卡片矩阵按天气分支：南风的晴天与雨天不是同一套卡。这里先做雨天/阴天
 * （小时 → 7日 → 台风雷达 → 生活建议），晴天的专属矩阵等真机截图再校。
 */
@Composable
fun WeatherScreen(
    vm: WeatherViewModel,
    blueTheme: Boolean,
    onOpenCityList: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (com.dailyweather.app.DetailKind) -> Unit = {},
    onLocate: () -> Unit,
) {
    val cities by vm.cities.collectAsState()
    val snapshots by vm.snapshotCache.collectAsState()
    val city = vm.cityFor(vm.selectedCityId)
        ?: cities.firstOrNull { it.isCurrentLocation }
        ?: cities.firstOrNull()
    val snapshot = snapshots[vm.selectedCityId] ?: snapshots[city?.id]
    val cityZone = city?.zone ?: java.time.ZoneId.systemDefault()
    val cards by vm.homeCards.collectAsState()
    var source by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(vm.selectedCityId) { source = vm.sourceOf(vm.selectedCityId) }

    val motion by vm.settingFlow(WeatherViewModel.SettingKey.MOTION).collectAsState(true)
    // 省电模式感知（对应南风 rememberPowerSaveMode）：省电时特效降到 Low 档。
    val context = androidx.compose.ui.platform.LocalContext.current
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    val powerSave = powerManager.isPowerSaveMode
    // 高刷适配（120/144/165Hz）：动效时钟跟随设备刷新率，云/雨不再被锁低帧。
    val view = androidx.compose.ui.platform.LocalView.current
    val refreshRate = remember { view.display?.refreshRate?.toInt()?.coerceAtLeast(60) ?: 60 }
    val effectQuality = remember(refreshRate, powerSave) {
        if (powerSave) EffectQuality.Low else EffectQuality.High.copy(fps = WeatherEffectFps.refreshFor(refreshRate))
    }
    val scene = remember(snapshot, cityZone) {
        SceneBridge.stateFor(snapshot, cityZone, cityKey = city?.id ?: "default")
    }
    val sky = SkyPalette.of(scene)

    CompositionLocalProvider(
        LocalSky provides sky,
        // 「天气动效」开关 + 省电模式 → 整个粒子层的绘制开关。
        LocalWeatherEffectDrawEnabled provides (motion && !powerSave),
        LocalEffectQuality provides effectQuality,
    ) {
        // 下拉刷新（material3）：刷新走当前城市 refreshCurrent，定位仍走右上按钮。
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refreshCurrent() },
            modifier = Modifier.fillMaxSize(),
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部毛玻璃的背板：采样"天空+特效+滚动内容"，玻璃带模糊其覆盖区域的
            // 全部内容（南风同款 backdrop-android：卡片穿过状态栏区域时也被真模糊）。
            val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            val scrollState = rememberScrollState()

            Box(modifier = Modifier.matchParentSize().layerBackdrop(backdrop)) {
                AnimatedSkyGradient(scene, Modifier.matchParentSize())
                // 天气切换时的交叉淡入淡出（对齐南风 effectTimeline：约 900ms 过渡），
                // 新旧两层粒子互相淡入淡出，而不是"雨突然停了"。
                androidx.compose.animation.Crossfade(
                    targetState = scene.kind to scene.phase,
                    animationSpec = androidx.compose.animation.core.tween(900),
                    label = "weatherEffectCrossfade",
                    modifier = Modifier.matchParentSize(),
                ) { key ->
                    val (kind, phase) = key
                    val faded = scene.copy(kind = kind, phase = phase)
                    WeatherEffectHost(faded, Modifier.matchParentSize())
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.statusBars.asPaddingValues())
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text(
                        text = city?.name ?: "添加城市",
                        color = sky.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                            .clip(RoundedCornerShape(16.dp))
                            .background(sky.cardFill.copy(alpha = 0.34f))
                            .clickable(onClick = onOpenCityList)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 右上角只保留设置入口；定位走「点击重试」占位态与下拉刷新。
                        HeaderIcon("more-horizontal", sky.textPrimary, onClick = onOpenSettings)
                    }
                }

                val snap = snapshot
                when {
                    snap != null -> {
                        HeroSection(snap)
                        Spacer(Modifier.height(Tokens.CardGap))
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
                            verticalArrangement = Arrangement.spacedBy(Tokens.CardGap),
                        ) {
                            cards.forEachIndexed { index, card ->
                                // 错峰入场：卡片按序淡入 + 轻微上移，数据/城市切换时也有过渡感。
                                CardEnter(index) {
                                    when (card) {
                                        HomeCardId.HOURLY -> HourlyCard(snap, blueTheme, cityZone) { onOpenDetail(com.dailyweather.app.DetailKind.HOURLY) }
                                        HomeCardId.DAILY -> DailyCard(snap, blueTheme, cityZone)
                                        HomeCardId.PRECIP -> PrecipCard(snap) { onOpenDetail(com.dailyweather.app.DetailKind.MINUTELY) }
                                        HomeCardId.LIFE -> LifeAdviceCard(snap)
                                        HomeCardId.AQI -> AqiCard(snap) { onOpenDetail(com.dailyweather.app.DetailKind.AIR_QUALITY) }
                                        HomeCardId.SUN -> SunCard(snap, cityZone)
                                        HomeCardId.DETAIL -> DetailCard(snap)
                                        else -> Unit
                                    }
                                }
                            }
                            vm.message?.let { msg ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(msg, color = sky.textSecondary, fontSize = 13.sp)
                                }
                            }
                            source.takeIf { it.isNotBlank() }?.let { src ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("部分气象数据来自", color = Tokens.TextTertiary, fontSize = 11.sp)
                                    Spacer(Modifier.width(5.dp))
                                    Text(src, color = Tokens.TextSecondary, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(80.dp))
                        }
                    }
                    city != null -> HeroPlaceholder(city.name, vm.message, onLocate)
                    else -> EmptyState(onOpenSearch, vm.message)
                }
            }
            } // layerBackdrop 采样层

            // 顶部毛玻璃带：锚定屏幕顶（含状态栏后）128dp，滚动时内容穿过被真·模糊（南风同款）。
            TopProgressiveGlass(backdrop, scrollState)
            TopProgressiveScrim(Modifier.matchParentSize())
        }
        } // PullToRefreshBox
    }
}

/** 卡片错峰入场：淡入 + 轻微上移，每张错开 60ms（学报告整体过渡取向）。 */
@Composable
private fun CardEnter(index: Int, content: @Composable () -> Unit) {
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val shift = remember { androidx.compose.animation.core.Animatable(26f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 60L)
        launch {
            alpha.animateTo(1f, androidx.compose.animation.core.tween(450))
        }
        launch {
            shift.animateTo(0f, androidx.compose.animation.core.tween(450))
        }
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = shift.value
        },
    ) { content() }
}

@Composable
private fun HeaderIcon(name: String, tint: Color, onClick: () -> Unit) {
    val sky = LocalSky.current
    val icon = rememberLucide(name)
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape)
            .background(sky.cardFill.copy(alpha = 0.30f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp)) }
    }
}

/** Hero：[实测] 92sp 数字 + 上标度号，字面是白→灰的竖向渐变，不是纯色。 */
@Composable
private fun HeroSection(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    val today = snapshot.daily.firstOrNull { it.date.take(10) == LocalDate.now().toString() }
        ?: snapshot.daily.firstOrNull()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Alignment.Bottom + 两者 lineHeight 一致 → 行盒底对齐 → 基线对齐
        Row(verticalAlignment = Alignment.Bottom) {
            // 温度变化时数字上滑淡入（对齐南风的 Hero 数字过渡），不是瞬间跳变。
            val tempText = Units.tempValue(snapshot.currentTemp).roundToInt().toString()
            androidx.compose.animation.AnimatedContent(
                targetState = tempText,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.tween(450),
                        initialOffsetY = { it / 3 },
                    ) + androidx.compose.animation.fadeIn())
                        .togetherWith(androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(250),
                        ))
                },
                label = "heroTemp",
            ) { text ->
                Text(
                    text = text,
                    style = TextStyle(
                        fontSize = Tokens.HeroTempSize,
                        lineHeight = Tokens.HeroTempSize,
                        fontWeight = FontWeight.Medium,
                        brush = Brush.verticalGradient(listOf(Tokens.HeroGradientTop, Tokens.HeroGradientBottom)),
                    ),
                )
            }
            Text(
                text = "°",
                color = Tokens.HeroGradientTop,
                fontSize = Tokens.HeroDegreeSize,
                lineHeight = Tokens.HeroTempSize,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append(SkyconMap.desc(snapshot.currentSkycon))
                if (today != null) {
                    append("  最低${Units.temp(today.tempMin)}  最高${Units.temp(today.tempMax)}")
                }
            },
            color = sky.textSecondary,
            fontSize = Tokens.SublineSize,
            fontWeight = FontWeight.Medium,
        )
        if (snapshot.forecastKey.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(snapshot.forecastKey, color = sky.textSecondary.copy(alpha = 0.72f), fontSize = 12.sp)
        }
    }
}

/** 小时卡：[实测] 卡高 99dp、列宽 51dp；降水概率单独占一行，无降水也占位，好让温度行对齐。 */
@Composable
private fun HourlyCard(snapshot: WeatherSnapshot, blueTheme: Boolean, zone: java.time.ZoneId, onClick: () -> Unit = {}) {
    val sky = LocalSky.current
    if (snapshot.hourly.isEmpty()) return
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(Tokens.HourlyCardHeight).clickable(onClick = onClick),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
            itemsIndexed(snapshot.hourly.take(24)) { index, point ->
                val hour = Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
                    .apply { timeInMillis = point.time }
                    .get(Calendar.HOUR_OF_DAY)
                Column(
                    modifier = Modifier.width(Tokens.HourlyCellWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = hourlyLabel(index, point.time, zone),
                        color = sky.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(9.dp))
                    WeatherBitmapIcon(
                        assetName = SkyconMap.asset(point.skycon, isNightHour(point.time, zone)),
                        blueTheme = blueTheme,
                        modifier = Modifier.size(27.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    val prob = point.precipitationProbability
                    Text(
                        text = if (prob >= 10) "${prob.roundToInt()}%" else " ",
                        color = Tokens.PrecipCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = Units.temp(point.temperature),
                        color = sky.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            }
            // 内容可滚动时两端渐隐提示（DstIn 只作用于本层，需离屏合成）。
            Canvas(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
            ) {
                val f = 16.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), 0f, f),
                    size = Size(f, size.height),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), size.width - f, size.width),
                    topLeft = Offset(size.width - f, 0f),
                    size = Size(f, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/** 7 日卡：[实测] 行高 48dp，温度条 4dp 且渐变锚在整条轨道上。 */
@Composable
private fun DailyCard(snapshot: WeatherSnapshot, blueTheme: Boolean, zone: java.time.ZoneId) {
    val days = snapshot.daily.take(5)
    if (days.isEmpty()) return
    val gMin = days.minOf { it.tempMin }
    val gMax = days.maxOf { it.tempMax }
    val span = (gMax - gMin).coerceAtLeast(1.0)

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = HomeCardPadding) {
        days.forEachIndexed { index, day ->
            DayRow(day, index, blueTheme, gMin, span, snapshot.currentTemp, zone)
        }
    }
}

@Composable
private fun DayRow(
    day: WeatherSnapshot.DailyPoint,
    index: Int,
    blueTheme: Boolean,
    globalMin: Double,
    span: Double,
    currentTemp: Double,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    val sky = LocalSky.current
    val date = runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull()
    val offset = date?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), it).toInt() }
    val label = when {
        offset == -1 -> "昨天"
        offset == null || offset == 0 -> "今天"
        offset == 1 -> "明天"
        else -> WEEKDAYS[date!!.dayOfWeek.value - 1]
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(53.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = sky.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = date?.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)).orEmpty(),
                color = Tokens.TextTertiary,
                fontSize = 11.sp,
            )
            // 农历：仅显示 ±3 天内，避免信息过载。
            val lunarStr = if ((offset ?: 99) in -3..3) {
                date?.let { d ->
                    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(zone))
                    cal.time = java.util.Date(d.atStartOfDay(zone).toInstant().toEpochMilli())
                    Lunar.lunarText(cal)
                }
            } else null
            if (lunarStr != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = lunarStr,
                    color = Tokens.TextTertiary.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                )
            }
        }
        // 天气图标列：图标中心与温度文字中心同一水平线（48dp 垂直中心），
        // 降水率挂在图标正下方——对齐南风"图标与温度行对齐、降水率占位"的布局。
        // （此前 Column 整体居中，图标中心比温度中心高约 8dp，视觉上"图标浮在上面"。）
        Box(
            modifier = Modifier.width(48.dp).height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            WeatherBitmapIcon(
                assetName = SkyconMap.asset(day.skycon),
                blueTheme = blueTheme,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = if (day.precipitationProbability >= 10) "${day.precipitationProbability.roundToInt()}%" else "",
                color = Tokens.PrecipCyan,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = 19.dp),
            )
        }
        Text(
            text = Units.temp(day.tempMin),
            color = Tokens.TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp),
        )
        Box(modifier = Modifier.weight(1f).height(Tokens.TempBarHeight)) {
            // 南风实图（夜间版）：区间条按全局温度刻度冷暖着色——冷端青绿→暖端黄，
            // 轨道保持灰白，只有填充段带色（昨天 18°~19° 的短条几乎无色即由此来）。
            val cold = Color(0xFF5FD0C8)
            val warm = Color(0xFFF7D154)
            val tMin = ((day.tempMin - globalMin) / span).toFloat().coerceIn(0f, 1f)
            val tMax = ((day.tempMax - globalMin) / span).toFloat().coerceIn(0f, 1f)
            TempBar(
                day, globalMin, span, if (offset == 0) currentTemp else null,
                lerp(cold, warm, tMin), lerp(cold, warm, tMax),
            )
        }
        Text(
            text = Units.temp(day.tempMax),
            color = sky.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.width(38.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun TempBar(
    day: WeatherSnapshot.DailyPoint,
    globalMin: Double,
    span: Double,
    markerTemp: Double?,
    startColor: Color = Tokens.BarStart,
    endColor: Color = Tokens.BarEnd,
) {
    val from = ((day.tempMin - globalMin) / span).toFloat().coerceIn(0f, 1f)
    val to = ((day.tempMax - globalMin) / span).toFloat().coerceIn(0f, 1f)
    val marker = markerTemp?.let { ((it - globalMin) / span).toFloat().coerceIn(0f, 1f) }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val track = size.width
        val barH = size.height
        drawRoundRect(color = Tokens.BarTrack, cornerRadius = CornerRadius(barH / 2f, barH / 2f))
        val x0 = from * track
        val x1 = (to * track).coerceAtLeast(x0 + barH)
        drawRoundRect(
            // 每行自己的冷暖渐变铺满填充段（颜色由 DayRow 按全局温度刻度算好）。
            brush = Brush.horizontalGradient(listOf(startColor, endColor), x0, x1),
            topLeft = Offset(x0, 0f),
            size = Size(x1 - x0, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f),
        )
        if (marker != null) {
            drawCircle(Color.White, radius = barH * 0.95f, center = Offset(marker * track, barH / 2f))
            drawCircle(endColor, radius = barH * 0.55f, center = Offset(marker * track, barH / 2f))
        }
    }
}

/** 生活建议：[实测] 2 行 × 3 列等分卡宽，图标 34dp。图标用南风包里解出的 SF 填充符号。 */
@Composable
private fun LifeAdviceCard(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    val advice = remember(snapshot) { LifeAdviceFrom.of(snapshot) }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = HomeCardPadding,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            advice.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val glyph = rememberSf(item.glyph)
                            if (glyph != null) {
                                Icon(
                                    glyph,
                                    contentDescription = null,
                                    tint = sky.textPrimary,
                                    modifier = Modifier.size(Tokens.AdviceIconSize),
                                )
                            } else {
                                Spacer(Modifier.size(Tokens.AdviceIconSize))
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(item.label, color = sky.textPrimary, fontSize = Tokens.AdviceLabelSize, maxLines = 1)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AqiCard(snapshot: WeatherSnapshot, onClick: () -> Unit = {}) {
    val sky = LocalSky.current
    val rt = snapshot.realtime ?: return
    // 南风布局（夜间实图）：左对齐「38 - 优」一行 → 光谱条 → 一句描述，无刻度、无图标。
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), contentPadding = HomeCardPadding) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (rt.aqi > 0) rt.aqi.toString() else "—",
                    color = sky.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "-  " + SkyconMap.aqiLevel(rt.aqi),
                    color = sky.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (rt.aqi > 0) {
                Spacer(Modifier.height(14.dp))
                SpectrumBar(
                    progress = (rt.aqi / 300.0).coerceIn(0.0, 1.0).toFloat(),
                    colors = listOf(
                        Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFB923C),
                        Color(0xFFF87171), Color(0xFFA78BFA),
                    ),
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "当前空气质量指数 AQI 为${rt.aqi}。",
                    color = Tokens.TextTertiary,
                    fontSize = 12.sp,
                )
            }
            // 六项污染物（PM2.5/PM10/O₃/NO₂/SO₂/CO）不再上首页，
            // 点击本卡进入空气质量详情页查看（AirQualityDetailScreen 已有国标限值横条）。
        }
    }
}

@Composable
private fun SunCard(snapshot: WeatherSnapshot, zone: java.time.ZoneId) {
    val sky = LocalSky.current
    val today = snapshot.daily.firstOrNull() ?: return
    if (today.sunrise.isBlank() && today.sunset.isBlank()) return
    // 日照进度：日出 0 → 日落 1；夜间为 null。
    val sunProgress = remember(snapshot.fetchedAt) {
        SceneBridge.sunProgressOf(snapshot, zone, System.currentTimeMillis())
    }
    // 夜间进度（南风夜间实图）：日落 → 次日日出，月亮骑在夜间时刻的位置上。
    val rise = minutesOf(today.sunrise)
    val set = minutesOf(today.sunset)
    val nightProgress = remember(snapshot.fetchedAt) {
        if (rise == null || set == null || set <= rise) return@remember null
        val now = java.time.LocalTime.ofInstant(
            java.time.Instant.ofEpochMilli(System.currentTimeMillis()), zone,
        )
        val nowMin = now.hour * 60 + now.minute
        val span = 1440 - set + rise
        (((nowMin - set + 1440) % 1440).toFloat() / span).takeIf { it in 0f..1f }
    }
    // 线的语义（南风）：左端=当前时段起点，右端=终点；已走部分暗线、未来亮线。
    // 白天：日出→日落，太阳在进度处；夜间：日落→日出，月牙在进度处。
    val isDay = sunProgress != null
    val leftLabel = if (isDay) "日出" else "日落"
    val rightLabel = if (isDay) "日落" else "日出"
    val leftTime = (if (isDay) today.sunrise else today.sunset).take(5)
    val rightTime = (if (isDay) today.sunset else today.sunrise).take(5)
    val frac = sunProgress ?: nightProgress ?: 0.5f
    val leftIcon = if (isDay) "sunrise" else "sunset"
    val rightIcon = if (isDay) "sunset" else "sunrise"

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = HomeCardPadding) {
        Column {
            // 顶部标注：图标在外侧、文字在内侧（南风对称布局）
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rememberLucide(leftIcon)?.let {
                        Icon(it, contentDescription = null, tint = Tokens.TextTertiary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(leftLabel, color = Tokens.TextTertiary, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rightLabel, color = Tokens.TextTertiary, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    rememberLucide(rightIcon)?.let {
                        Icon(it, contentDescription = null, tint = Tokens.TextTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 白色细线：已过段暗、未来段亮；太阳/月牙骑在分界处（无圆底托，南风就是裸图标）。
            // 修复"白色宽白条"：线减细（3dp→2dp），未来段 0.90 纯白在白天玻璃卡上像一道
            // 横贯的亮白条——压到 0.55，保持"未来比已走过亮"的进度语义但不刺眼。
            Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                val lineY = size.height / 2f
                val lineH = 2.dp.toPx()
                val inset = 4.dp.toPx()
                val x0 = inset
                val x1 = size.width - inset
                val cx = x0 + (x1 - x0) * frac
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(x0, lineY - lineH / 2f),
                    size = Size(cx - x0, lineH),
                    cornerRadius = CornerRadius(lineH / 2f, lineH / 2f),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(cx, lineY - lineH / 2f),
                    size = Size(x1 - cx, lineH),
                    cornerRadius = CornerRadius(lineH / 2f, lineH / 2f),
                )
                if (isDay) {
                    // 太阳：暖白亮核
                    drawCircle(Color(0x55FFD98A), radius = 12.dp.toPx(), center = Offset(cx, lineY))
                    drawCircle(Color(0xFFFFE9A8), radius = 5.5.dp.toPx(), center = Offset(cx, lineY))
                    drawCircle(Color(0xFFFFFFFF), radius = 3.2.dp.toPx(), center = Offset(cx, lineY))
                } else {
                    // 月牙：白圆 + 偏移暗圆遮出弧；伴两颗小星
                    drawCircle(Color(0xFFF2F6FC), radius = 8.dp.toPx(), center = Offset(cx, lineY))
                    drawCircle(Color(0xFF16294A), radius = 6.6.dp.toPx(), center = Offset(cx + 3.4.dp.toPx(), lineY - 2.2.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.95f), radius = 1.4.dp.toPx(), center = Offset(cx + 9.dp.toPx(), lineY - 7.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.75f), radius = 0.9.dp.toPx(), center = Offset(cx + 12.dp.toPx(), lineY - 2.dp.toPx()))
                }
            }
            Spacer(Modifier.height(6.dp))
            // 时间数字放线下（南风布局，空间感更舒展）
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(leftTime, color = sky.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(rightTime, color = sky.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** "HH:mm" → 当天第几分钟；解析失败返回 null。 */
private fun minutesOf(s: String): Int? {
    val parts = s.take(5).split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}

/**
 * 逐小时降水柱状卡（对应南风 MinutelyPrecipitationCard / PrecipBarsCanvas 的
 * 小时粒度版——分钟级曲线要等彩云 BYOK，这里先用 hourly.precipitation mm 画柱）。
 * 未来 24 小时全程无降水时不渲染（南风也是"没雨就不占屏"）。
 */
@Composable
private fun PrecipCard(snapshot: WeatherSnapshot, onClick: () -> Unit = {}) {
    val sky = LocalSky.current
    val hours = remember(snapshot.fetchedAt) {
        snapshot.hourly.take(24)
    }
    if (hours.isEmpty() || hours.all { it.precipitationMm < 0.05 }) return
    val maxMm = hours.maxOf { it.precipitationMm }.coerceAtLeast(1.0)
    val zone = java.time.ZoneId.systemDefault()
    GlassCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        contentPadding = HomeCardPadding,
    ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            rememberLucide("umbrella")?.let {
                                Icon(it, contentDescription = null, tint = sky.textSecondary, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("未来 24 小时降水", color = sky.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            val peak = hours.maxOf { it.precipitationMm }
                            Text(
                                "峰值 ${Units.precipitationText(peak)}",
                                color = Tokens.PrecipCyan, fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
                            val n = hours.size
                            val slot = size.width / n
                            val barW = slot * 0.55f
                            hours.forEachIndexed { i, point ->
                                val mm = point.precipitationMm
                                if (mm >= 0.05) {
                                    val barH = ((mm / maxMm * size.height * 0.86f).toFloat()).coerceAtLeast(3f)
                                    drawRoundRect(
                                        color = Tokens.PrecipCyan.copy(alpha = 0.55f + 0.35f * (mm / maxMm).toFloat()),
                                        topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - barH),
                                        size = Size(barW, barH),
                                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                                    )
                                }
                            }
                            // 底线
                            drawLine(
                                color = Tokens.TextTertiary.copy(alpha = 0.35f),
                                start = Offset(0f, size.height), end = Offset(size.width, size.height),
                                strokeWidth = 1f,
                            )
                        }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                // 首尾钟点标注
                val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                listOf(0, hours.size - 1).forEach { idx ->
                    val t = hours[idx].time
                    Text(
                        java.time.Instant.ofEpochMilli(t).atZone(zone).format(fmt),
                        color = Tokens.TextTertiary, fontSize = 10.sp,
                        textAlign = if (idx == 0) TextAlign.Start else TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPlaceholder(cityName: String, message: String?, onRetry: () -> Unit) {
    val sky = LocalSky.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(cityName, color = sky.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text("等待数据…", color = sky.textSecondary, fontSize = 16.sp)
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it, color = Tokens.TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "点击重试",
            color = sky.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyState(onOpenSearch: () -> Unit, message: String?) {
    val sky = LocalSky.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有城市", color = sky.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text(
            "搜索并添加一个城市，或允许定位获取当前天气",
            color = sky.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Tokens.TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "添加城市",
            color = sky.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/** 南风的短写：周四 / 周五，不是 DateTimeFormatter 的「星期四」。 */
private val WEEKDAYS = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/**
 * 首页信息卡统一内边距：左右 14 / 上下 12。
 * 此前各卡各定（AQI 16、7日 10、日照/降水/详情 14、生活建议 22、小时卡 0），
 * 卡与卡之间「内容→边缘」的留白 0~22dp 不等，看着就是间隔不一致、底部留白过大。
 */
private val HomeCardPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)

/** 跨零点后那一格照南风写「明天」，其余仍是钟点；再往后才是周几。 */
private fun hourlyLabel(index: Int, epochMs: Long, zone: java.time.ZoneId): String {
    if (index == 0) return "现在"
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val day = java.time.LocalDate.ofInstant(instant, zone)
    val time = java.time.LocalTime.ofInstant(instant, zone)
    val clock = "%02d:00".format(time.hour)
    return when (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), day)) {
        0L -> clock
        1L -> if (time.hour == 0) "明天" else clock
        else -> WEEKDAYS[day.dayOfWeek.value - 1]
    }
}

private fun isNightHour(epochMs: Long, zone: java.time.ZoneId): Boolean {
    val h = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), zone).hour
    return h !in 6..18
}

/**
 * 气象详情：体感 / 风速 / 湿度 / 气压 / 能见度 / 紫外线。
 *
 * 这一卡是风速、气压、能见度三个单位选项唯一的显示出口——没有它，
 * 单位设置里那几行就只是存了个数而已。缺值的格子显示「—」，不填假数。
 */
@Composable
private fun DetailCard(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    val rt = snapshot.realtime ?: return
    val tiles = listOf(
        ("体感" to if (rt.apparentTemperature > -900) Units.temp(rt.apparentTemperature) else "—") to "thermometer-ios",
        ("风速" to if (rt.windSpeed > 0) Units.windText(rt.windSpeed) else "—") to "wind-ios",
        ("湿度" to "${(rt.humidity * 100).roundToInt()}%") to "humidity-ios",
        ("气压" to if (rt.pressureHpa > 0) Units.pressureText(rt.pressureHpa) else "—") to "pressure-ios",
        ("能见度" to if (rt.visibilityKm > 0) Units.visibilityText(rt.visibilityKm) else "—") to "visibility-ios",
        ("紫外线" to if (rt.uvIndex > 0) rt.uvIndex.roundToInt().toString() else "—") to "uv-sun-ios",
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = HomeCardPadding,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (pair, icon) ->
                        val (label, value) = pair
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            rememberLucide(icon)?.let {
                                Icon(it, contentDescription = null, tint = sky.textSecondary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            // 值行固定高度垂直居中：三列基线一致（长文本自动缩号不破行高）。
                            Text(
                                value,
                                color = sky.textPrimary,
                                fontSize = if (value.length > 5) 14.sp else 17.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                modifier = Modifier.height(22.dp).wrapContentHeight(align = Alignment.CenterVertically),
                            )
                            Text(label, color = Tokens.TextTertiary, fontSize = 12.sp)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 横向光谱条 + 位置标记点（南风 AqiSpectrumBar / UvSpectrumBar 的通用形态）。 */
@Composable
internal fun SpectrumBar(
    progress: Float,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        drawRoundRect(
            brush = Brush.horizontalGradient(colors),
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        val x = (w * progress).coerceIn(h, w - h)
        drawCircle(Color.White, radius = h * 0.75f, center = Offset(x, h / 2f))
        drawCircle(Color(0xFF3A4254), radius = h * 0.34f, center = Offset(x, h / 2f))
    }
}
