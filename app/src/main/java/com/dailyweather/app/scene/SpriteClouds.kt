package com.dailyweather.app.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
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
    /** 摆动角速度基准（数值沿用旧"屏宽/秒"表 ×18 换算），wind 拉伸；风大摆得快。 */
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
    // 晴/晴转多云共用一套规格：两者天空帧本就是同一组（8 帧积云），若云精灵数量不同，
    // 数据源在 CLEAR↔PARTLY_CLOUDY 间每次翻牌都会触发 900ms 特效 Crossfade，双份云
    // 临时叠加 → 顶部天空"一会儿深一会儿浅"。规格一致后翻牌只变卡片图标，天空零跳动。
    val sunny = listOf(
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
    return when (state.kind) {
        WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY -> sunny

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
 * 在 DrawScope 上绘制一层云精灵 —— 南风 L4/A3 的 1:1 复刻（逆向文档五·5.1/5.2）。
 *
 * 与旧线性穿屏方案的区别（对齐南风逆向）：
 * - **放置（L4）**：正弦振荡 + 循环半宽——每朵云绕自己的基准点缓慢摆动，
 *   振幅 = max(云最大边, 云宽×50%+3)，摆幅足够时云可完整飘出屏幕再摆回来；
 *   不再"从左飞到右再瞬移回卷"。底部受 bottomFactor 约束（云不越地平线）。
 * - **阴影（A3）**：按 天气×时段 给精灵上亮度保持的 tint——晨金 / 暮橙 /
 *   雨雷冷灰 / 雪冷白 / 雾灰白 / 夜蓝灰，昼晴保持原色；景深越远 alphaScale 越低。
 * - **变换（A3）**：呼吸缩放 ±2%（driftPhase 相位）+ 垂直偏斜 skew 0.4（phase 相位），
 *   云团缓慢"翻腾"的伪 3D 感（南风实测参数 1:1）。
 *
 * 每朵云的基准/相位/尺寸由 seed 哈希决定（稳定，不随帧跳变，可复现）。
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
    val image = bitmap.asImageBitmap()

    // ---- 南风 A3 阴影：按 天气×时段 的 tint（亮度保持），整层共用一个 filter ----
    val shadeColor: Color = when {
        state.phase == SkyPhase.MORNING -> Color(0xFFE8C48E)  // MorningCloudShades 金调
        state.phase == SkyPhase.EVENING -> Color(0xFFE8A276)  // EveningCloudShades 橙红
        state.kind == WeatherKind.RAIN || state.kind == WeatherKind.THUNDER ->
            Color(0xFFB9C2CE)                                  // Rain/StormCloudTint 冷灰
        state.kind == WeatherKind.SNOW || state.kind == WeatherKind.SLEET ->
            Color(0xFFDDE7F2)                                  // SnowCloudShade 冷白
        state.kind == WeatherKind.FOG -> Color(0xFFD9DDE3)     // FogWispShade 灰白
        state.isNight -> Color(0xFF9FA9BC)                     // *CloudShadeNight 蓝灰
        else -> Color(0xFFFFFFFF)                              // 昼晴：原色不 tint
    }
    val shadeFilter: ColorFilter? = if (shadeColor == Color.White) null else ColorFilter.colorMatrix(
        // 亮度保持 tint：RGB' = luma(RGB) × tint（对应南风 drawBitmap(…, shade.filter)）。
        ColorMatrix(
            floatArrayOf(
                0.213f * shadeColor.red, 0.715f * shadeColor.red, 0.072f * shadeColor.red, 0f, 0f,
                0.213f * shadeColor.green, 0.715f * shadeColor.green, 0.072f * shadeColor.green, 0f, 0f,
                0.213f * shadeColor.blue, 0.715f * shadeColor.blue, 0.072f * shadeColor.blue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    for (i in 0 until count) {
        // 南风 A3 的两个独立相位：driftPhase（呼吸缩放）/ phase（偏斜 + 水平初相）。
        val driftPhase = spriteHash01(spec.seed * 31 + i * 7)
        val phase = spriteHash01(spec.seed * 13 + i * 5)
        val yFrac = spec.altitude.start +
            (spec.altitude.endInclusive - spec.altitude.start) * spriteHash01(spec.seed * 17 + i * 9)
        val wFrac = spec.width.start +
            (spec.width.endInclusive - spec.width.start) * spriteHash01(spec.seed * 23 + i * 3)

        val w = wFrac * size.width
        val h = w * (bitmap.height.toFloat() / bitmap.width.toFloat())

        // ---- 南风 L4 放置：正弦振荡 + 循环半宽 ----
        // 基准点散布在屏幕中部 60%；振幅 = max(云最大边, 云宽×50%+3)，
        // 摆幅足够时云可完整飘出屏幕再摆回来；角速度随风拉伸（风大摆得快）。
        val baseX = (0.20f + 0.60f * spriteHash01(spec.seed * 29 + i * 13)) * size.width
        val loopHalf = maxOf(maxOf(w, h), w * 0.50f + 3f)
        val angular = (spec.speed * 18f * (0.5f + state.wind) *
            (0.7f + 0.6f * spriteHash01(spec.seed * 37 + i * 11))).coerceAtMost(0.9f)
        val left = baseX + loopHalf * sin(phase * 2f * PI.toFloat() + t * angular) - w / 2f
        // 南风 L4 底部约束：云不得越过 bottomFactor×屏高（地平线之上）。
        val top = min(yFrac * size.height - h / 2f, size.height * 0.75f - h)

        // 云心出屏羽化（摆出屏幕时不生硬）；顶区 12% 渐隐带（v0.1.29 防抖）保留。
        val cxN = ((left + w / 2f) / size.width).coerceIn(-0.3f, 1.3f)
        val topFade = (yFrac / 0.12f).coerceIn(0f, 1f)

        // 南风 A3 alphaScale：景深越远越淡（Far/Mid/Near 递增）。
        val depthNorm = if (count <= 1) 1f else i.toFloat() / (count - 1).toFloat()
        val alpha = (spec.alpha * (0.80f + 0.20f * depthNorm) * edgeFade(cxN) * topFade)
            .coerceIn(0f, 1f)
        if (alpha <= 0.005f) continue

        // ---- 南风 A3 变换：呼吸缩放 ±2%（driftPhase）+ 垂直偏斜 skew 0.4（phase）----
        val breatheScale = 1f + 0.02f * sin((0.05f * t + driftPhase) * 2f * PI.toFloat())
        val skewY = 0.4f * sin((0.04f * t + phase) * 2f * PI.toFloat())

        // 亚像素平移：位置走 withTransform{translate(Float)}，只有尺寸取整。
        // 高刷屏（120-165Hz）下每帧位移仅 2-5px，IntOffset 取整会产生可感知的
        // "走一步停一步"抖动 —— 用户实测的"云一卡一卡"第二根因。
        // 变换顺序对齐南风：绕云中心 → scale → skew（DrawScope 无 skew，用 Compose
        // Matrix 的 column-major values 手写：skew(sx=0, sy=k) ⇒ m10 = values[1] = k，
        // 与南风 canvas.skew(0,skewY) 的 x'=x, y'=y+k·x 同构）→ 平移回 → 绘制。
        withTransform({
            translate(left + w / 2f, top + h / 2f)
            scale(breatheScale, 1f)
            transform(
                // Matrix() 工厂即 identity，只需写入 skew 项 m10。
                androidx.compose.ui.graphics.Matrix().apply { values[1] = skewY },
            )
            translate(-w / 2f, -h / 2f)
        }) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                alpha = alpha,
                colorFilter = shadeFilter,
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
