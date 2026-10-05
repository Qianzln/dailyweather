package com.dailyweather.app.data

/**
 * 设置键位与默认值。
 */
object SettingsKeys {

    // 颜色模式：LIGHT / DARK / SYSTEM
    const val COLOR_MODE = "color_mode"
    const val COLOR_MODE_DEFAULT = "LIGHT"

    // 控件材质：LIQUID / FROST / TRANSLUCENT
    const val CARD_MATERIAL = "card_material"
    const val CARD_MATERIAL_DEFAULT = "LIQUID"

    // 定位名称层级：DISTRICT（区/县）/ LANDMARK（地标）
    const val LOCATION_LEVEL = "location_level"
    const val LOCATION_LEVEL_DEFAULT = "DISTRICT"

    // 主页卡片显隐
    const val SHOW_AQI = "show_aqi"
    const val SHOW_MINUTELY = "show_minutely"
    const val SHOW_HOURLY48 = "show_hourly48"
    const val SHOW_SUN = "show_sun"
    const val SHOW_DETAIL = "show_detail"
    const val SHOW_RADAR = "show_radar"
    const val SHOW_LIFE = "show_life"

    // 主页卡片排序（逗号分隔的 HomeCardId 名）
    const val CARD_ORDER = "card_order"

    // 桌面组件的材质与不透明度，键为 widget_<id>，值形如 "GRADIENT:100"
    const val WIDGET_PREFIX = "widget_"
}

/**
 * 主页卡片。顺序即「主页卡片排序」列表里的顺序，
 * 「天气概况与预警」是 Hero 区，不参与排序。
 */
enum class HomeCardId(val title: String) {
    SUMMARY("天气概况与预警"),
    AQI("空气质量"),
    HOURLY("逐小时预报"),
    DAILY("多日预报"),
    PRECIP("分钟级降水"),
    SUN("日出日落"),
    DETAIL("气象详情"),
    RADAR("台风雷达&海洋潮汐"),
    LIFE("生活建议"),
}

/** 默认排序与显隐：所有卡片默认全开。台风雷达不再上首页（数据层保留给台风详情页）。 */
object HomeCards {

    val defaultOrder: List<HomeCardId> = listOf(
        HomeCardId.SUMMARY, HomeCardId.AQI, HomeCardId.HOURLY, HomeCardId.DAILY,
        HomeCardId.PRECIP, HomeCardId.SUN, HomeCardId.DETAIL, HomeCardId.LIFE,
    )

    fun defaultVisible(id: HomeCardId): Boolean = true
}
