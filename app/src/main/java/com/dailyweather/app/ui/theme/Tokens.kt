package com.dailyweather.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 南风天气真机截图的实测度量与色值。
 *
 * 来源统一：`home_a.jpg` 1272×2772（density 3.0，即 1dp = 3px）与
 * `set_a.jpg` 1440×3168。凡是这里写的数值都是**逐像素量出来的**，
 * 不是照抄某个旧版实现；改动请同步重新量，不要凭感觉调。
 */
object Tokens {

    // ---- 首页卡片矩阵 ----
    /** 卡片左右边距：实测卡片占 x48..x1224。 */
    val ScreenMargin = 16.dp
    /** 卡片间距：实测 25px，略增为 10dp 以改善呼吸感。 */
    val CardGap = 10.dp
    // ---- 统一圆角系统 ----
    /** 大卡片圆角：首页主卡片、设置卡 */
    val CardRadius = 22.dp
    /** 中等圆角：详情卡、雷达卡、AQI 卡 */
    val MediumRadius = 18.dp
    /** 小圆角：按钮、胶囊标签、图标底板 */
    val SmallRadius = 12.dp
    /** 微型圆角：温度条、光谱条、进度条 */
    val MiniRadius = 6.dp
    /** 城市名胶囊圆角 */
    val PillRadius = 16.dp
    /** [实测] 卡面 = 天空叠 0xB830465A：白天与夜间两组实测值都能由它推出，常数黑叠层推不出。 */
    val CardFill = Color(0xB830465A)
    /** 卡片顶部亮边：实测卡上沿有一条 2–3px 的高光。 */
    val CardStroke = Color(0x1FFFFFFF)

    /** 小时卡高度：实测 y742..y1039 = 297px。 */
    val HourlyCardHeight = 99.dp
    /** 小时列宽：实测列距 155px。 */
    val HourlyCellWidth = 51.dp

    // ---- 文字 ----
    val TextPrimary = Color(0xFFF1F3F5)
    val TextSecondary = Color(0xD9FFFFFF)
    val TextTertiary = Color(0xA6FFFFFF)
    /** [实测] 降水概率的青色，采样簇 #51BDD9 / #55BCCF / #60BBD6。 */
    val PrecipCyan = Color(0xFF55BDD6)

    // ---- 7 日温度条 ----
    /** 实测条高 12px。 */
    val TempBarHeight = 4.dp
    /** 实测渐变 #D3D278 → #FCCB52（左淡右浓）。 */
    val BarStart = Color(0xFFD3D278)
    val BarEnd = Color(0xFFFCCB52)
    /** 轨道：实测比卡面亮约 15，即白 @10%。 */
    val BarTrack = Color(0x1AFFFFFF)

    // ---- Hero ----
    /** 实测数字帽高 197px ≈ 66dp，对应字号约 92sp。微调至 96sp 增强存在感。 */
    val HeroTempSize = 96.sp
    /** 不给行高会按字体默认行距（CJK 约 1.35em）撑开，Hero 与副行之间会出现一大段空白。 */
    val HeroDegreeSize = 42.sp
    /** 实测 Hero 是白→灰的竖向渐变填充，不是纯色。 */
    val HeroGradientTop = Color(0xFFF4F5F7)
    val HeroGradientBottom = Color(0xFF9AA1A8)
    val SublineSize = 17.sp

    // ---- 生活建议网格 ----
    /** 实测 3 列，列心 x245 / x640 / x1035，即等分卡宽。 */
    val AdviceIconSize = 34.dp
    /** 实测行距 282px。 */
    val AdviceRowPitch = 94.dp
    val AdviceLabelSize = 13.sp

    // ---- 台风雷达卡 ----
    /** 实测 y1832..y2158 = 326px。 */
    val RadarCardHeight = 109.dp
    val RadarThumbWidth = 93.dp
    val RadarThumbHeight = 72.dp
    val RadarThumbRadius = 16.dp

    // ---- 设置页（浅色 iOS 风）----
    val PageBackground = Color(0xFFF7F7F9)
    val GroupCardBackground = Color(0xFFFFFFFF)
    val GroupHeaderColor = Color(0xFF8E8E93)
    val RowTitleColor = Color(0xFF1C1C1E)
    val RowSecondaryColor = Color(0xFF8E8E93)
    val DividerColor = Color(0xFFE5E5EA)
    /** 实测行距 184–191px @density 3 → 61dp。 */
    val SettingsRowHeight = 61.dp
    val SwitchOnGreen = Color(0xFF62C06B)
    val SwitchOffTrack = Color(0xFFE9E9EA)
    val AccountBlue = Color(0xFF2E7CD6)

    /** [实测] 设置卡圆角约 93px。 */
    val SettingsCardRadius = 28.dp
    /** [实测] 设置卡左右边距 47px。 */
    val SettingsScreenMargin = 16.dp
    val RowTitleSize = 16.sp
    val SectionHeaderSize = 12.sp
    val PageTitleSize = 18.sp
}
