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
    /** 逐小时卡显隐。原键名 show_hourly48 与实际渲染（24 小时）不符，v0.1.31 改名。 */
    const val SHOW_HOURLY = "show_hourly"
    const val SHOW_SUN = "show_sun"
    const val SHOW_DETAIL = "show_detail"
    /** 天气趋势卡（forecastKey 独立成卡）显隐。 */
    const val SHOW_TREND = "show_trend"
    /** 台风雷达卡显隐（对齐南风首页台风雷达位置）。 */
    const val SHOW_RADAR = "show_radar"
    /** 气象预警卡显隐（对齐南风首页预警条）。 */
    const val SHOW_ALERT = "show_alert"
    const val SHOW_LIFE = "show_life"

    // 主页卡片排序（逗号分隔的 HomeCardId 名）
    const val CARD_ORDER = "card_order"

    // 桌面组件的材质与不透明度，键为 widget_<id>，值形如 "GRADIENT:100"
    const val WIDGET_PREFIX = "widget_"
}

/**
 * 主页卡片。顺序即「主页卡片排序」列表里的顺序。
 *
 * 注意：`SUMMARY` 是 Hero 区（由 `WeatherScreen` 无条件渲染），`RADAR`（台风雷达&海洋潮汐）
 * 当前无实现——两者都在 [SettingsStore.homeCardsFlow] 里被过滤掉，不进卡片流，
 * 否则会渲染出一个空 item（现象：卡片之间多一段空白）。
 */
enum class HomeCardId(val title: String) {
    SUMMARY("天气概况与预警"),
    /** 分钟级降水（南风首页第 1 张卡）。 */
    PRECIP("分钟级降水"),
    AQI("空气质量"),
    HOURLY("逐小时预报"),
    DAILY("多日预报"),
    SUN("日出日落"),
    /** 气象详情：渲染为 3 张卡（紫外线 / 风力 / 大气），对应南风首页的第 6-8 项。 */
    DETAIL("气象详情"),
    /** 天气趋势（forecastKey 独立成卡，南风首页第 9 项）。 */
    TREND("天气趋势"),
    RADAR("台风雷达&海洋潮汐"),
    ALERT("气象预警"),
    LIFE("生活建议"),
}

/**
 * 默认排序与显隐：所有卡片默认全开。SUMMARY 是 Hero 区、RADAR 无实现，均不进卡片流。
 *
 * 顺序对齐南风 v4.3.79 `WeatherScreen` 的 LazyColumn item 顺序：
 * Hero → 分钟降水 → 空气质量 → 逐小时 → 逐日 → 日出日落 → 气象详情(×3) → 天气趋势 → 生活指数。
 */
object HomeCards {

    /** 卡片流默认顺序（不含 SUMMARY —— Hero 区无条件渲染）。
     *  RADAR 默认关（需用户手动开启），ALERT 默认开（有预警时显示）。 */
    val defaultOrder: List<HomeCardId> = listOf(
        HomeCardId.PRECIP, HomeCardId.AQI, HomeCardId.HOURLY, HomeCardId.DAILY,
        HomeCardId.SUN, HomeCardId.DETAIL, HomeCardId.TREND, HomeCardId.RADAR,
        HomeCardId.ALERT, HomeCardId.LIFE,
    )

    /** 不进卡片流的 id（Hero 区）。RADAR/ALERT 均进卡片流，由 settings 控制显隐。 */
    val nonCardIds: Set<HomeCardId> = setOf(HomeCardId.SUMMARY)

    fun defaultVisible(id: HomeCardId): Boolean = true
}
