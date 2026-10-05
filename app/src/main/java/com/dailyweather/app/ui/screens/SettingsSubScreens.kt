package com.dailyweather.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.BuildConfig
import com.dailyweather.app.data.HomeCardId
import com.dailyweather.app.data.HomeCards
import com.dailyweather.app.data.SettingsKeys
import com.dailyweather.app.ui.components.WeatherBitmapIcon
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.viewmodel.WeatherViewModel

/**
 * 设置子页。版式与文案照南风 v4.3.36 真机截图：通知页「常驻天气」单独一卡、
 * 外观页四组（颜色模式 / 主页卡片显隐 / 卡片排序 / 控件材质）；
 * 单位页四组（温度 / 风力 / 气压 / 能见度），单选勾是蓝色不是绿色；
 * 插件页 7 个组件各自带「材质 · 不透明度」并可进详情调整。
 */

@Composable
fun NotificationSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val persistent by vm.settingFlow(WeatherViewModel.SettingKey.PERSISTENT).collectAsState(false)
    val morning by vm.settingFlow(WeatherViewModel.SettingKey.DAILY_MORNING).collectAsState(false)
    val evening by vm.settingFlow(WeatherViewModel.SettingKey.DAILY_EVENING).collectAsState(false)
    val urgent by vm.settingFlow(WeatherViewModel.SettingKey.URGENT).collectAsState(false)

    SettingsScaffold("通知设置", onBack) {
        GroupCard {
            SwitchRow("常驻天气", persistent, divider = false,
                onToggle = { vm.setSetting(WeatherViewModel.SettingKey.PERSISTENT, it) })
        }
        Footnote("优先显示定位城市。静音更新，后台刷新受系统省电影响。部分系统支持划除通知，重新打开 App 后恢复。")
        GroupCard {
            SwitchRow("早晚天气通知", morning || evening, onToggle = { vm.setDailyBoth(it) })
            SwitchRow("气象预警推送", urgent, divider = false, onToggle = { vm.setSetting(WeatherViewModel.SettingKey.URGENT, it) })
        }
    }
}

@Composable
fun AppearanceSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val life by vm.boolSetting(SettingsKeys.SHOW_LIFE, true).collectAsState(true)
    val aqi by vm.boolSetting(SettingsKeys.SHOW_AQI, true).collectAsState(true)
    val sun by vm.boolSetting(SettingsKeys.SHOW_SUN, true).collectAsState(true)
    val detail by vm.boolSetting(SettingsKeys.SHOW_DETAIL, true).collectAsState(true)
    val orderRaw by vm.stringSetting(SettingsKeys.CARD_ORDER, "").collectAsState("")

    // 排序列表只含真实渲染的卡片：SUMMARY 是 Hero 区、RADAR 已不上首页，都过滤掉。
    val order = remember(orderRaw) {
        val saved = orderRaw.split(",").mapNotNull { runCatching { HomeCardId.valueOf(it) }.getOrNull() }
            .filter { it != HomeCardId.SUMMARY && it != HomeCardId.RADAR }
        (saved + HomeCards.defaultOrder)
            .filter { it != HomeCardId.SUMMARY && it != HomeCardId.RADAR }
            .distinct()
    }
    var picked by remember { mutableStateOf<HomeCardId?>(null) }

    SettingsScaffold("外观", onBack) {
        SectionHeader("主页卡片")
        GroupCard {
            SwitchRow("空气质量", aqi, onToggle = { vm.setBoolSetting(SettingsKeys.SHOW_AQI, it) })
            SwitchRow("日出日落", sun, onToggle = { vm.setBoolSetting(SettingsKeys.SHOW_SUN, it) })
            SwitchRow("气象详情", detail, onToggle = { vm.setBoolSetting(SettingsKeys.SHOW_DETAIL, it) })
            SwitchRow("生活建议", life, divider = false, onToggle = { vm.setBoolSetting(SettingsKeys.SHOW_LIFE, it) })
        }
        Footnote("控制主页各卡片的显示与隐藏")
        SectionHeader("主页卡片排序")
        GroupCard {
            order.forEachIndexed { idx, id ->
                CardOrderRow(id, picked == id, onPick = {
                    val target = picked
                    when {
                        target == null -> picked = id
                        target == id -> picked = null
                        else -> {
                            val next = order.toMutableList()
                            val from = next.indexOf(target)
                            val to = next.indexOf(id)
                            next.removeAt(from)
                            next.add(to, target)
                            picked = null
                            vm.setStringSetting(SettingsKeys.CARD_ORDER, next.joinToString(",") { it.name })
                        }
                    }
                })
                RowDivider(idx != order.lastIndex)
            }
        }
        Footnote(
            if (picked == null) "点选两行交换，调整主页卡片的显示顺序"
            else "再点一行，把「${picked?.title}」移到它的位置",
        )
    }
}

/** 排序行：先点一行选中（高亮），再点目标行完成移动。南风是长按拖拽，这里用点选代替。 */
@Composable
private fun CardOrderRow(id: HomeCardId, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.SettingsRowHeight)
            .clickable(onClick = onPick)
            .padding(horizontal = 18.dp)
            .background(if (selected) Color(0xFFEAF3FE) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            id.title,
            color = Tokens.RowTitleColor,
            fontSize = Tokens.RowTitleSize,
            modifier = Modifier.weight(1f),
        )
        rememberLucide("grip-vertical")?.let {
            Icon(it, contentDescription = null, tint = Tokens.RowSecondaryColor, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun UnitsSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val temp by vm.temperatureUnit.collectAsState("C")
    val wind by vm.windUnit.collectAsState("KMH")
    val pressure by vm.pressureUnit.collectAsState("HPA")

    SettingsScaffold("单位设置", onBack) {
        SectionHeader("温度")
        GroupCard {
            RadioRow("摄氏度（°C）", temp == "C") { vm.setTemperatureUnit("C") }
            RadioRow("华氏度（°F）", temp == "F", divider = false) { vm.setTemperatureUnit("F") }
        }
        SectionHeader("风力")
        GroupCard {
            RadioRow("公里/小时（km/h）", wind == "KMH") { vm.setWindUnit("KMH") }
            RadioRow("米/秒（m/s）", wind == "MS") { vm.setWindUnit("MS") }
            RadioRow("英里/小时（mph）", wind == "MPH") { vm.setWindUnit("MPH") }
            RadioRow("节（kn）", wind == "KN", divider = false) { vm.setWindUnit("KN") }
        }
        SectionHeader("气压")
        GroupCard {
            RadioRow("百帕（hPa）", pressure == "HPA") { vm.setPressureUnit("HPA") }
            RadioRow("毫米汞柱（mmHg）", pressure == "MMHG") { vm.setPressureUnit("MMHG") }
            RadioRow("英寸汞柱（inHg）", pressure == "INHG", divider = false) { vm.setPressureUnit("INHG") }
        }
    }
}

@Composable
fun SpeechSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val speech by vm.settingFlow(WeatherViewModel.SettingKey.SPEECH).collectAsState(false)

    SettingsScaffold("语音播报", onBack) {
        GroupCard {
            SwitchRow("开启语音播报", speech, divider = true,
                onToggle = { vm.setSetting(WeatherViewModel.SettingKey.SPEECH, it) })
            // 试听：立即播报当前城市天气。
            NavRow("试听播报", trailing = "播放当前天气", onClick = { vm.speakWeather() }, divider = false)
        }
        Footnote("播报内容：当前温度与天气现象（如「当前 22 度，多云」）。\n开启后每次刷新天气会自动播报。")
    }
}

/** 了解天气图标：把包里那两套图标的样子与含义列出来。 */
@Composable
fun WeatherIconsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val blue by vm.settingFlow(WeatherViewModel.SettingKey.BLUE).collectAsState(false)
    val icons = listOf(
        "clear-day" to "晴", "clear-night" to "晴夜", "partly-cloudy-day" to "多云",
        "partly-cloudy-night" to "多云夜", "overcast" to "阴", "drizzle" to "小雨",
        "night-drizzle" to "夜小雨", "rain" to "雨", "thunderstorms-rain" to "雷阵雨",
        "snow" to "雪", "rain-snow" to "雨夹雪", "fog" to "雾", "haze" to "霾", "wind" to "大风",
    )
    SettingsScaffold("了解天气图标", onBack) {
        GroupCard {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                icons.chunked(4).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        row.forEach { icon ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                WeatherBitmapIcon(icon.first, blue, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(icon.second, color = Tokens.RowTitleColor, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Footnote("图标为 Meteocons 套系（MIT），含两套配色（标准 / 蓝色变体）。")
    }
}

@Composable
fun ContactAuthorScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    SettingsScaffold("联系作者", onBack) {
        GroupCard { NavRow("版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", divider = false) }
        Footnote("本工程是本地研究用的界面对照实现，不对外分发；天气数据来自公开接口。")
    }
}
