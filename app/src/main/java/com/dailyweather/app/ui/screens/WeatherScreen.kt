package com.dailyweather.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.dailyweather.app.scene.TopProgressiveScrim
import com.dailyweather.app.scene.WeatherEffectOverlay
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.SkyconMap
import com.dailyweather.app.ui.components.WeatherBitmapIcon
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.components.rememberSf
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.ui.theme.SkyPalette
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.viewmodel.WeatherViewModel
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
    val scene = remember(snapshot, cityZone) { SceneBridge.stateFor(snapshot, cityZone) }
    val sky = SkyPalette.of(scene)

    CompositionLocalProvider(
        LocalSky provides sky,
        // 「天气动效」开关 + 省电模式 → 整个粒子层的绘制开关。
        LocalWeatherEffectDrawEnabled provides (motion && !powerSave),
        LocalEffectQuality provides if (powerSave) EffectQuality.Low else EffectQuality.High,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                WeatherEffectOverlay(faded, Modifier.matchParentSize())
            }
            TopProgressiveScrim(Modifier.matchParentSize())

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text(
                        text = city?.name ?: "添加城市",
                        color = sky.textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenCityList)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (vm.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = sky.textSecondary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            HeaderIcon("refresh-cw", sky.textSecondary, onClick = onLocate)
                        }
                        HeaderIcon("more-horizontal", sky.textSecondary, onClick = onOpenSettings)
                    }
                }

                val snap = snapshot
                when {
                    snap != null -> {
                        HeroSection(snap)
                        Spacer(Modifier.height(14.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.ScreenMargin),
                            verticalArrangement = Arrangement.spacedBy(Tokens.CardGap),
                        ) {
                            cards.forEach { card ->
                                when (card) {
                                    HomeCardId.HOURLY -> HourlyCard(snap, blueTheme, cityZone) { onOpenDetail(com.dailyweather.app.DetailKind.HOURLY) }
                                    HomeCardId.DAILY -> DailyCard(snap, blueTheme, cityZone)
                                    HomeCardId.PRECIP -> PrecipCard(snap) { onOpenDetail(com.dailyweather.app.DetailKind.MINUTELY) }
                                    HomeCardId.RADAR -> RadarCard(snap)
                                    HomeCardId.LIFE -> LifeAdviceCard(snap)
                                    HomeCardId.AQI -> AqiCard(snap) { onOpenDetail(com.dailyweather.app.DetailKind.AIR_QUALITY) }
                                    HomeCardId.SUN -> SunCard(snap, cityZone)
                                    HomeCardId.DETAIL -> DetailCard(snap)
                                    else -> Unit
                                }
                            }
                            vm.message?.let { msg ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(msg, color = sky.textSecondary, fontSize = 13.sp)
                                }
                            }
                            source.takeIf { it.isNotBlank() }?.let { src ->
                                Spacer(Modifier.height(18.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0x29FFFFFF))
                    .border(1.dp, Color(0x4DFFFFFF), CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                rememberLucide("more-horizontal")?.let {
                    Icon(it, contentDescription = "更多", tint = sky.textPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(name: String, tint: Color, onClick: () -> Unit) {
    val icon = rememberLucide(name)
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
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
        modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = Units.tempValue(snapshot.currentTemp).roundToInt().toString(),
                style = TextStyle(
                    fontSize = Tokens.HeroTempSize,
                    lineHeight = 96.sp,
                    fontWeight = FontWeight.Medium,
                    brush = Brush.verticalGradient(listOf(Tokens.HeroGradientTop, Tokens.HeroGradientBottom)),
                ),
            )
            Text(
                text = "°",
                color = Tokens.HeroGradientTop,
                fontSize = Tokens.HeroDegreeSize,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 12.dp, start = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(8.dp))
            Text(snapshot.forecastKey, color = Tokens.TextTertiary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(26.dp))
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
                        modifier = Modifier.padding(top = 11.dp),
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

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
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
        date != null -> WEEKDAYS[date.dayOfWeek.value - 1]
        else -> day.date
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
        }
        Column(modifier = Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WeatherBitmapIcon(
                assetName = SkyconMap.asset(day.skycon),
                blueTheme = blueTheme,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = if (day.precipitationProbability >= 10) "${day.precipitationProbability.roundToInt()}%" else " ",
                color = Tokens.PrecipCyan,
                fontSize = 10.sp,
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
            // 南风的区间条按温度冷暖着色：全局刻度上冷端青绿、暖端黄。
            TempBar(
                day, globalMin, span, if (offset == 0) currentTemp else null,
                tempBarColor(day.tempMin, globalMin, span), tempBarColor(day.tempMax, globalMin, span),
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

/** 全周同一把温度刻度：冷端青绿 → 暖端黄（对齐南风日卡区间条的配色逻辑）。 */
private fun tempBarColor(temp: Double, globalMin: Double, span: Double): Color =
    androidx.compose.ui.graphics.lerp(
        Color(0xFF52C7A2), Color(0xFFF6D35B),
        ((temp - globalMin) / span).coerceIn(0.0, 1.0).toFloat(),
    )

/**
 * 台风雷达卡：[实测] 高 109dp，左侧标题 + 短临描述，右侧 93×72 圆角缩略图。
 *
 * 缩略图是程序化占位（网格 + 一条台风路径），雷达帧还没进快照；接上后只换位图、不改几何。
 */
@Composable
private fun RadarCard(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(Tokens.RadarCardHeight),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("台风雷达", color = sky.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(11.dp))
                Text(
                    text = snapshot.minutelyDesc.ifBlank { "雷达数据待接入" },
                    color = Tokens.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(12.dp))
            RadarThumb(
                modifier = Modifier
                    .width(Tokens.RadarThumbWidth)
                    .height(Tokens.RadarThumbHeight)
                    .clip(RoundedCornerShape(Tokens.RadarThumbRadius)),
            )
        }
    }
}

@Composable
private fun RadarThumb(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0x5540576A))) {
        val w = size.width
        val h = size.height
        val grid = Color.White.copy(alpha = 0.055f)
        for (i in 1..3) {
            drawLine(grid, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1.dp.toPx())
        }
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.85f)
            cubicTo(w * 0.40f, h * 0.72f, w * 0.55f, h * 0.55f, w * 0.89f, h * 0.13f)
        }
        drawPath(path, Color.White.copy(alpha = 0.23f), style = Stroke(width = 2.dp.toPx()))
        val head = Offset(w * 0.78f, h * 0.28f)
        drawCircle(Color.White.copy(alpha = 0.13f), radius = w * 0.13f, center = head)
        drawCircle(Color.White.copy(alpha = 0.55f), radius = 3.dp.toPx(), center = head)
    }
}

/** 生活建议：[实测] 2 行 × 3 列等分卡宽，图标 34dp。图标用南风包里解出的 SF 填充符号。 */
@Composable
private fun LifeAdviceCard(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    val advice = remember(snapshot) { LifeAdviceFrom.of(snapshot) }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (rt.aqi > 0) rt.aqi.toString() else "—",
                    color = sky.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.width(64.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = SkyconMap.aqiLevel(rt.aqi),
                        color = sky.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = rt.airQualityDesc.ifBlank { "PM2.5 ${rt.pm25.roundToInt()} · 空气数据待接入" },
                        color = Tokens.TextTertiary,
                        fontSize = 12.sp,
                    )
                }
                rememberLucide("leaf")?.let {
                    Icon(it, contentDescription = null, tint = Color(0xFF8FD08F), modifier = Modifier.size(20.dp))
                }
            }
            // AQI 光谱条（对应南风 AqiSpectrumBar）：绿→黄→橙→红→紫，0–300 刻度。
            if (rt.aqi > 0) {
                Spacer(Modifier.height(12.dp))
                SpectrumBar(
                    progress = (rt.aqi / 300.0).coerceIn(0.0, 1.0).toFloat(),
                    colors = listOf(
                        Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFB923C),
                        Color(0xFFF87171), Color(0xFFA78BFA),
                    ),
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("0", "50", "100", "150", "200", "300").forEach {
                        Text(
                            it, color = Tokens.TextTertiary, fontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // 全污染物一行（彩云/小米都带六项；0 = 上游没给，如实显示「—」）。
            val pollutants = listOf(
                "PM2.5" to rt.pm25, "PM10" to rt.pm10, "O₃" to rt.o3,
                "NO₂" to rt.no2, "SO₂" to rt.so2, "CO" to rt.co,
            )
            if (pollutants.any { it.second > 0.0 }) {
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    pollutants.forEach { (label, value) ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (value > 0.0) "${value.roundToInt()}" else "—",
                                color = sky.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(label, color = Tokens.TextTertiary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SunCard(snapshot: WeatherSnapshot, zone: java.time.ZoneId) {
    val sky = LocalSky.current
    val today = snapshot.daily.firstOrNull() ?: return
    if (today.sunrise.isBlank() && today.sunset.isBlank()) return
    // 日照进度：日出 0 → 日落 1；夜间为 null（太阳画到地平线下）。
    val progress = remember(snapshot.fetchedAt) {
        SceneBridge.sunProgressOf(snapshot, zone, System.currentTimeMillis())
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        rememberLucide("sunrise")?.let {
                            Icon(it, contentDescription = null, tint = Tokens.BarEnd, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text("日出", color = Tokens.TextTertiary, fontSize = 12.sp)
                    }
                    Text(today.sunrise.take(5), color = sky.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        rememberLucide("sunset")?.let {
                            Icon(it, contentDescription = null, tint = Tokens.BarEnd, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text("日落", color = Tokens.TextTertiary, fontSize = 12.sp)
                    }
                    Text(today.sunset.take(5), color = sky.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 太阳弧线（对应南风 HorizontalSunProgress）：白天太阳在弧上，夜间藏到地平线下。
            Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
                val w = size.width
                val h = size.height
                val horizon = h * 0.88f
                // 半径取宽高较小约束，保证弧顶不裁出画布。
                val r = minOf(w * 0.42f, h * 0.74f)
                val cx = w / 2f
                // 地平线（虚线效果用细实线代替）
                drawLine(
                    color = Tokens.TextTertiary.copy(alpha = 0.4f),
                    start = Offset(0f, horizon), end = Offset(w, horizon),
                    strokeWidth = 1f,
                )
                // 弧轨道
                val arcTop = androidx.compose.ui.geometry.Rect(
                    cx - r, horizon - r, cx + r, horizon + r,
                )
                drawArc(
                    color = Tokens.TextTertiary.copy(alpha = 0.35f),
                    startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = arcTop.topLeft, size = arcTop.size,
                    style = Stroke(width = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))),
                )
                val p = progress
                if (p != null) {
                    // 太阳在弧上：progress 0 → 180°，1 → 0°。
                    val angle = 180f * (1f - p)
                    val rad = angle * PI.toFloat() / 180f
                    val sx = cx + cos(rad) * r
                    val sy = horizon - sin(rad) * r
                    drawCircle(Color(0x55FFD98A), radius = 16f, center = Offset(sx, sy))
                    drawCircle(Color(0xFFFFD35C), radius = 7f, center = Offset(sx, sy))
                    drawCircle(Color(0xFFFFF3CE), radius = 4.2f, center = Offset(sx, sy))
                } else {
                    // 夜间：月亮弧点画在地平线下方中央。
                    drawCircle(
                        Color(0xFF9FB6D8).copy(alpha = 0.8f), radius = 5f,
                        center = Offset(cx, horizon + 6f),
                    )
                }
            }
        }
    }
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
        contentPadding = PaddingValues(14.dp),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 18.dp, bottom = 18.dp,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (pair, icon) ->
                        val (label, value) = pair
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            rememberLucide(icon)?.let {
                                Icon(it, contentDescription = null, tint = sky.textSecondary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(value, color = sky.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(label, color = Tokens.TextTertiary, fontSize = 12.sp)
                            // 迷你可视化：风罗盘针 / 气压刻度 / 紫外线光谱（对应南风
                            // WindCompass / PressureGauge / UvSpectrumBar 的格内缩版）。
                            when (icon) {
                                "wind-ios" -> if (rt.windSpeed > 0) {
                                    WindNeedle(
                                        directionDeg = rt.windDirection,
                                        modifier = Modifier.padding(top = 6.dp).size(26.dp),
                                    )
                                }
                                "pressure-ios" -> if (rt.pressureHpa > 0) {
                                    SpectrumBar(
                                        progress = ((rt.pressureHpa - 970.0) / 80.0).coerceIn(0.0, 1.0).toFloat(),
                                        colors = listOf(Color(0xFF5B8CFF), Color(0xFF4ADE80), Color(0xFFFBBF24)),
                                        modifier = Modifier.padding(top = 10.dp).width(52.dp).height(4.dp),
                                    )
                                }
                                "uv-sun-ios" -> if (rt.uvIndex > 0) {
                                    SpectrumBar(
                                        progress = (rt.uvIndex / 11.0).coerceIn(0.0, 1.0).toFloat(),
                                        colors = listOf(
                                            Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFB923C),
                                            Color(0xFFF87171), Color(0xFFA78BFA),
                                        ),
                                        modifier = Modifier.padding(top = 10.dp).width(52.dp).height(4.dp),
                                    )
                                }
                                else -> Unit
                            }
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

/** 风向罗盘：圆圈 + 指针指向风的来向（windDirection，0=北）。 */
@Composable
private fun WindNeedle(directionDeg: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = r, center = c, style = Stroke(1.5f))
        drawCircle(color = Color.White.copy(alpha = 0.4f), radius = r * 0.10f, center = c)
        // N 刻度点
        drawCircle(
            color = Color.White.copy(alpha = 0.8f), radius = 1.6f,
            center = Offset(c.x, c.y - r + 2f),
        )
        val a = (directionDeg.toFloat() - 90f) * PI.toFloat() / 180f
        drawLine(
            color = Color.White,
            start = Offset(c.x + cos(a) * r * 0.18f, c.y + sin(a) * r * 0.18f),
            end = Offset(c.x + cos(a) * r * 0.78f, c.y + sin(a) * r * 0.78f),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
        )
        // 箭头尖
        val tip = Offset(c.x + cos(a) * r * 0.78f, c.y + sin(a) * r * 0.78f)
        val leftA = a + PI.toFloat() * 0.85f
        drawLine(
            color = Color.White,
            start = tip,
            end = Offset(tip.x + cos(leftA) * r * 0.22f, tip.y + sin(leftA) * r * 0.22f),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
        )
    }
}
