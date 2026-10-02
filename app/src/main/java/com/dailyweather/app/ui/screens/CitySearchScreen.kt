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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.data.City
import com.dailyweather.app.ui.components.GlassCard
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.LocalSky
import com.dailyweather.app.viewmodel.WeatherViewModel

/** 城市搜索：高德关键字搜索优先，内置城市表兜底。 */
@Composable
fun CitySearchScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val sky = LocalSky.current
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<Triple<String, Double, Double>>()) }

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
            Text("添加城市", color = sky.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                vm.searchCities(it) { list -> results = list }
            },
            placeholder = { Text("输入城市名或拼音（如：北京 / beijing）", color = sky.textSecondary) },
            colors = TextFieldDefaults.colors(
                focusedTextColor = sky.textPrimary,
                unfocusedTextColor = sky.textPrimary,
                focusedContainerColor = sky.cardFill,
                unfocusedContainerColor = sky.cardFill,
                focusedIndicatorColor = sky.cardStroke,
                unfocusedIndicatorColor = sky.cardStroke,
                cursorColor = sky.textPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            items(results) { (name, lat, lng) ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.addCity(
                                City(
                                    id = "c_${lng}_${lat}",
                                    name = name.substringBefore(" ·"),
                                    longitude = lng,
                                    latitude = lat,
                                )
                            )
                            onBack()
                        },
                ) {
                    Text(name, color = sky.textPrimary, fontSize = 16.sp)
                }
            }
        }
    }
}
