package com.dailyweather.app.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.sin

/**
 * 云层精灵系统 —— 1:1 南风的 `weather_cloud_*` 精灵组合方案。
 *
 * 南风的做法：每种天气是**一组精灵规格**（用哪张 PNG、几朵、在什么高度、多大、
 * 多透明、漂多快），运行时只做水平漂移 + 环绕回卷，没有形状计算、没有烘焙。
 * 本文件照同一取向把差异写成**数据表**而不是函数：
 *
 * - 白天白云：cumulus_day_v1/v2/v3 三个变体轮换（自然感来自"每朵不一样"）
 * - 夜间白云：cumulus_v1 预烘焙暗色版（不靠 ColorFilter 变暗——素材本身就是夜的）
 * - 雨雷乌云：bank_day_v1/v2（白天）/ bank_v2（夜）——"压顶乌云"的基调层
 * - 高空薄云：wisp_v1——晴天上空的丝状卷云
 *
 * 漂移速度全部被 [WeatherSceneState.wind] 拉伸；回卷边界处 alpha 淡入淡出，
 * 避免精灵在屏边闪现（对应南风 WeatherEffectFadeDistance 的取向）。
 */

/** 单层云的规格：昼/夜精灵 × 数量 × 高度带 × 尺寸带 × alpha × 速度（屏宽/秒）。 */
data class SpriteCloudSpec(
    val day: SpriteAssets.CloudSprite,
    val night: SpriteAssets.CloudSprite,
    val count: Int,
    /** 云中心 y，占屏高分数。 */
    val altitude: ClosedFloatingPointRange<Float>,
    /** 云宽，占屏宽分数。 */
    val width: ClosedFloatingPointRange<Float>,
    val alpha: Float,
    /** 基准漂移速度（屏宽/秒），wind=0.3 时即此值。 */
    val speed: Float,
    /** 每层唯一 seed，保证同层多朵不重叠在同一相位。 */
    val seed: Int,
)

/**
 * 按天气取精灵规格表 —— 南风"阴 ~275 行 / 雨 ~326 行最重"的取向落成数据密度。
 *
 * 参数范式对齐南风逆向（u7.ib 规格：count=3~9、速度 0.005~0.02 屏宽/秒、
 * 尺寸 0.5~1.0+、alpha 0.3~0.9）：每层数量 3~6、大小成"团"、漂速放慢。
 * 云团感来自多层大云重叠，而不是单朵大云。
 */
fun spriteCloudsFor(state: WeatherSceneState): List<SpriteCloudSpec> {
    val stretch = 1f + state.wind * 1.2f
    return when (state.kind) {
        WeatherKind.CLEAR -> listOf(
            // 高空卷云 4 朵（南风 PHOTO_WISP count=3）。alpha 上调到"看得见的丝状云"。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.WISP, SpriteAssets.CloudSprite.WISP,
                4, 0.06f..0.18f, 0.50f..0.70f, 0.52f, 0.009f * stretch, 11,
            ),
            // 中景积云群 4 朵（南风 PHOTO_CUMULUS count=9 的"晴"档：明显而不密）。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_4, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                4, 0.18f..0.30f, 0.50f..0.66f, 0.46f, 0.010f * stretch, 13,
            ),
            // 低空积云 3 朵，近景更显眼（与背景帧拉开纵深；用户反馈晴天云"不够明显"）。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_1, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                3, 0.26f..0.36f, 0.44f..0.58f, 0.52f, 0.016f * stretch, 23,
            ),
        )

        WeatherKind.PARTLY_CLOUDY -> listOf(
            // 高空卷云 3 朵。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.WISP, SpriteAssets.CloudSprite.WISP,
                3, 0.05f..0.16f, 0.50f..0.68f, 0.40f, 0.011f * stretch, 31,
            ),
            // 高积云群：成片碎云填中层（"云阵"）。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.ALTO_DAY_1, SpriteAssets.CloudSprite.WISP,
                3, 0.10f..0.24f, 0.60f..0.85f, 0.45f, 0.012f * stretch, 35,
            ),
            // 白云朵朵：5 朵积云变体轮换（南风 CUMULUS count=9 的"多云"档）。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_2, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                5, 0.14f..0.32f, 0.45f..0.62f, 0.60f, 0.018f * stretch, 41,
            ),
            // 近景大积云 1 朵，贴镜头的纵深感（南风 PHOTO_CUMULUS float≈0.88 高 alpha 档）。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_3, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                1, 0.30f..0.42f, 0.70f..0.90f, 0.78f, 0.030f * stretch, 53,
            ),
        )

        WeatherKind.CLOUDY -> listOf(
            // 三层暗云堤铺满上 1/3：顶层薄、中层主、底层碎。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.BANK_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                4, 0.02f..0.10f, 0.75f..1.05f, 0.60f, 0.010f * stretch, 61,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.BANK_DAY_2, SpriteAssets.CloudSprite.BANK_NIGHT,
                4, 0.09f..0.20f, 0.85f..1.15f, 0.75f, 0.015f * stretch, 71,
            ),
            // 层云带：低平连续云幕填中景，阴天"整片"感。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.STRATUS_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                3, 0.12f..0.24f, 0.80f..1.10f, 0.55f, 0.014f * stretch, 73,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_1, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                3, 0.18f..0.30f, 0.50f..0.68f, 0.45f, 0.024f * stretch, 83,
            ),
        )

        WeatherKind.RAIN, WeatherKind.THUNDER -> listOf(
            // 压顶乌云：两层 bank 密铺，雨最重。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.BANK_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                4, 0.00f..0.08f, 0.85f..1.10f, 0.75f, 0.009f * stretch, 89,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.BANK_DAY_2, SpriteAssets.CloudSprite.BANK_NIGHT,
                4, 0.07f..0.17f, 0.90f..1.20f, 0.85f, 0.018f * stretch, 91,
            ),
            // 层云带：雨天的厚重云幕。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.STRATUS_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                3, 0.10f..0.20f, 0.90f..1.20f, 0.55f, 0.012f * stretch, 93,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_2, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                3, 0.16f..0.28f, 0.55f..0.72f, 0.50f, 0.028f * stretch, 97,
            ),
        )

        WeatherKind.SNOW, WeatherKind.SLEET -> listOf(
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.BANK_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                3, 0.03f..0.12f, 0.75f..0.95f, 0.55f, 0.010f * stretch, 101,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.STRATUS_DAY_1, SpriteAssets.CloudSprite.BANK_NIGHT,
                3, 0.14f..0.26f, 0.80f..1.05f, 0.45f, 0.014f * stretch, 105,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_5, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                2, 0.16f..0.30f, 0.50f..0.66f, 0.40f, 0.018f * stretch, 103,
            ),
        )

        WeatherKind.FOG -> listOf(
            // 雾：低空薄云缓慢漂，压得低、拉得宽。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.WISP, SpriteAssets.CloudSprite.WISP,
                3, 0.18f..0.40f, 0.85f..1.10f, 0.42f, 0.006f * stretch, 111,
            ),
        )

        WeatherKind.WIND -> listOf(
            // 风天：丝状云被拉长 + 快漂移。
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.WISP, SpriteAssets.CloudSprite.WISP,
                3, 0.08f..0.24f, 0.55f..0.75f, 0.40f, 0.075f * stretch, 121,
            ),
            SpriteCloudSpec(
                SpriteAssets.CloudSprite.CUMULUS_DAY_2, SpriteAssets.CloudSprite.CUMULUS_NIGHT,
                4, 0.26f..0.38f, 0.46f..0.60f, 0.48f, 0.060f * stretch, 127,
            ),
        )
    }
}

/**
 * 在 DrawScope 上绘制一层云精灵。
 *
 * 布局：每朵的纵向位置/尺寸/相位由 seed 哈希决定（稳定，不随帧跳变）；
 * 横向 = 初始相位 + 时间×速度，行程 [-margin, 1+margin] 内环绕回卷，
 * 回卷边界处 alpha 淡入淡出，云"从雾里飘进来、飘进雾里消失"。
 */
fun DrawScope.drawSpriteCloudLayer(
    spec: SpriteCloudSpec,
    state: WeatherSceneState,
    bitmap: Bitmap?,
    t: Float,
    quality: EffectQuality,
) {
    if (bitmap == null || quality.particleScale <= 0f) return
    val count = (spec.count * quality.particleScale).toInt().coerceAtLeast(1)
    val windBoost = 0.5f + state.wind
    val margin = 0.20f
    val travelSpan = 1f + 2 * margin
    val image = bitmap.asImageBitmap()

    for (i in 0 until count) {
        val phase = spriteHash01(spec.seed * 31 + i * 7)
        val yFrac = spec.altitude.start +
            (spec.altitude.endInclusive - spec.altitude.start) * spriteHash01(spec.seed * 13 + i * 5)
        val wFrac = spec.width.start +
            (spec.width.endInclusive - spec.width.start) * spriteHash01(spec.seed * 17 + i * 9)
        val speed = spec.speed * (0.7f + 0.6f * spriteHash01(spec.seed * 23 + i * 3)) * windBoost

        val travel = (phase * travelSpan + t * speed) % travelSpan
        val xFrac = travel - margin

        // 回卷边界羽化：进入（travel < margin）与离开（travel > span-margin）时 alpha→0。
        val fadeIn = (travel / margin).coerceIn(0f, 1f)
        val fadeOut = ((travelSpan - travel) / margin).coerceIn(0f, 1f)
        val edgeAlpha = min(fadeIn, fadeOut)

        // 呼吸感：极缓慢的 alpha 起伏（周期 ~11s，幅度 ±12%）。
        val breathe = 0.88f + 0.12f * (sin((t / 11f + phase * 6f) * 2f * Math.PI.toFloat()) + 1f) / 2f

        val alpha = (spec.alpha * edgeAlpha * breathe).coerceIn(0f, 1f)
        if (alpha <= 0.005f) continue

        val w = wFrac * size.width
        val h = w * (bitmap.height.toFloat() / bitmap.width.toFloat())
        val x = xFrac * size.width
        val y = yFrac * size.height - h / 2f

        // 亚像素平移：位置走 withTransform{translate(Float)}，只有尺寸取整。
        // 高刷屏（120-165Hz）下每帧位移仅 2-5px，IntOffset 取整会产生可感知的
        // "走一步停一步"抖动 —— 用户实测的"云一卡一卡"第二根因。
        withTransform({ translate(x, y) }) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                alpha = alpha,
            )
        }
    }
}

/** 与旧引擎一致的稳定哈希（seed → 0..1），保证云的位置跨帧稳定。 */
internal fun spriteHash01(seed: Int): Float {
    var h = seed * 374761393
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)).toInt() and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
}
