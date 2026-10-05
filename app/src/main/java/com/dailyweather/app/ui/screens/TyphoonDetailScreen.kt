package com.dailyweather.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.nativeCanvas
import com.dailyweather.app.data.model.WeatherSnapshot
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.viewmodel.WeatherViewModel
import kotlin.math.roundToInt

/**
 * 台风路径详情页。
 *
 * 数据来源：小米源 `typhoon.list`，包含当前定位、风速/气压、历史路径点。
 * 无台风时展示空态骨架，点击刷新可重新拉取。
 */
@Composable
fun TyphoonDetailScreen(
    vm: WeatherViewModel,
    snapshot: WeatherSnapshot? = null,
    onBack: () -> Unit,
) {
    val detailBack = Color(0xFFE8EEF9)
    val sky = com.dailyweather.app.ui.theme.LocalSky.current
    val radarFrames = vm.radarFrames
    val radarError = vm.radarError
    val typhoons = snapshot?.typhoons ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B2230))
            .verticalScroll(rememberScrollState()),
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                rememberLucide("chevron-left")
                    ?.let { Icon(it, contentDescription = "返回", tint = detailBack) }
            }
            Column {
                Text("台风路径", color = detailBack, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("实时追踪 · 路径可视化", color = Tokens.TextTertiary, fontSize = 12.sp)
            }
        }

        // 主内容区
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (typhoons.isEmpty()) {
                // 空态
                GlassCard(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        rememberLucide("cloud")?.let {
                            Icon(it, contentDescription = null, tint = Color(0xFF8BA4C0), modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前无活跃台风",
                            color = sky.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "台风季期间将显示路径追踪与雷达回波",
                            color = Tokens.TextTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                // 每个台风一张卡片
                typhoons.forEach { typhoon ->
                    TyphoonCard(typhoon, snapshot)
                }
            }

            // 雷达回波信息（如果有帧数据）
            if (radarFrames.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            rememberLucide("radar")?.let {
                                Icon(it, contentDescription = null, tint = Color(0xFF4A9EFF), modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("实时雷达回波", color = sky.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = radarError ?: "最近帧: ${frameLabel(radarFrames.last().first)}",
                            color = Tokens.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // 刷新按钮
            Box(
                modifier = Modifier.fillMaxWidth()
                    .height(44.dp)
                    .clickable { vm.loadRadar() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "刷新数据",
                    color = Color(0xFF4A9EFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun TyphoonCard(typhoon: WeatherSnapshot.Typhoon, snapshot: WeatherSnapshot?) {
    val sky = com.dailyweather.app.ui.theme.LocalSky.current
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题行：台风名称 + 编号
            Row(verticalAlignment = Alignment.CenterVertically) {
                rememberLucide("wind")?.let {
                    Icon(it, contentDescription = null, tint = Color(0xFF4A9EFF), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (typhoon.name.isNotBlank()) "${typhoon.name}（${typhoon.typhoonId}）" else typhoon.typhoonId,
                    color = sky.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 核心数据
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TyphoonStatItem("风速", "${typhoon.windSpeedKmh.roundToInt()} km/h", sky)
                TyphoonStatItem("气压", "${typhoon.pressureHpa.roundToInt()} hPa", sky)
                TyphoonStatItem("移动", "${typhoon.moveSpeedKmh.roundToInt()} km/h", sky)
            }
            Spacer(Modifier.height(6.dp))
            TyphoonStatItemFull("当前位置", "${"%.2f".format(typhoon.currentLat)}°N ${"%.2f".format(typhoon.currentLon)}°E", sky)

            Spacer(Modifier.height(10.dp))

            // 路径可视化
            if (typhoon.path.isNotEmpty()) {
                Text("历史路径（${typhoon.path.size} 个点）", color = Tokens.TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                TyphoonPathCanvas(typhoon.path, modifier = Modifier.fillMaxWidth().height(160.dp))
            }
        }
    }
}

@Composable
private fun TyphoonStatItem(label: String, value: String, sky: com.dailyweather.app.ui.theme.SkyPalette) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Tokens.TextTertiary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = sky.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TyphoonStatItemFull(label: String, value: String, sky: com.dailyweather.app.ui.theme.SkyPalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tokens.TextTertiary, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = sky.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 台风路径画布：将经纬度点映射到画布坐标，绘制轨迹线和当前位置标记。
 *
 * 简单等距投影：经度→X，纬度→Y（上下翻转）。
 * 路径点按时间升序排列，最新点在右侧；当前位置用红色脉冲圆点标注。
 */
@Composable
private fun TyphoonPathCanvas(path: List<WeatherSnapshot.TyphoonPoint>, modifier: Modifier = Modifier) {
    if (path.isEmpty()) return
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 16.dp.toPx()

        // 找经纬度范围
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        path.forEach { p ->
            minLat = minOf(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
            minLon = minOf(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
        }
        // 避免除零
        val latSpan = (maxLat - minLat).takeIf { it > 0.01 } ?: 1.0
        val lonSpan = (maxLon - minLon).takeIf { it > 0.01 } ?: 1.0
        // 留边距
        val scaleX = (w - pad * 2) / lonSpan
        val scaleY = (h - pad * 2) / latSpan
        val scale = minOf(scaleX, scaleY)

        fun lonToX(lon: Double) = (pad + (lon - minLon) * scale).toFloat()
        fun latToY(lat: Double) = (h - pad - (lat - minLat) * scale).toFloat()

        // 绘制路径线
        if (path.size >= 2) {
            val pathLine = Path().apply {
                moveTo(lonToX(path[0].lon), latToY(path[0].lat))
                for (i in 1 until path.size) {
                    lineTo(lonToX(path[i].lon), latToY(path[i].lat))
                }
            }
            drawPath(pathLine, color = Color(0xFF4A9EFF), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            // 路径点
            path.forEach { p ->
                drawCircle(color = Color(0xFF4A9EFF), radius = 3f, center = Offset(lonToX(p.lon), latToY(p.lat)))
            }
        }

        // 当前位置标记（红色脉冲圆点）
        val last = path.last()
        val cx = lonToX(last.lon)
        val cy = latToY(last.lat)
        drawCircle(color = Color(0xFFFF4444).copy(alpha = 0.3f), radius = 10f, center = Offset(cx, cy))
        drawCircle(color = Color(0xFFFF4444), radius = 5f, center = Offset(cx, cy))
        drawCircle(color = Color.White, radius = 2f, center = Offset(cx, cy))

        // 坐标刻度标签
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#88ffffff")
            textSize = 20f
            isAntiAlias = true
        }
        // 左下角：最小经纬度
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText("%.1f°N".format(minLat), (pad + 2f).toFloat(), (h - pad + 16f).toFloat(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("%.1f°E".format(minLon), (pad + 2f).toFloat(), (h - pad + 30f).toFloat(), textPaint)
        // 右上角：最大经纬度
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText("%.1f°N".format(maxLat), (w - pad - 2f).toFloat(), (pad + 16f).toFloat(), textPaint)
        drawContext.canvas.nativeCanvas.drawText("%.1f°E".format(maxLon), (w - pad - 2f).toFloat(), (pad + 30f).toFloat(), textPaint)
    }
}

/** unix 秒 → "HH:mm"。 */
private fun frameLabel(epochSec: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = epochSec * 1000
    return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}
