package com.dailyweather.app.scene

/**
 * 天气时段。
 *
 * 南风的场景枚举把时段编进了天气名（`PARTLY_CLOUDY_MORNING` / `_NOON` / `_EVENING`，
 * 中文文案对应「多云清晨 / 多云正午 / 多云傍晚」），那是 状况 × 时段 的笛卡尔积硬编码。
 * 这里拆成两个正交维度：时段产出光照档，天气产出云场与降水，
 * 于是 4 × 8 = 32 个场景不需要 32 套素材。
 */
enum class SkyPhase { MORNING, DAY, EVENING, NIGHT }

/** 天气状况，粒度取自南风实际支持的场景键。 */
enum class WeatherKind {
    CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, SLEET, FOG, WIND, THUNDER;

    /** 该状况的默认转阴度，喂给天空渐变兜底色。 */
    val baseOvercast: Float
        get() = when (this) {
            CLEAR -> 0.00f
            PARTLY_CLOUDY -> 0.25f
            CLOUDY -> 1.00f
            RAIN -> 0.85f
            SNOW -> 0.90f
            SLEET -> 0.88f
            FOG -> 0.70f
            WIND -> 0.15f
            THUNDER -> 0.95f
        }
}

/**
 * 首页场景的唯一输入。
 *
 * 这几个字段就是「今天长什么样」的全部真相：谁要画天气都从这里读，
 * 不允许自己再算一遍时段或再判一次天气。
 *
 * 渲染分工（1:1 南风混合方案）：
 * - 天空背景与云层 → PNG 精灵（[SpriteSkyBackground] / [drawSpriteCloudLayer]）
 * - 降水粒子与辉光 → 程序化位图（ParticleSprites，与南风同构）
 */
data class WeatherSceneState(
    val kind: WeatherKind,
    val phase: SkyPhase,
    /** 降水强度 0–1：只影响粒子数量与不透明度，不影响背景色。 */
    val intensity: Float = 0f,
    /** 风强 0–1：只影响漂移速度与云被拉长的程度。 */
    val wind: Float = 0.3f,
    /**
     * 日照进度 0–1：日出→日落之间太阳在弧线上的位置（对应南风 SunPose）。
     * null = 夜间或缺日出日落数据，不画太阳。
     */
    val sunProgress: Float? = null,
) {
    val isNight: Boolean get() = phase == SkyPhase.NIGHT

    val overcast: Float
        get() = (kind.baseOvercast + intensity * 0.15f).coerceAtMost(1f)
}
