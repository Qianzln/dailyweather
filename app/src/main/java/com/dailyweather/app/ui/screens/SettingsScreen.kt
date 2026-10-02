package com.dailyweather.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyweather.app.BuildConfig
import com.dailyweather.app.ui.components.rememberLucide
import com.dailyweather.app.ui.theme.Tokens
import com.dailyweather.app.viewmodel.WeatherViewModel

/**
 * 设置主页与全部子页共用的浅色 iOS 版式原语。
 *
 * 度量以 [Tokens] 为准（南风 v4.3.36 真机截图量出）：页面底 #F7F7F9、白卡圆角 28dp、
 * 行高 61dp、组标题 12sp #8E8E93、行标题 16sp #1C1C1E、开关绿 #62C06B、单选勾蓝 #2E7CD6。
 * 会员项一律灰态带锁、不可点——截图里它们就是那样，首页也确实没有那些卡。
 */

@Composable
fun SettingsScreen(vm: WeatherViewModel, onBack: () -> Unit, onOpenSub: (String) -> Unit) {
    SettingsScaffold("设置", onBack) {
        AccountCard { onOpenSub("账号与会员") }
        GroupCard {
            NavRow("通知设置", onClick = { onOpenSub("通知设置") })
            NavRow("外观设置", onClick = { onOpenSub("外观设置") })
            NavRow("单位设置", onClick = { onOpenSub("单位设置") })
            NavRow("定位显示", onClick = { onOpenSub("定位显示") })
            NavRow("插件设置", onClick = { onOpenSub("插件设置") })
            NavRow("语音播报", onClick = { onOpenSub("语音播报") }, divider = false)
        }
        GroupCard {
            NavRow("了解天气图标", onClick = { onOpenSub("了解天气图标") })
            NavRow("检查更新", trailing = "v${BuildConfig.VERSION_NAME}")
            NavRow("联系作者", onClick = { onOpenSub("联系作者") }, divider = false)
        }
    }
}

/** 子页统一入口：按标题分发，MainActivity 不必为每页加路由。 */
@Composable
fun SettingsSubScreen(title: String, vm: WeatherViewModel, onBack: () -> Unit, onOpenSub: (String) -> Unit = {}) {
    when {
        title == "通知设置" -> NotificationSettingsScreen(vm, onBack)
        title == "外观设置" -> AppearanceSettingsScreen(vm, onBack)
        title == "单位设置" -> UnitsSettingsScreen(vm, onBack)
        title == "定位显示" -> LocationDisplayScreen(vm, onBack)
        title == "插件设置" -> PluginsSettingsScreen(vm, onBack, onOpenSub)
        title == "语音播报" -> SpeechSettingsScreen(vm, onBack)
        title == "了解天气图标" -> WeatherIconsScreen(vm, onBack)
        title == "联系作者" -> ContactAuthorScreen(vm, onBack)
        title.startsWith("组件详情:") -> WidgetDetailScreen(title.removePrefix("组件详情:"), vm, onBack)
        else -> SettingsScaffold(title, onBack) {
            GroupCard { NavRow("该页面尚未接入", divider = false) }
        }
    }
}

@Composable
internal fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.PageBackground)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        StatusBarAppearance(true)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rememberLucide("arrow-left")?.let {
                Icon(
                    it, contentDescription = "返回", tint = Tokens.RowTitleColor,
                    modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onBack).padding(7.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Tokens.RowTitleColor, fontSize = Tokens.PageTitleSize, fontWeight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.SettingsScreenMargin),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.SettingsCardRadius))
            .background(Tokens.GroupCardBackground),
        content = content,
    )
}

@Composable
internal fun RowDivider(show: Boolean) {
    if (show) Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(0.5.dp).background(Tokens.DividerColor))
}

@Composable
internal fun NavRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .height(if (subtitle == null) Tokens.SettingsRowHeight else 68.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Tokens.RowTitleColor, fontSize = Tokens.RowTitleSize)
            subtitle?.let { Text(it, color = Tokens.RowSecondaryColor, fontSize = 12.sp) }
        }
        trailing?.let {
            Text(it, color = Tokens.RowSecondaryColor, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
        }
        if (onClick != null) {
            rememberLucide("chevron-right")?.let {
                Icon(it, contentDescription = null, tint = Tokens.RowSecondaryColor, modifier = Modifier.size(18.dp))
            }
        }
    }
    RowDivider(divider)
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    divider: Boolean = true,
    locked: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.SettingsRowHeight)
            .clickable(enabled = !locked) { onToggle(!checked) }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (locked) Tokens.RowSecondaryColor else Tokens.RowTitleColor,
            fontSize = Tokens.RowTitleSize,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            rememberLucide("lock")?.let {
                Icon(it, contentDescription = "需会员", tint = Tokens.RowSecondaryColor, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = if (locked) null else { v: Boolean -> onToggle(v) },
            enabled = !locked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Tokens.SwitchOnGreen,
                uncheckedTrackColor = Tokens.SwitchOffTrack,
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = Tokens.SwitchOffTrack,
                disabledCheckedTrackColor = Tokens.SwitchOffTrack,
                disabledUncheckedThumbColor = Color(0xFFD6D6DB),
                disabledCheckedThumbColor = Color(0xFFD6D6DB),
                disabledUncheckedBorderColor = Color.Transparent,
                disabledCheckedBorderColor = Color.Transparent,
            ),
        )
    }
    RowDivider(divider)
}

@Composable
internal fun RadioRow(
    title: String,
    selected: Boolean,
    divider: Boolean = true,
    locked: Boolean = false,
    onSelect: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(Tokens.SettingsRowHeight)
            .clickable(enabled = !locked, onClick = onSelect)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (locked) Tokens.RowSecondaryColor else Tokens.RowTitleColor,
            fontSize = Tokens.RowTitleSize,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            rememberLucide("lock")?.let {
                Icon(it, contentDescription = "需会员", tint = Tokens.RowSecondaryColor, modifier = Modifier.size(17.dp))
            }
        } else if (selected) {
            rememberLucide("check")?.let {
                Icon(it, contentDescription = null, tint = Tokens.AccountBlue, modifier = Modifier.size(22.dp))
            }
        }
    }
    RowDivider(divider)
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text, color = Tokens.GroupHeaderColor, fontSize = Tokens.SectionHeaderSize,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 6.dp),
    )
}

@Composable
internal fun Footnote(text: String) {
    Text(
        text, color = Tokens.RowSecondaryColor, fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun AccountCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.SettingsCardRadius))
            .background(Tokens.GroupCardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(46.dp).background(Color(0xFFEAF3FE), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            rememberLucide("account-avatar")?.let {
                Icon(it, contentDescription = null, tint = Tokens.AccountBlue, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("账号与会员", color = Tokens.RowTitleColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("去登录 · 解锁更多功能", color = Tokens.AccountBlue, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFEAF3FE))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text("去登录", color = Tokens.AccountBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

/**
 * 浅色页必须把状态栏/导航栏图标切成深色，否则 MainActivity 里为天空场景设的
 * 浅色图标在 #F7F7F9 底上完全读不出来。
 */
@Composable
internal fun StatusBarAppearance(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(light) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
        onDispose { }
    }
}
