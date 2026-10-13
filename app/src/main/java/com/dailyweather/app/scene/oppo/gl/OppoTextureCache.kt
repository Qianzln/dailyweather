package com.dailyweather.app.scene.oppo.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.dailyweather.app.scene.oppo.OppoSceneRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * OPPO 贴图上传与缓存。
 *
 * ## 三条必须遵守的解码约束（原版 README 明确要求）
 * 云贴图是 **RGBA 双通道**：RGB = normal map（法线），A = 不透明度。
 * 因此：
 * 1. `inPremultiplied = false` —— 预乘会把 A 混进 RGB，直接毁掉法线方向；
 * 2. `inScaled = false` —— 缩放会重采样法线，梯度失真；
 * 3. 不做颜色校正（不套 ColorMatrix / 不做 sRGB→linear）。
 *
 * ## 关于"不要垂直翻转"——**照做，不翻转**
 * README 要求「不缩放、不颜色校正、不垂直翻转、不预乘」地解码。经实机验证，这里的正确解读是
 * **上传前也不要翻转**：源 PNG 已经是 GPU 行序（即 PNG 的第 0 行就是 `t = 0` 那一行）。
 *
 * ⚠️ 曾经的实现是「上传前用 `Matrix(scale(1,-1))` 翻一次」，那有两个问题，都已实测暴露：
 * 1. **它会静默失败**：`Bitmap.createBitmap(src, …, matrix, false)` 遇到
 *    `inPremultiplied = false` 的位图会抛 `IllegalArgumentException`
 *    （logcat: `Canvas: trying to use a non-premultiplied bitmap`）→ 44 张全部"翻转失败，沿用原图"，
 *    即实际上一张都没翻，日志刷了 44 条警告；
 * 2. 就算翻转成功，垂直镜像一个 **normal map** 会把法线的 Y 分量镜像掉（云的光照方向会错）。
 * 所以现在是**直接上传原图**，与 README 一致。
 *
 * ## 采样方式
 * 云用 `CLAMP_TO_EDGE`、太阳光晕用 `REPEAT`（共 31 / 13 张，见 `presets.json` 的 `textures` 表）。
 * 混用会出接缝或边缘拉丝，所以 wrap 必须逐张贴图按元数据设置。
 */
object OppoTextureCache {

    private const val TAG = "OppoTexture"
    private const val DIR = "oppo/"

    private val cache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var appContext: Context? = null

    /** 采样参数是否生成 mipmap（云缩放后若无 mipmap 会闪烁；若观感偏"糊"可关掉）。 */
    @Volatile
    var useMipmaps: Boolean = true

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 取贴图 id；失败返回 0（调用方跳过该层，不崩）。 */
    fun texture(assetPath: String): Int {
        cache[assetPath]?.let { return it }
        val ctx = appContext ?: return 0

        val bmp = decode(ctx, assetPath) ?: run {
            Log.e(TAG, "解码失败 $assetPath")
            cache[assetPath] = 0
            return 0
        }

        val idArr = IntArray(1)
        GLES30.glGenTextures(1, idArr, 0)
        val id = idArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)

        val repeat = OppoSceneRegistry.textures[assetPath]?.wrapRepeat ?: false
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (useMipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        // 不做垂直翻转就上传（见类注释：源 PNG 已是 GL 行序，README 明确要求不得翻转）。
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (useMipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        bmp.recycle()
        GlCheck.check("texImage2D $assetPath")

        cache[assetPath] = id
        return id
    }

    /** 预加载一批（GL 线程调用）。云/太阳/闪电是必用集，首帧前先热一遍避免卡顿。 */
    fun preload(paths: Collection<String>): Int {
        var ok = 0
        for (p in paths) if (texture(p) != 0) ok++
        return ok
    }

    /** 全部 44 张都热一遍（约 9.6 MB 源文件，首次进入会花几百毫秒，建议后台做）。 */
    fun preloadAll(): Int = preload(OppoSceneRegistry.textures.keys)

    fun invalidate() {
        val ids = cache.values.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
        cache.clear()
        if (white != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(white), 0)
            white = 0
        }
    }

    // ---------- 1×1 白色兜底 ----------

    private var white = 0

    /**
     * 1×1 白色 RGBA 贴图。
     *
     * 用途：某些材质（实测 `particle_dust`）**没有绑定任何贴图**，但片段着色器里仍有 sampler。
     * 这时纹理单元 0 上是上一次遗留的贴图 → 会把别的图当作沙尘画出来（典型表现是"沙尘里出现云的形状"）。
     * 给未绑定的 sampler 统一塞白色，最坏也只是纯色粒子，不会串图。
     */
    fun white(): Int {
        if (white != 0) return white
        val idArr = IntArray(1)
        GLES30.glGenTextures(1, idArr, 0)
        white = idArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, white)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
                .put(byteArrayOf(-1, -1, -1, -1)),
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return white
    }

    /**
     * 给程序里**没被绑定**的 sampler uniform 统一绑白色，返回下一个可用纹理单元。
     *
     * @param bound 已被调用方绑定过的 sampler 名
     */
    fun bindFallbackSamplers(prog: OppoShaderLibrary.Program, bound: Set<String>, startUnit: Int): Int {
        var unit = startUnit
        val w = white()
        if (w == 0) return unit
        for ((name, u) in prog.uniforms) {
            if (u.kind != OppoShaderLibrary.Kind.SAMPLER2D) continue
            if (name in bound) continue
            val loc = prog.loc(name)
            if (loc < 0) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, w)
            GLES30.glUniform1i(loc, unit)
            unit++
        }
        return unit
    }

    // ---------- 内部 ----------

    private fun decode(context: Context, assetPath: String): Bitmap? = try {
        // 这里只做一次 open 探测尺寸，第二次才真解码为 RGBA_8888：
        // 源图为 ASTC 转出的 PNG，带 alpha，必须保持 8888 不走 565 的降位路径。
        val opts = BitmapFactory.Options().apply {
            inPremultiplied = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.assets.open("$DIR$assetPath").use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        Log.e(TAG, "读取失败 $assetPath: ${e.message}")
        null
    }
}
