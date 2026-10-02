package com.dailyweather.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.WeatherBitmapIcon
import com.dailyweather.app.ui.components.SkyconMap
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.viewmodel.WeatherViewModel

/** 城市列表：多城市管理 + 定位城市置顶（对齐南风 CityListScreen）。 */
@Composable
fun CityListScreen(
    vm: WeatherViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onPickCity: (String) -> Unit,
) {
    val sky = LocalSky.current
    val cities by vm.cities.collectAsState()
    val snapshots by vm.snapshotCache.collectAsState()
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
        LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            items(cities, key = { it.id }) { city ->
                val snap = snapshots[city.id]
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickCity(city.id) },
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
