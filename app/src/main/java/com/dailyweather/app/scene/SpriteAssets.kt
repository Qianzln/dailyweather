package com.dailyweather.app.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 南风精灵资产加载器 —— 1:1 复刻南风的「PNG 精灵帧」方案。
 *
 * 南风的天气动画不是程序化粒子，而是**预烘焙的 PNG 帧序列 + 精灵图**：
 * - 天空背景：8 帧积云天空（720×808）/ 3 帧薄云天空（720×720），逐帧循环
 * - 云层精灵：独立的积云/层云/薄云 PNG，按天气组合、按视差速度漂移
 * - 降水精灵：20×68 雨滴条
 *
 * 所有位图进程内缓存（LruCache），同一路径只解码一次。
 * 天空帧用 RGB_565（不透明照片，内存减半）；云精灵必须 ARGB_8888（带 alpha）。
 */
object SpriteAssets {

    private const val MAX_CACHE_MB = 48

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_MB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / (1024 * 1024)
    }

    /** 从 assets 加载位图并缓存。失败返回 null（缺资源时上层降级到渐变天空）。 */
    fun load(context: Context, assetPath: String, opaque: Boolean): Bitmap? {
        cache.get(assetPath)?.let { return it }
        val bmp = runCatching {
            context.assets.open(assetPath).use { input ->
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull() ?: return null
        cache.put(assetPath, bmp)
        return bmp
    }

    /** 从 res/drawable 加载位图并缓存（雨丝等保留在 res 里的南风原版素材）。 */
    fun loadResource(context: Context, resId: Int): android.graphics.Bitmap? {
        val key = "res/$resId"
        cache.get(key)?.let { return it }
        val bmp = runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, resId)
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 冷启动预热 —— 对应南风 SkyPulseApp 的"天气精灵资源预热"。
     *
     * 全部在 IO 线程解码，首帧组合时缓存已热：主线程组合只做 `cache.get`（O(1)），
     * 不再同步解码大 PNG（720×808 帧单张 ~2.3MB 解码，11 张串行在主线程必 ANR）。
     */
    fun prewarm(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            (SKY_CUMULUS_FRAMES + SKY_WISP_FRAMES).forEach { load(context, it, opaque = false) }
            CloudSprite.entries.forEach { load(context, it.asset, opaque = false) }
            prewarmed = true
        }
    }

    /** 预热进度 0..1：UI 可选地等待/淡入；非必须，未命中时渐变底兜底。 */
    @Volatile var prewarmed: Boolean = false
        private set

    // ---- 天空背景帧 ----
    // 来源：南风 v4.3.64 res/weather_sky_*（经 aapt2 定位 PNG 后原样拷贝）。

    private const val SKY_DIR = "skyframes"

    /** 积云天空：8 帧，晴天/多云/风天用。 */
    val SKY_CUMULUS_FRAMES: List<String> =
        (1..8).map { "$SKY_DIR/cumulus_%02d.png".format(it) }

    /** 薄云天空：3 帧，阴天/雾天/降水用。 */
    val SKY_WISP_FRAMES: List<String> =
        (1..3).map { "$SKY_DIR/wisp_%02d.png".format(it) }

    fun skyFramesFor(kind: WeatherKind): List<String> = when (kind) {
        WeatherKind.CLEAR, WeatherKind.PARTLY_CLOUDY, WeatherKind.WIND -> SKY_CUMULUS_FRAMES
        else -> SKY_WISP_FRAMES
    }

    // ---- 云层精灵 ----

    private const val CLOUD_DIR = "cloudsprites"

    enum class CloudSprite(val asset: String, val opaque: Boolean = false) {
        /** 白色积云 · 白天，3 个变体（720×404）。 */
        CUMULUS_DAY_1("$CLOUD_DIR/weather_cloud_cumulus_day_v1.png"),
        CUMULUS_DAY_2("$CLOUD_DIR/weather_cloud_cumulus_day_v2.png"),
        CUMULUS_DAY_3("$CLOUD_DIR/weather_cloud_cumulus_day_v3.png"),

        /** 大积云 · 白天 v4/v5（768×512，自绘补充变体）——成"团"的主力。 */
        CUMULUS_DAY_4("$CLOUD_DIR/weather_cloud_cumulus_day_v4.png"),
        CUMULUS_DAY_5("$CLOUD_DIR/weather_cloud_cumulus_day_v5.png"),

        /** 高积云群 · 白天（鱼鳞碎云成片，半透明）——中景"云阵"。 */
        ALTO_DAY_1("$CLOUD_DIR/weather_cloud_altocumulus_v1.png"),

        /** 层云带 · 白天（低平厚云基座）——阴雨天的连续云幕。 */
        STRATUS_DAY_1("$CLOUD_DIR/weather_cloud_stratus_v1.png"),

        /** 积云 · 夜间预烘焙暗色版（768×768）。 */
        CUMULUS_NIGHT("$CLOUD_DIR/weather_cloud_cumulus_v1.png"),

        /** 暗云堤 · 白天 2 帧（720×360）——雨/雷/阴的"压顶乌云"。 */
        BANK_DAY_1("$CLOUD_DIR/weather_cloud_bank_day_v1.png"),
        BANK_DAY_2("$CLOUD_DIR/weather_cloud_bank_day_v2.png"),

        /** 暗云堤 · 夜间版（768×512）。 */
        BANK_NIGHT("$CLOUD_DIR/weather_cloud_bank_v2.png"),

        /** 高空薄云卷云（768×422）。 */
        WISP("$CLOUD_DIR/weather_cloud_wisp_v1.png"),
    }

    // ---- 降水精灵 ----

    /** 雨滴条：res/drawable-nodpi 已有原版拷贝（20×68 灰度+alpha）。 */
    // 走 R.drawable.weather_rain_streak_mgl，不进 assets。
}
