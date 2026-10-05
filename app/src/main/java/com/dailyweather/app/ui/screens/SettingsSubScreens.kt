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
    val tempChange by vm.settingFlow(WeatherViewModel.SettingKey.TEMP_CHANGE).collectAsState(false)
    val wind by vm.settingFlow(WeatherViewModel.SettingKey.WIND).collectAsState(false)
    val extreme by vm.settingFlow(WeatherViewModel.SettingKey.EXTREME).collectAsState(false)

    SettingsScaffold("通知设置", onBack) {
        GroupCard {
            SwitchRow("常驻天气", persistent, divider = false,
                onToggle = { vm.setSetting(WeatherViewModel.SettingKey.PERSISTENT, it) })
        }
        Footnote("优先显示定位城市。静音更新，后台刷新受系统省电影响。部分系统支持划除通知，重新打开 App 后恢复。")
        GroupCard {
            SwitchRow("早晚天气通知", morning || evening, onToggle = { vm.setDailyBoth(it) })
            SwitchRow("气象预警推送", urgent, onToggle = { vm.setSetting(WeatherViewModel.SettingKey.URGENT, it) })
            SwitchRow("变温提醒", tempChange, onToggle = { vm.setSetting(WeatherViewModel.SettingKey.TEMP_CHANGE, it) })
            SwitchRow("大风提醒", wind, onToggle = { vm.setSetting(WeatherViewModel.SettingKey.WIND, it) })
            SwitchRow("极端天气提醒", extreme, divider = false,
                onToggle = { vm.setSetting(WeatherViewModel.SettingKey.EXTREME, it) })
        }
    }
}

@Composable
fun AppearanceSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val colorMode by vm.stringSetting(SettingsKeys.COLOR_MODE, SettingsKeys.COLOR_MODE_DEFAULT).collectAsState("LIGHT")
    val material by vm.stringSetting(SettingsKeys.CARD_MATERIAL, SettingsKeys.CARD_MATERIAL_DEFAULT).collectAsState("LIQUID")
    val life by vm.boolSetting(SettingsKeys.SHOW_LIFE, true).collectAsState(true)
    val aqi by vm.boolSetting(SettingsKeys.SHOW_AQI, true).collectAsState(true)
    val sun by vm.boolSetting(SettingsKeys.SHOW_SUN, true).collectAsState(true)
    val detail by vm.boolSetting(SettingsKeys.SHOW_DETAIL, true).collectAsState(true)
    val orderRaw by vm.stringSetting(SettingsKeys.CARD_ORDER, "").collectAsState("")

    val order = remember(orderRaw) {
        val saved = orderRaw.split(",").mapNotNull { runCatching { HomeCardId.valueOf(it) }.getOrNull() }
        (saved + HomeCards.defaultOrder).distinct()
    }
    var picked by remember { mutableStateOf<HomeCardId?>(null) }

    SettingsScaffold("外观", onBack) {
        SectionHeader("颜色模式")
        GroupCard {
            RadioRow("浅色", colorMode == "LIGHT") { vm.setStringSetting(SettingsKeys.COLOR_MODE, "LIGHT") }
            RadioRow("深色", colorMode == "DARK") { vm.setStringSetting(SettingsKeys.COLOR_MODE, "DARK") }
            RadioRow("跟随系统", colorMode == "SYSTEM", divider = false) {
                vm.setStringSetting(SettingsKeys.COLOR_MODE, "SYSTEM")
            }
        }
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
            if (picked == null) "长按卡片上下拖动，调整主页卡片的显示顺序"
            else "再点一行，把「${picked?.title}」移到它的位置",
        )
        SectionHeader("控件材质")
        GroupCard {
            RadioRow("液态玻璃", material == "LIQUID") { vm.setStringSetting(SettingsKeys.CARD_MATERIAL, "LIQUID") }
            RadioRow("磨砂玻璃", material == "FROST") { vm.setStringSetting(SettingsKeys.CARD_MATERIAL, "FROST") }
            RadioRow("半透明", material == "TRANSLUCENT", divider = false) {
                vm.setStringSetting(SettingsKeys.CARD_MATERIAL, "TRANSLUCENT")
            }
        }
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
    val visibility by vm.stringSetting("unit_visibility", "KM").collectAsState("KM")

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
        SectionHeader("能见度")
        GroupCard {
            RadioRow("公里（km）", visibility == "KM") { vm.setStringSetting("unit_visibility", "KM") }
            RadioRow("英里（mi）", visibility == "MI", divider = false) { vm.setStringSetting("unit_visibility", "MI") }
        }
    }
}

@Composable
fun LocationDisplayScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    val level by vm.stringSetting(SettingsKeys.LOCATION_LEVEL, SettingsKeys.LOCATION_LEVEL_DEFAULT)
        .collectAsState("DISTRICT")

    SettingsScaffold("定位显示", onBack) {
        SectionHeader("定位名称")
        GroupCard {
            RadioRow("展示区/县", level == "DISTRICT") {
                vm.setStringSetting(SettingsKeys.LOCATION_LEVEL, "DISTRICT")
            }
        }
    }
}

/** 7 个桌面组件的默认「材质:不透明度」，键名与 res/xml 里的 provider 对齐。 */
private val WIDGET_DEFAULTS = listOf(
    Triple("small", "2×2 今日天气", "桌面小卡片") to "GRADIENT:100",
    Triple("2x1", "2×1 实时天气", "图标与温度") to "GRADIENT:100",
    Triple("4x2", "4×2 多功能天气", "天气概览与五日预报") to "GRADIENT:100",
    Triple("medium", "4×2 时钟天气", "时钟为主，可叠加天气色卡片") to "NONE:0",
    Triple("week", "4×2 多日天气", "五日预报与温度区间条") to "GRADIENT:85",
    Triple("hourly", "4×2 小时天气", "逐小时天气预报") to "GRADIENT:85",
    Triple("ios", "4×2 周历天气", "时钟、七日周历与多日温度") to "GRADIENT:100",
)

@Composable
fun PluginsSettingsScreen(vm: WeatherViewModel, onBack: () -> Unit, onOpenSub: (String) -> Unit) {
    val background by vm.settingFlow(WeatherViewModel.SettingKey.BACKGROUND).collectAsState(false)

    SettingsScaffold("插件设置", onBack) {
        SectionHeader("位置权限")
        GroupCard {
            NavRow(
                "后台定位",
                subtitle = if (background) "已开启 · 后台按需更新" else "未开启 · 仅使用时可定位",
                divider = false,
                onClick = { vm.setSetting(WeatherViewModel.SettingKey.BACKGROUND, !background) },
            )
        }
        Footnote("允许后，桌面插件和天气通知可在后台按需更新位置；未开启时仍会更新上次位置的天气。")
        SectionHeader("组件外观")
        GroupCard {
            WIDGET_DEFAULTS.forEachIndexed { idx, entry ->
                val meta = entry.first
                val saved by vm.stringSetting(SettingsKeys.WIDGET_PREFIX + meta.first, entry.second)
                    .collectAsState(entry.second)
                val parts = saved.split(":")
                NavRow(
                    meta.second,
                    subtitle = meta.third,
                    trailing = "${if (parts.getOrElse(0) { "GRADIENT" } == "NONE") "无背景" else "天气渐变"} · ${parts.getOrElse(1) { "100" }}%",
                    divider = idx != WIDGET_DEFAULTS.lastIndex,
                    onClick = { onOpenSub("组件详情:${meta.first}") },
                )
            }
        }
        Footnote(
            "点击任一组件可单独设置背景材质与不透明度。百分比越低，桌面壁纸透出越多；0% 表示完全透出壁纸。" +
                "调整后立即生效。组件的格子大小由系统桌面决定，不同机型的实际大小会有差异；长按组件拖动边框可手动调整大小。",
        )
    }
}

@Composable
fun WidgetDetailScreen(widgetId: String, vm: WeatherViewModel, onBack: () -> Unit) {
    val saved by vm.stringSetting(SettingsKeys.WIDGET_PREFIX + widgetId, "GRADIENT:100").collectAsState("GRADIENT:100")
    val parts = saved.split(":")
    val material = parts.getOrElse(0) { "GRADIENT" }
    val opacity = parts.getOrElse(1) { "100" }.toIntOrNull() ?: 100

    SettingsScaffold("组件外观", onBack) {
        SectionHeader("背景材质")
        GroupCard {
            RadioRow("天气渐变", material == "GRADIENT") {
                vm.setStringSetting(SettingsKeys.WIDGET_PREFIX + widgetId, "GRADIENT:$opacity")
            }
            RadioRow("无背景", material == "NONE", divider = false) {
                vm.setStringSetting(SettingsKeys.WIDGET_PREFIX + widgetId, "NONE:0")
            }
        }
        SectionHeader("不透明度")
        GroupCard {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Slider(
                    value = opacity.toFloat(),
                    onValueChange = { v ->
                        vm.setStringSetting(SettingsKeys.WIDGET_PREFIX + widgetId, "$material:${v.toInt()}")
                    },
                    valueRange = 0f..100f,
                )
                Text(
                    "$opacity%", color = Tokens.RowSecondaryColor, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End,
                )
            }
        }
        Footnote("调整后回到桌面即可看到效果；0% 表示完全透出壁纸。")
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
        Footnote("播报内容：当前温度、天气现象、未来降水与预警。\n开启后每次刷新天气会自动播报。")
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
        Footnote("图标为 Meteocons 套系（MIT），另有一套蓝色变体可在首页右上角切换。")
    }
}

@Composable
fun ContactAuthorScreen(vm: WeatherViewModel, onBack: () -> Unit) {
    SettingsScaffold("联系作者", onBack) {
        GroupCard { NavRow("版本", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", divider = false) }
        Footnote("本工程是本地研究用的界面对照实现，不对外分发；天气数据来自公开接口。")
    }
}
