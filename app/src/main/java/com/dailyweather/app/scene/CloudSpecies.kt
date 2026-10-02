package com.dailyweather.app.scene

import androidx.annotation.DrawableRes
import com.dailyweather.app.R

/**
 * 云的物种。
 *
 * 取自南风字符串池里真实存在的 species 常量：`CUMULUS` / `CUMULUS_DAY` / `STRATUS` /
 * `STRATUS_DAY` / `PHOTO_CUMULUS` / `PHOTO_WISP`，以及 `SceneCloudConfig(species=…)`、
 * `CloudBakeKey(species=…)`、`isWisp`、`getSpecies` 这几个入口。
 *
 * 与南风的一处刻意差异：它把昼/夜编进了物种名（`CUMULUS` 与 `CUMULUS_DAY` 是两个物种），
 * 一旦「时段」进了物种就会和 `SkyPhase` 相乘爆炸。这里只让物种回答
 * 「这朵云是什么形状、是白模还是照片」，亮到什么颜色由 [CloudLightRig] 决定。
 */
enum class CloudSpecies(
    /** 该物种可用的形状变体（同物种不同轮廓，供云场随机取用，避免重复感）。 */
    @param:DrawableRes val shapes: IntArray,
    /**
     * true = 素材是 `8-bit gray+alpha` 的白模剪影，本身没有颜色，必须经
     * [CloudBaker] 按光照档烘焙；false = RGBA 照片级成品云，自带明暗，不走烘焙。
     *
     * 依据：`file` 实测 `weather_cloud_cumulus_day_v1.png` = 720×404 8-bit gray+alpha，
     * 而 `weather_cloud_cumulus_v1.png` = 768×768 RGBA。
     */
    val isMask: Boolean,
) {
    /** 积云：竖向有起伏的团块，晴天与多云的主力。 */
    CUMULUS(
        intArrayOf(
            R.drawable.weather_cloud_cumulus_day_v1,
            R.drawable.weather_cloud_cumulus_day_v2,
            R.drawable.weather_cloud_cumulus_day_v3,
        ),
        isMask = true,
    ),

    /** 层云 / 云堤：横向铺开的长条，阴天与雨天的主力。 */
    STRATUS(
        intArrayOf(
            R.drawable.weather_cloud_bank_day_v1,
            R.drawable.weather_cloud_bank_day_v2,
        ),
        isMask = true,
    ),

    /** 照片级积云：自带暖顶冷底的光影，用于近景与前景云。 */
    PHOTO_CUMULUS(
        intArrayOf(R.drawable.weather_cloud_cumulus_v1),
        isMask = false,
    ),

    /** 照片级云堤：768×512 的实拍暗云，压在雨天的天顶当基调。 */
    PHOTO_BANK(
        intArrayOf(R.drawable.weather_cloud_bank_v2),
        isMask = false,
    ),

    /** 照片级卷云：横向丝状，用于晴天的 cirrus 层。 */
    PHOTO_WISP(
        intArrayOf(R.drawable.weather_cloud_wisp_v1),
        isMask = false,
    ),
    ;

    /** 照片级素材自带光影、不走烘焙；低质量档下近景前景首先砍掉它。 */
    val isPhoto: Boolean get() = !isMask
}


/** 云场的「一片」：同物种下若干朵云的分布描述（对应南风的 `…WispField` / `SceneCloud`）。 */
data class CloudFieldSpec(
    val species: CloudSpecies,
    val count: Int,
    /** 云心所在的纵向区间，取 0–1 分数（南风的粒子一律用分数而非像素）。 */
    val yFractionRange: ClosedFloatingPointRange<Float>,
    /** 相对漂移速度倍率：远景小、近景大，形成视差。 */
    val driftSpeed: Float,
    /** 目标显示宽度占屏宽的分数，高度按素材宽高比推出。 */
    val widthFraction: Float,
    val alpha: Float,
    /** 相位种子：同一场景里不同层用不同种子，避免同步运动被看出来。 */
    val seed: Int,
)
