package com.dailyweather.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.WeatherBitmapIcon
import com.dailyweather.app.ui.components.WeatherSkyBackground
import com.dailyweather.app.ui.components.SkyconMap
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.viewmodel.WeatherViewModel

@OptIn(ExperimentalFoundationApi::class)

/** 城市列表：多城市管理 + 定位城市置顶（对齐南风 CityListScreen）。
 *  长按删除 + 拖拽手柄排序（拖动行右侧手柄，松手落库）。 */
@Composable
fun CityListScreen(
    vm: WeatherViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onPickCity: (String) -> Unit,
) {
    val cities by vm.cities.collectAsState()
    val snapshots by vm.snapshotCache.collectAsState()

    // 天气天空背景（随所选城市天气）+ 场景调色板，替代纯蓝窗底。
    WeatherSkyBackground(vm) {
        val sky = LocalSky.current
        // ---- 拖拽排序状态（LazyColumn 手柄拖动：记录拖拽中的 key 与位移）----
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        var draggingId by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        var dragOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val itemHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            val backIcon = rememberLucide("arrow-left")
            if (backIcon != null) {
                Icon(
                    imageVector = backIcon,
                    contentDescription = "返回",
                    tint = sky.textPrimary,
                    modifier = Modifier
                        .width(28.dp)
                        .height(28.dp)
                        .clickable(onClick = onBack),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("城市管理", color = sky.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val plus = rememberLucide("plus")
            if (plus != null) {
                Icon(
                    imageVector = plus,
                    contentDescription = "添加",
                    tint = sky.textPrimary,
                    modifier = Modifier
                        .width(28.dp)
                        .height(28.dp)
                        .clickable(onClick = onOpenSearch),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = listState, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            items(cities, key = { it.id }) { city ->
                val snap = snapshots[city.id]
                val dragging = draggingId == city.id
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (dragging) {
                                translationY = dragOffset
                                scaleX = 1.03f
                                scaleY = 1.03f
                                shadowElevation = 12f
                            }
                        }
                        .combinedClickable(
                            onClick = { onPickCity(city.id) },
                            onLongClick = {
                                // 长按删除；定位城市不可删（它的存在由定位开关管理）。
                                if (!city.isCurrentLocation) vm.removeCity(city.id)
                            },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(city.name, color = sky.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                if (city.isCurrentLocation) {
                                    Spacer(Modifier.width(4.dp))
                                    val pin = rememberLucide("map-pin")
                                    if (pin != null) {
                                        Icon(pin, contentDescription = "定位", tint = sky.textSecondary, modifier = Modifier.width(14.dp).height(14.dp))
                                    }
                                }
                            }
                            if (snap != null) {
                                Text(
                                    "${SkyconMap.desc(snap.currentSkycon)} · ${snap.daily.firstOrNull()?.let { "${it.tempMin.toInt()}°/${it.tempMax.toInt()}°" } ?: ""}",
                                    color = sky.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        snap?.let {
                            Text("${it.currentTemp.toInt()}°", color = sky.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Light)
                            Spacer(Modifier.width(8.dp))
                            WeatherBitmapIcon(SkyconMap.asset(it.currentSkycon), blueTheme = false, contentDescription = null)
                        }
                        if (!city.isCurrentLocation) {
                            // 拖拽手柄：按住上下拖动重排（城市卡片行高含间距约 96dp）。
                            rememberLucide("menu")?.let { handle ->
                                Icon(
                                    imageVector = handle,
                                    contentDescription = "拖动排序",
                                    tint = sky.textSecondary,
                                    modifier = Modifier
                                        .width(20.dp).height(20.dp)
                                        .padding(start = 4.dp)
                                        .pointerInput(city.id, cities) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingId = city.id
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragOffset += amount.y
                                                    // 拖过一格（行高）就和相邻城市交换。
                                                    val steps = (dragOffset / itemHeightPx).toInt()
                                                    if (steps != 0) {
                                                        val from = cities.indexOfFirst { it.id == city.id }
                                                        val to = (from + steps).coerceIn(0, cities.size - 1)
                                                        if (from != to && !cities[from].isCurrentLocation) {
                                                            vm.reorderCities(cities.map { it.id }.toMutableList().apply {
                                                                add(to, removeAt(from))
                                                            })
                                                            dragOffset -= steps * itemHeightPx
                                                        }
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingId = null
                                                    dragOffset = 0f
                                                },
                                            )
                                        },
                                )
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSearch),
                ) {
                    Text("＋ 添加城市", color = sky.textSecondary, fontSize = 15.sp)
                }
            }
        }
        }
    }
}
