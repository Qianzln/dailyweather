package com.dailyweather.app.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 程序化粒子精灵工厂 —— 对应南风的 `ParticleSprites`。
 *
 * 关键结论（来自逆向报告 02 章）：雨、雪、辉光等天气粒子**全部由代码运行时生成位图**，
 * 一张外部图片都没有。这样既保证风格统一，又能按昼夜/主题实时染色，预热后零 IO。
 *
 * 每帧只对已生成的 sprite 做平移/旋转/缩放和一次颜色矩阵 —— 这是粒子动画
 * 在移动端保持流畅的关键，而不是逐像素重算。
 */
object ParticleSprites {

    /** 辉光 128×128 径向渐变。centerAlpha 越小越"软"。 */
    private const val GLOW_SOFT = 0.5f

    private const val GLOW_SIZE = 128
    private const val METEOR_W = 32
    private const val METEOR_H = 512
    private const val SNOW_SIZE = 96
    private const val SNOW_FRAMES = 24

    @Volatile private var glow: ImageBitmap? = null
    @Volatile private var meteor: ImageBitmap? = null
    @Volatile private var snowflakes: List<ImageBitmap>? = null

    fun glowSprite(): ImageBitmap? = glow

    fun meteorSprite(): ImageBitmap? = meteor

    /** 24 帧自旋雪花：帧 i 转了 i×(360/24)°，循环播放即得连续自旋。 */
    fun snowflakeFrames(): List<ImageBitmap>? = snowflakes

    /**
     * 冷启动预热 —— 对应 SkyPulseApp.onCreate 的"天气粒子资源预热"。
     * 全部在后台线程生成，首帧组合时缓存已热，动画零卡顿进入。
     */
    fun prewarm(context: Context) {
        if (glow != null && meteor != null && snowflakes != null) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            generateAll()
        }
    }

    /** 生成全部精灵（预热协程调用）。幂等，纯 CPU，几十毫秒量级。 */
    suspend fun generateAll() = kotlinx.coroutines.withContext(Dispatchers.Default) {
        glow = buildGlow().asImageBitmap()
        meteor = buildMeteor().asImageBitmap()
        snowflakes = buildSnowflakes().map { it.asImageBitmap() }
    }

    // ------------------------------------------------------------------
    // 辉光：径向渐变，太阳光晕 / 雾晕 / 闪电辉光共用同一张。
    // ------------------------------------------------------------------
    private fun buildGlow(): Bitmap {
        val size = GLOW_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size / 2f, size / 2f,
                intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, GLOW_SOFT, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bmp
    }

    // ------------------------------------------------------------------
    // 雨滴"流星"：32×512 竖长条，头部亮、尾迹渐隐、核心高光三段式。
    // 灰度图，绘制时 SrcIn tint 换色（夜冷蓝/昼亮白），不用两套素材。
    // ------------------------------------------------------------------
    private fun buildMeteor(): Bitmap {
        val w = METEOR_W
        val h = METEOR_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 头部亮、尾迹渐隐的竖直渐变；绘制时 SrcIn tint 换色（夜冷蓝/昼亮白）。
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x26FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.08f, 0.30f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val path = android.graphics.Path()
        // 头部圆一点、尾部收窄：对称轮廓模拟风阻拉长的水滴，边缘加正弦抖动避免"刀切"。
        for (y in 0..h) {
            val f = y / h.toFloat()
            val halfW = w / 2f * (0.62f - 0.52f * f * f) * (1f + 0.08f * sin(f * 40f))
            path.addRect(w / 2f - halfW, y.toFloat(), w / 2f + halfW, y + 1f, android.graphics.Path.Direction.CW)
        }
        c.drawPath(path, paint)
        return bmp
    }

    // ------------------------------------------------------------------
    // 雪花：24 帧六瓣对称，每帧旋转 i×2.5°… 实际每帧转 360/24=15°。
    // 主臂 6 组 + 副分支，帧间连续旋转，播放时循环即得自旋。
    // ------------------------------------------------------------------
    private fun buildSnowflakes(): List<Bitmap> {
        val frames = ArrayList<Bitmap>(SNOW_FRAMES)
        for (i in 0 until SNOW_FRAMES) {
            frames.add(buildSnowflake(i * (360f / SNOW_FRAMES)))
        }
        return frames
    }

    private fun buildSnowflake(rotationDeg: Float): Bitmap {
        val size = SNOW_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            strokeWidth = size * 0.035f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val branch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            strokeWidth = size * 0.022f
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val rot = rotationDeg * PI.toFloat() / 180f
        for (k in 0 until 6) {
            val a = rot + k * (PI.toFloat() / 3f)
            val dx = cos(a)
            val dy = sin(a)
            val armLen = size * 0.42f
            // 主臂
            c.drawLine(cx, cy, cx + dx * armLen, cy + dy * armLen, arm)
            // 副分支：两段，朝向与主臂成 ±60°，长度递减
            val joints = listOf(0.45f to 0.30f, 0.72f to 0.22f)
            for ((pos, len) in joints) {
                val jx = cx + dx * armLen * pos
                val jy = cy + dy * armLen * pos
                for (sign in listOf(1, -1)) {
                    val ba = a + sign * (PI.toFloat() / 3f)
                    c.drawLine(
                        jx, jy,
                        jx + cos(ba) * armLen * len, jy + sin(ba) * armLen * len,
                        branch,
                    )
                }
            }
        }
        // 中心点
        c.drawCircle(cx, cy, size * 0.045f, arm)
        return bmp
    }
}
