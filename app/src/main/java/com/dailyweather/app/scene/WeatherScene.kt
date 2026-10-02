package com.dailyweather.app.scene
import androidx.compose.ui.graphics.Color

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
    CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, FOG, WIND, THUNDER;

    /** 该状况的默认转阴度，喂给 [CloudLightRig.overcast]。 */
    val baseOvercast: Float
        get() = when (this) {
            CLEAR -> 0.00f
            PARTLY_CLOUDY -> 0.25f
            CLOUDY -> 1.00f
            RAIN -> 0.85f
            SNOW -> 0.90f
            FOG -> 0.70f
            WIND -> 0.15f
            THUNDER -> 0.95f
        }
}

/**
 * 首页场景的唯一输入。
 *
 * 这三个字段就是「今天长什么样」的全部真相：谁要画天气都从这里读，
 * 不允许自己再算一遍时段或再判一次天气。
 */
data class WeatherSceneState(
    val kind: WeatherKind,
    val phase: SkyPhase,
    /** 降水强度 0–1：只影响粒子数量与不透明度，不影响背景色。 */
    val intensity: Float = 0f,
    /** 风强 0–1：只影响漂移速度与云被拉长的程度。 */
    val wind: Float = 0.3f,
) {
    val isNight: Boolean get() = phase == SkyPhase.NIGHT

    val overcast: Float
        get() = (kind.baseOvercast + intensity * 0.15f).coerceAtMost(1f)

    val rig: CloudLightRig get() = lightRigFor(phase, overcast)

    /**
     * 照片级云素材的色调。素材自带暖顶冷底，夜里必须压成中性灰，否则夜空里
     * 会浮着一团棕云——实测南风夜档的云带是 #45464A 这种中性灰。
     */
    val photoTint: Color
        get() = when (phase) {
            SkyPhase.NIGHT -> Color(0xFF6E7076)
            SkyPhase.EVENING -> Color(0xFFD8C8C0)
            SkyPhase.MORNING -> Color(0xFFE8E2DC)
            SkyPhase.DAY -> Color(0xFFE4E8EE)
        }
}

/**
 * 该状况需要哪几层云场。
 *
 * 这是「阴天为什么重、晴天为什么轻」的落点。南风的代码量分布是
 * 晴 ~88 行、多云 ~90 行、雪 ~83 行，而**阴 ~275 行、雨 ~326 行**——
 * 难画透的两种天气吃了最多投入。这里照同一取向分配，但把差异写成数据而不是
 * 每个天气一个 Effect 函数：新增「多云的第 N 层」不必再写一个函数。
 */
fun cloudFieldsFor(state: WeatherSceneState): List<CloudFieldSpec> {
    val stretch = 1f + state.wind * 0.6f
    return when (state.kind) {
        WeatherKind.CLEAR -> listOf(
            // 只有一层高空卷云，稀疏、淡。
            CloudFieldSpec(CloudSpecies.PHOTO_WISP, 2, 0.10f..0.24f, 0.010f, 0.9f * stretch, 0.30f, 11),
            CloudFieldSpec(CloudSpecies.CUMULUS, 1, 0.30f..0.38f, 0.016f, 0.55f, 0.22f, 23),
        )

        WeatherKind.PARTLY_CLOUDY -> listOf(
            CloudFieldSpec(CloudSpecies.PHOTO_WISP, 2, 0.08f..0.20f, 0.012f, 0.95f * stretch, 0.34f, 31),
            CloudFieldSpec(CloudSpecies.CUMULUS, 3, 0.16f..0.34f, 0.022f, 0.62f, 0.62f, 41),
            // 近景一朵，压住内容上沿，形成纵深。
            CloudFieldSpec(CloudSpecies.PHOTO_CUMULUS, 1, 0.36f..0.48f, 0.040f, 0.85f, 0.55f, 53),
        )

        // [实测] 南风的阴：云带占据屏高 0.11–0.25、核心亮度 #5B5C60，下缘参差、
        // 再往下就是干净的天空。所以三层集中在上半带，不做满屏铺云。
        WeatherKind.CLOUDY -> listOf(
            CloudFieldSpec(CloudSpecies.STRATUS, 3, 0.05f..0.14f, 0.012f, 1.25f * stretch, 0.62f, 61),
            CloudFieldSpec(CloudSpecies.STRATUS, 3, 0.12f..0.22f, 0.020f, 1.15f * stretch, 0.95f, 71),
            CloudFieldSpec(CloudSpecies.CUMULUS, 2, 0.17f..0.27f, 0.030f, 0.85f, 0.82f, 83),
        )

        WeatherKind.RAIN, WeatherKind.THUNDER -> listOf(
            // 照片级暗云堤压住天顶，烘焙层云叠在其下做运动层。
            CloudFieldSpec(CloudSpecies.PHOTO_BANK, 2, 0.03f..0.14f, 0.010f, 1.30f * stretch, 0.42f, 89),
            CloudFieldSpec(CloudSpecies.STRATUS, 4, 0.06f..0.22f, 0.030f * stretch, 1.25f * stretch, 0.80f, 91),
            CloudFieldSpec(CloudSpecies.STRATUS, 3, 0.20f..0.34f, 0.052f * stretch, 1.10f * stretch, 0.92f, 97),
        )

        WeatherKind.SNOW -> listOf(
            CloudFieldSpec(CloudSpecies.STRATUS, 3, 0.08f..0.24f, 0.012f, 1.10f, 0.60f, 101),
            CloudFieldSpec(CloudSpecies.CUMULUS, 2, 0.26f..0.40f, 0.020f, 0.85f, 0.68f, 103),
        )

        WeatherKind.FOG -> listOf(
            CloudFieldSpec(CloudSpecies.STRATUS, 3, 0.18f..0.42f, 0.008f, 1.30f, 0.42f, 111),
        )

        WeatherKind.WIND -> listOf(
            // 风天靠「被拉长的丝状云 + 快漂移」表达，不额外加粒子。
            CloudFieldSpec(CloudSpecies.PHOTO_WISP, 3, 0.10f..0.30f, 0.085f, 1.20f * stretch, 0.40f, 121),
            CloudFieldSpec(CloudSpecies.CUMULUS, 2, 0.30f..0.42f, 0.070f, 0.75f * stretch, 0.45f, 127),
        )
    }
}
