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
import kotlin.math.roundToInt

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
    onLocate: () -> Unit,
) {
    val cities by vm.cities.collectAsState()
    val snapshots by vm.snapshotCache.collectAsState()
    val city = vm.cityFor(vm.selectedCityId)
        ?: cities.firstOrNull { it.isCurrentLocation }
        ?: cities.firstOrNull()
    val snapshot = snapshots[vm.selectedCityId] ?: snapshots[city?.id]
    val cards by vm.homeCards.collectAsState()
    var source by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(vm.selectedCityId) { source = vm.sourceOf(vm.selectedCityId) }

    val motion by vm.settingFlow(WeatherViewModel.SettingKey.MOTION).collectAsState(true)
    val scene = remember(snapshot) { SceneBridge.stateFor(snapshot) }
    val sky = SkyPalette.of(scene)

    CompositionLocalProvider(LocalSky provides sky) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedSkyGradient(scene, Modifier.matchParentSize())
            WeatherEffectOverlay(scene, Modifier.matchParentSize())
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
                                    HomeCardId.HOURLY -> HourlyCard(snap, blueTheme)
                                    HomeCardId.DAILY -> DailyCard(snap, blueTheme)
                                    HomeCardId.RADAR -> RadarCard(snap)
                                    HomeCardId.LIFE -> LifeAdviceCard(snap)
                                    HomeCardId.AQI -> AqiCard(snap)
                                    HomeCardId.SUN -> SunCard(snap)
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
private fun HourlyCard(snapshot: WeatherSnapshot, blueTheme: Boolean) {
    val sky = LocalSky.current
    if (snapshot.hourly.isEmpty()) return
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(Tokens.HourlyCardHeight),
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            itemsIndexed(snapshot.hourly.take(24)) { index, point ->
                val hour = Calendar.getInstance().apply { timeInMillis = point.time }
                    .get(Calendar.HOUR_OF_DAY)
                Column(
                    modifier = Modifier.width(Tokens.HourlyCellWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = hourlyLabel(index, point.time),
                        color = sky.textSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 11.dp),
                    )
                    Spacer(Modifier.height(9.dp))
                    WeatherBitmapIcon(
                        assetName = SkyconMap.asset(point.skycon, isNightHour(point.time)),
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
private fun DailyCard(snapshot: WeatherSnapshot, blueTheme: Boolean) {
    val days = snapshot.daily.take(5)
    if (days.isEmpty()) return
    val gMin = days.minOf { it.tempMin }
    val gMax = days.maxOf { it.tempMax }
    val span = (gMax - gMin).coerceAtLeast(1.0)

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        days.forEachIndexed { index, day ->
            DayRow(day, index, blueTheme, gMin, span, snapshot.currentTemp)
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
) {
    val sky = LocalSky.current
    val date = runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull()
    val offset = date?.let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), it).toInt() }
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
            TempBar(day, globalMin, span, if (offset == 0) currentTemp else null)
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
            // 渐变铺满整条轨道，填充只是取其中一段——所以同一 x 位置在不同行的颜色一致。
            brush = Brush.horizontalGradient(listOf(Tokens.BarStart, Tokens.BarEnd), 0f, track),
            topLeft = Offset(x0, 0f),
            size = Size(x1 - x0, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f),
        )
        if (marker != null) {
            drawCircle(Color.White, radius = barH * 0.95f, center = Offset(marker * track, barH / 2f))
            drawCircle(Tokens.BarEnd, radius = barH * 0.55f, center = Offset(marker * track, barH / 2f))
        }
    }
}

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
private fun AqiCard(snapshot: WeatherSnapshot) {
    val sky = LocalSky.current
    val rt = snapshot.realtime ?: return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
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
    }
}

@Composable
private fun SunCard(snapshot: WeatherSnapshot) {
    val today = snapshot.daily.firstOrNull() ?: return
    if (today.sunrise.isBlank() && today.sunset.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.CardGap)) {
        SunTile("日出", today.sunrise, "sunrise", Modifier.weight(1f))
        SunTile("日落", today.sunset, "sunset", Modifier.weight(1f))
    }
}

@Composable
private fun SunTile(title: String, value: String, icon: String, modifier: Modifier) {
    val sky = LocalSky.current
    GlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            rememberLucide(icon)?.let {
                Icon(it, contentDescription = null, tint = Tokens.BarEnd, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = Tokens.TextTertiary, fontSize = 12.sp)
                Text(value.take(5), color = sky.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
private fun hourlyLabel(index: Int, epochMs: Long): String {
    if (index == 0) return "现在"
    val instant = java.time.Instant.ofEpochMilli(epochMs)
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.LocalDate.ofInstant(instant, zone)
    val time = java.time.LocalTime.ofInstant(instant, zone)
    val clock = "%02d:00".format(time.hour)
    return when (java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(zone), day)) {
        0L -> clock
        1L -> if (time.hour == 0) "明天" else clock
        else -> WEEKDAYS[day.dayOfWeek.value - 1]
    }
}

private fun isNightHour(epochMs: Long): Boolean {
    val h = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()).hour
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
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
