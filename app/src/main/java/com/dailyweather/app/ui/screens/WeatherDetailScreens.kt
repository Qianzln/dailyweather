package com.dailyweather.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.data.Units
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.Tokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 首页卡片的详情子屏（对齐南风的 HourlyDetailScreen / AirQualityDetailScreen /
 * MinutelyPrecipitationCard 全屏形态）：共用一个玻璃底 + 返回条的脚手架。
 */

private val DetailBack = Color(0xFFE8EEF9)

@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B2230))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                rememberLucide("chevron-left")?.let {
                    Icon(it, contentDescription = "返回", tint = DetailBack)
                }
            }
            Column {
                Text(title, color = DetailBack, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Tokens.TextTertiary, fontSize = 12.sp)
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// 逐小时：温度曲线 + 逐时降水概率 + 逐时 AQI（小米源）
// ---------------------------------------------------------------------------

@Composable
fun HourlyDetailScreen(snapshot: WeatherSnapshot, zone: ZoneId, onBack: () -> Unit) {
    val hours = snapshot.hourly.take(24)
    DetailScaffold("逐小时预报", "${hours.size} 小时 · 点击首页逐小时卡进入", onBack) {
        if (hours.isEmpty()) {
            Text("暂无逐小时数据", color = Tokens.TextSecondary, fontSize = 14.sp)
            return@DetailScaffold
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rememberLucide("thermometer")?.let {
                        Icon(it, contentDescription = null, tint = Tokens.TextTertiary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("温度曲线", color = Tokens.TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                TempCurve(hours, Modifier.fillMaxWidth().height(140.dp))
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rememberLucide("umbrella")?.let {
                        Icon(it, contentDescription = null, tint = Tokens.PrecipCyan, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("逐小时降水概率", color = Tokens.TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                ProbBars(hours, Modifier.fillMaxWidth().height(80.dp))
                Spacer(Modifier.height(6.dp))
                HourTicks(hours, zone)
            }
        }
        val aqiHours = hours.filter { it.aqi > 0 }
        if (aqiHours.size >= 3) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        rememberLucide("leaf")?.let {
                            Icon(it, contentDescription = null, tint = Color(0xFF8FD08F), modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("逐小时空气质量（AQI）", color = Tokens.TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    AqiCurve(aqiHours, Modifier.fillMaxWidth().height(110.dp))
                }
            }
        }
    }
}

/** 温度曲线：平滑路径 + 渐变填充 + 首尾与极值标注。 */
@Composable
private fun TempCurve(hours: List<WeatherSnapshot.HourlyPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val temps = hours.map { it.temperature }
        val minT = temps.min()
        val maxT = temps.max()
        val span = (maxT - minT).coerceAtLeast(1.0)
        val top = 26f
        val bottom = size.height - 22f
        fun pt(i: Int): Offset = Offset(
            x = i / (hours.size - 1).toFloat() * size.width,
            y = (bottom - ((temps[i] - minT) / span).toFloat() * (bottom - top)).toFloat(),
        )
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until hours.size) {
                val a = pt(i - 1)
                val b = pt(i)
                quadraticBezierTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            lineTo(pt(hours.size - 1).x, pt(hours.size - 1).y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, bottom)
            lineTo(0f, bottom)
            close()
        }
        drawPath(fill, brush = Brush.verticalGradient(listOf(Color(0x55FFD35C), Color(0x00FFD35C))))
        drawPath(path, color = Color(0xFFFFD35C), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        // 极值标注
        val iMin = temps.indexOf(minT)
        val iMax = temps.indexOf(maxT)
        listOf(iMin to minT, iMax to maxT).forEach { (i, t) ->
            val p = pt(i)
            drawCircle(Color(0xFFFFD35C), radius = 3.5f, center = p)
            drawCircle(Color(0xFF1B2230), radius = 1.8f, center = p)
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("${t.roundToInt()}°", p.x, p.y - 10f, paint)
            }
        }
    }
}

/** 逐时降水概率柱。 */
@Composable
private fun ProbBars(hours: List<WeatherSnapshot.HourlyPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val slot = size.width / hours.size
        val barW = slot * 0.5f
        hours.forEachIndexed { i, point ->
            val p = (point.precipitationProbability / 100.0).toFloat().coerceIn(0f, 1f)
            if (p > 0.02f) {
                val barH = p * (size.height - 4f)
                drawRoundRect(
                    color = Tokens.PrecipCyan.copy(alpha = 0.45f + 0.4f * p),
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }
        drawLine(
            color = Tokens.TextTertiary.copy(alpha = 0.3f),
            start = Offset(0f, size.height), end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    }
}

/** 逐时 AQI 折线（小米源）。 */
@Composable
private fun AqiCurve(hours: List<WeatherSnapshot.HourlyPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val vals = hours.map { it.aqi.toFloat() }
        val maxV = (vals.max() * 1.1f).coerceAtLeast(50f)
        val top = 14f
        val bottom = size.height - 6f
        val pts = vals.mapIndexed { i, v ->
            Offset(
                i / (vals.size - 1).toFloat() * size.width,
                bottom - (v / maxV) * (bottom - top),
            )
        }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color = Color(0xFF8FD08F), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        pts.forEachIndexed { i, p ->
            drawCircle(Color(0xFF8FD08F), radius = 2.6f, center = p)
            if (i % 6 == 0) {
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(255, 154, 168, 196)
                        textSize = 20f
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText("${vals[i].roundToInt()}", p.x, p.y - 8f, paint)
                }
            }
        }
    }
}

@Composable
private fun HourTicks(hours: List<WeatherSnapshot.HourlyPoint>, zone: ZoneId) {
    val fmt = DateTimeFormatter.ofPattern("HH时")
    Row(modifier = Modifier.fillMaxWidth()) {
        hours.forEachIndexed { i, point ->
            Text(
                if (i % 6 == 0) Instant.ofEpochMilli(point.time).atZone(zone).format(fmt) else " ",
                color = Tokens.TextTertiary,
                fontSize = 9.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 空气质量详情
// ---------------------------------------------------------------------------

@Composable
fun AirQualityDetailScreen(snapshot: WeatherSnapshot, onBack: () -> Unit) {
    val rt = snapshot.realtime
    DetailScaffold("空气质量", snapshot.realtime?.let { "AQI ${it.aqi}" } ?: "暂无数据", onBack) {
        if (rt == null) {
            Text("暂无空气质量数据", color = Tokens.TextSecondary, fontSize = 14.sp)
            return@DetailScaffold
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rt.aqi > 0) rt.aqi.toString() else "—",
                        color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(com.dailyweather.app.ui.components.SkyconMap.aqiLevel(rt.aqi), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(rt.airQualityDesc.ifBlank { "空气实况" }, color = Tokens.TextTertiary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                SpectrumBar(
                    progress = (rt.aqi / 300.0).coerceIn(0.0, 1.0).toFloat(),
                    colors = listOf(
                        Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFFB923C),
                        Color(0xFFF87171), Color(0xFFA78BFA),
                    ),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("0", "50", "100", "150", "200", "300").forEach {
                        Text(it, color = Tokens.TextTertiary, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("六项污染物", color = Tokens.TextSecondary, fontSize = 13.sp)
                listOf(
                    "PM2.5" to rt.pm25 to 35.0,
                    "PM10" to rt.pm10 to 70.0,
                    "O₃" to rt.o3 to 160.0,
                    "NO₂" to rt.no2 to 80.0,
                    "SO₂" to rt.so2 to 150.0,
                    "CO" to rt.co to 4.0,
                ).forEach { (pair, ref) ->
                    val (label, value) = pair
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(label, color = Color.White, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (value > 0) "${value.roundToInt()} μg/m³" else "—",
                                color = Tokens.TextSecondary, fontSize = 13.sp,
                            )
                        }
                        if (value > 0) {
                            Spacer(Modifier.height(4.dp))
                            SpectrumBar(
                                progress = (value / ref).coerceIn(0.0, 1.0).toFloat(),
                                colors = listOf(Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFF87171)),
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        }
                    }
                }
                Text(
                    "横条按国标一级限值（PM2.5 35 / PM10 70 / O₃ 160 / NO₂ 80 / SO₂ 150 / CO 4）比例显示",
                    color = Tokens.TextTertiary, fontSize = 10.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分钟级降水趋势
// ---------------------------------------------------------------------------

@Composable
fun MinutelyPrecipScreen(snapshot: WeatherSnapshot, onBack: () -> Unit) {
    val minutely = snapshot.minutely
    DetailScaffold("分钟级降水", "未来两小时 · 每分钟一个点", onBack) {
        if (minutely == null) {
            Text("当前数据源没有分钟级降水数据", color = Tokens.TextSecondary, fontSize = 14.sp)
            return@DetailScaffold
        }
        if (minutely.description.isNotBlank()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(minutely.description, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Spacer(Modifier.height(4.dp))
                MinutelyBars(minutely.precipitation2h, Modifier.fillMaxWidth().height(120.dp))
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("现在", color = Tokens.TextTertiary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("30分", color = Tokens.TextTertiary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("1小时", color = Tokens.TextTertiary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("90分", color = Tokens.TextTertiary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("2小时", color = Tokens.TextTertiary, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

/** 分钟级降水柱：120 个值全画，宽度自适应。 */
@Composable
private fun MinutelyBars(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = (values.max().coerceAtLeast(0.1)).toFloat()
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            val f = (v / max).toFloat()
            if (f > 0.02f) {
                val barH = f * (size.height - 4f)
                drawRoundRect(
                    color = Tokens.PrecipCyan.copy(alpha = 0.45f + 0.4f * f),
                    topLeft = Offset(i * slot, size.height - barH),
                    size = Size((slot * 0.75f).coerceAtLeast(1f), barH),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        drawLine(
            color = Tokens.TextTertiary.copy(alpha = 0.3f),
            start = Offset(0f, size.height), end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
    }
}
