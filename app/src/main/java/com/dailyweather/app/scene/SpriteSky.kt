package com.dailyweather.app.scene

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Compose 响应式精灵位图：缓存未热时在 IO 线程解码（绝不在主线程解码大 PNG），
 * 完成后写入 state 触发重组；未就绪期间返回 null，上层以渐变底兜底。
 */
@Composable
fun rememberSpriteBitmap(assetPath: String, opaque: Boolean): android.graphics.Bitmap? {
    val context = LocalContext.current
    var bmp by remember(assetPath) { mutableStateOf(SpriteAssets.load(context, assetPath, opaque)) }
    LaunchedEffect(assetPath) {
        if (bmp == null) {
            val loaded = withContext(Dispatchers.IO) { SpriteAssets.load(context, assetPath, opaque) }
            if (loaded != null) bmp = loaded
        }
    }
    return bmp
}

/**
 * 天空精灵帧层 —— 1:1 南风的 `weather_sky_cumulus_01~08` / `weather_sky_wisp_01~03`。
 *
 * 南风的做法：整幅天空（含云）预渲染成 PNG 帧序列，运行时只做**逐帧轮播 + 帧间交叉淡化**，
 * 没有任何程序化云。本层照抄：
 * - 帧选择按天气：晴/多云/风 → 8 帧积云天空；阴/雾/降水 → 3 帧薄云天空
 * - 轮播节奏：每帧 2.4s，帧间 700ms Crossfade（肉眼是"云在缓缓流动"而不是"翻页"）
 * - 夜间/时段氛围：帧之上叠一层渐变 tint（素材只有日间版，夜间靠预烘焙暗色云精灵 +
 *   本层深蓝 tint 表达；清晨/傍晚用暖金 tint 保持"上冷下暖"）
 */
object SpriteSky {
    /** 每帧停留时长。3.2s——配合 1.1s 交叉淡化，两张照片帧的重叠期更长，切换更无形。 */
    const val FRAME_MILLIS = 3200L

    /** 帧间交叉淡化时长。南风原版 700ms 的"翻页感"在帧流化后显得格格不入，加长。 */
    const val CROSSFADE_MILLIS = 1100

    /** 帧流化漂移速度（屏宽/秒）。约 125s 漂满一屏，慢而可见；云场 cover 后 x 方向
     *  有巨大冗余，fract 环绕完全无缝，不会露出边缘。 */
    const val DRIFT_SPEED = 0.008f

    /** 呼吸缩放幅度。±1.5%：云团缓慢"翻腾"的伪 3D 感，帧切换时两帧错位更自然。 */
    const val BREATHE_AMPLITUDE = 0.015f

    /** 呼吸缩放周期（秒）。20s 一个大循环，比任何帧停留都长，看不出节奏。 */
    const val BREATHE_PERIOD_S = 20f

    /**
     * 云场帧的目标色 —— 南风"127 中性灰基准精灵 × 光照档"架构。
     *
     * 素材实测：weather_sky_* 帧的云以 R=G=B≈127 为基准亮度（立体感编码在
     * 与 128 的偏移里），43.9% 像素全透明（露出渐变天空）。渲染时用色彩矩阵
     * 做 `out = tint + (in - 128)` 重映射：中性灰→tint 色，亮部更亮、暗部更暗，
     * 对比保持。取值沿用南风逆向的 photoTint 语义：
     * 晴/多云暖奶油、阴/雨/雪/雷中性灰、夜强制暗灰。
     */
    fun cloudTint(state: WeatherSceneState): Color = when {
        // 夜间云：南风实图是灰白色、清晰可见（不是暗灰剪影）——alpha 提取后云体
        // 本来就柔和，tint 给到中亮灰蓝。
        state.isNight -> Color(0xFF98A2B0)
        state.kind in listOf(
            WeatherKind.CLOUDY, WeatherKind.RAIN, WeatherKind.THUNDER,
            WeatherKind.SNOW, WeatherKind.SLEET,
        ) -> Color(0xFF8A9098)
        else -> Color(0xFFF2EDE5)
    }

    /**
     * 云形提取矩阵：R 通道高亮区 = 云（alpha = (R-128)×2），RGB 恒为 tint。
     *
     * 素材实测：上半帧 R≈128 处为天空（alpha→0 露出渐变底），R>128 为云体
     * （云心实、云缘淡，立体感由 alpha 渐变承载）。直接整幅重映射会变成
     * 一层米白罩死天空（实测翻车）；G/B 通道的偏差是次级细节与边缘光，
     * alpha 提取后由 tint 层统一表达光照。
     */
    fun cloudColorMatrix(tint: Color, layerAlpha: Float): androidx.compose.ui.graphics.ColorMatrix {
        return androidx.compose.ui.graphics.ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, tint.red * 255f,
                0f, 0f, 0f, 0f, tint.green * 255f,
                0f, 0f, 0f, 0f, tint.blue * 255f,
                2f, 0f, 0f, 0f, -256f,
            ),
        )
    }

    /** 时段 tint（叠在帧上，保持素材重用）：清晨暖金 / 傍晚橙 / 夜深蓝。
     *  alpha 取"压暗但保纹理"档：素材是白天帧，夜 tint 过重会把云纹完全盖平（实测 80% 时纹理全灭）。 */
    fun phaseTint(state: WeatherSceneState): List<Pair<Float, Color>> = when (state.phase) {
        SkyPhase.MORNING -> listOf(
            0f to Color(0x002E4A8A),
            0.55f to Color(0x147A9BC4),
            1f to Color(0x2EF5C070),
        )
        SkyPhase.DAY -> listOf(
            0f to Color(0x00000000),
            1f to Color(0x00000000),
        )
        SkyPhase.EVENING -> listOf(
            0f to Color(0x382A2060),
            0.6f to Color(0x26B06070),
            1f to Color(0x38FF9040),
        )
        SkyPhase.NIGHT -> listOf(
            0f to Color(0x8C050C1C),
            0.55f to Color(0x730E1A38),
            1f to Color(0x591E2D50),
        )
    }

    /** 降水/雾的压暗 tint：素材是日间晴空，雨雪雾天整体压灰压暗（同样保纹理）。 */
    fun weatherTint(state: WeatherSceneState): List<Pair<Float, Color>> = when (state.kind) {
        WeatherKind.RAIN -> listOf(0f to Color(0x40303F4E), 1f to Color(0x59222E3A))
        WeatherKind.THUNDER -> listOf(0f to Color(0x59242E3C), 1f to Color(0x73161E28))
        WeatherKind.CLOUDY -> listOf(0f to Color(0x333D4A56), 1f to Color(0x4D2E3A46))
        WeatherKind.FOG -> listOf(0f to Color(0x409AA6B0), 1f to Color(0x59B8C2CA))
        WeatherKind.SNOW, WeatherKind.SLEET -> listOf(0f to Color(0x336E7E8E), 1f to Color(0x4D8898A8))
        else -> listOf(0f to Color(0x00000000), 1f to Color(0x00000000))
    }
}

/** 天空精灵帧背景：渐变底（兜底/加载中）→ PNG 帧轮播 → 时段与天气 tint。 */
@Composable
fun SpriteSkyBackground(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
    overcast: Float,
) {
    val context = LocalContext.current
    val frames = remember(state.kind) { SpriteAssets.skyFramesFor(state.kind) }

    // 帧流化时钟：与噪声雾层共用同一时钟，Alex 帧平移/呼吸/噪声全部同源连续。
    // ambient<=0（静态/关闭档）时时钟不起，帧仍轮播但静止，与旧行为一致。
    val quality = LocalEffectQuality.current
    val framesMove = quality.fps.ambient > 0
    val clock = rememberEffectClock(quality.fps.ambient.coerceAtLeast(24))

    androidx.compose.foundation.layout.Box(modifier) {
        // 渐变底：帧解码前的首帧兜底 + 天气色调基座（沿用原 SkyGradientStops 实测色）。
        val base = skyStopsFor(state.phase, overcast)
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to base.zenith,
                    base.midStop to base.mid,
                    1f to base.horizon,
                ),
            )
        }

        // GLSL/AGSL 噪声云层（API 33+）：FBM 连续演化，填补 8 帧轮播的
        // 离散停顿感——从"氛围"升级为带明暗的连续云，垫在渐变底与 PNG 帧之间。
        NoiseHazeLayer(state, Modifier.matchParentSize(), clock)

        // 帧轮播：index 离散推进，Crossfade 负责帧间淡化。
        var index by remember(frames) { mutableIntStateOf(0) }
        LaunchedEffect(frames) {
            while (true) {
                delay(SpriteSky.FRAME_MILLIS)
                index = (index + 1) % frames.size
            }
        }
        Crossfade(
            targetState = frames[index],
            animationSpec = tween(SpriteSky.CROSSFADE_MILLIS),
            label = "skyFrames",
            modifier = Modifier.matchParentSize(),
        ) { path ->
            val bmp = rememberSpriteBitmap(path, opaque = false)
            // 素材结构（实测）：720×808 = 上下两个 720×404 子图拼贴。
            // 上半：不透明云场（RGB≈128 基准 + 立体感偏差）——用作全屏天空云层；
            // 下半：透明底的精灵式云——与 weather_cloud_* 同构，另行复用。
            // 注意：Crossfade content 不是外层 BoxScope 直接子项，matchParentSize
            // 在此未定义（实测尺寸算 0 → 帧不渲染），必须 fillMaxSize。
            // 帧层顶部 20% DstIn 渐隐蒙版（0.1.20 引入，多版实测无黑带；保留）。
            if (bmp != null) {
                Canvas(
                    Modifier.fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.20f to Color.White,
                                    1f to Color.White,
                                    startY = 0f,
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    // 帧流化：静止帧 → 连续漂移 + 呼吸缩放。
                    // 1) 漂移：dx = -(t×speed mod 1)×屏宽，基于同一全局时钟 → 帧切换时
                    //    位移连续，Crossfade 里两帧云形交错淡化，肉眼看不到"翻页"；
                    // 2) 呼吸：±1.5% 极慢缩放（每帧相位错开），云团像在翻腾而非贴图平移。
                    val t = if (framesMove) clock.floatValue else 0f
                    val driftX = -((t * SpriteSky.DRIFT_SPEED) % 1f) * size.width
                    val breathe = 1f + SpriteSky.BREATHE_AMPLITUDE * kotlin.math.sin(
                        ((t / SpriteSky.BREATHE_PERIOD_S) * 2f * kotlin.math.PI.toFloat()) +
                            frames.indexOf(path) * 1.3f,
                    )
                    val halfH = bmp.height / 2
                    // cover 缩放：等比放大至铺满，居中裁剪（等价 ContentScale.Crop）。
                    val scale = maxOf(size.width / bmp.width, size.height / halfH) * breathe
                    val dstW = bmp.width * scale
                    val dstH = halfH * scale
                    val dx = (size.width - dstW) / 2f + driftX
                    val dy = (size.height - dstH) / 2f
                    withTransform({ translate(dx, dy) }) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            srcSize = androidx.compose.ui.unit.IntSize(bmp.width, halfH),
                            dstOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                            dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                SpriteSky.cloudColorMatrix(SpriteSky.cloudTint(state), layerAlpha = 1f),
                            ),
                        )
                    }
                }
            }
        }

        // tint 层：时段 × 天气，两层渐变叠加（夜间整体压暗，雨雪雾压灰）。
        Canvas(Modifier.matchParentSize()) {
            val phaseStops = SpriteSky.phaseTint(state).filter { it.second.alpha > 0f }
            if (phaseStops.isNotEmpty()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = phaseStops.toTypedArray(),
                        startY = 0f, endY = size.height,
                    ),
                )
            }
            val weatherStops = SpriteSky.weatherTint(state).filter { it.second.alpha > 0f }
            if (weatherStops.isNotEmpty()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = weatherStops.toTypedArray(),
                        startY = 0f, endY = size.height,
                    ),
                )
            }
        }
    }
}
