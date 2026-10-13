package com.dailyweather.app.scene.oppo.gl

import android.util.Log
import android.opengl.GLES30

/**
 * GLES 能力探测与基础封装。
 *
 * ## 为什么需要探测
 * OPPO COE 的粒子系统是 `#version 320 es` 的 compute shader（SSBO 双缓冲），
 * 需要 **GLES 3.1** 起步（`#version 320 es` 是 3.2 的着色语言版本，3.1 设备通常可编译
 * 320 es 的核心特性）。`minSdk 26` 的机器并不都支持，模拟器（swiftshader）大概率不支持。
 * 所以渲染器必须有两条路径：GLES 可用走 GPU，不可用回退现有 Compose/AGSL。
 *
 * 探测结果只在第一次 `GLSurfaceView.onSurfaceCreated` 之后有效。
 */
object OppoGlCaps {

    private const val TAG = "OppoGlCaps"

    /** 连接后拿到的原始版本串，便于日志定位。 */
    @Volatile
    var versionString: String = ""
        private set

    /** GLES 主版本（3 = GLES 3.x）。 */
    @Volatile
    var major: Int = 0
        private set

    /** GLES 次版本（0/1/2）。4.3.99 的 compute 需要 >= 1。 */
    @Volatile
    var minor: Int = 0
        private set

    /** 是否具备 compute shader 能力（GLES 3.1+）。 */
    @Volatile
    var hasCompute: Boolean = false
        private set

    /**
     * 是否支持 `#version 320 es`（**GLES 3.2**）。
     *
     * 粒子系统的 compute 与顶点/片段着色器全部是 `#version 320 es`，
     * 所以 3.1 设备虽然"有 compute"但**编译不过**。粒子路径只认这个标志。
     */
    @Volatile
    var hasGles32: Boolean = false
        private set

    /** 纹理最大边长；云贴图 1024×626、闪电 1080×1920，异常设备可能拒绝。 */
    @Volatile
    var maxTextureSize: Int = 0
        private set

    @Volatile
    var probed: Boolean = false
        private set

    /** 在 GL 线程（onSurfaceCreated）调用一次。 */
    fun probe() {
        if (probed) return
        val v = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        versionString = v
        val e = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(v)
        major = e?.groupValues?.get(1)?.toIntOrNull() ?: 0
        minor = e?.groupValues?.get(2)?.toIntOrNull() ?: 0
        hasCompute = major > 3 || (major == 3 && minor >= 1)
        hasGles32 = major > 3 || (major == 3 && minor >= 2)
        val maxTex = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        maxTextureSize = maxTex[0]
        probed = true
        Log.i(TAG, "GLES $major.$minor  compute=$hasCompute  gles32=$hasGles32  maxTex=$maxTextureSize  ver=$v")
    }

    fun reset() {
        probed = false
        versionString = ""
        major = 0; minor = 0; hasCompute = false; hasGles32 = false; maxTextureSize = 0
    }
}

/** 打开 GL 调试检查；失败只记日志不抛，避免动画线程因单帧问题整体挂掉。 */
object GlCheck {

    private const val TAG = "OppoGl"
    private var lastError = 0

    fun check(where: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR && err != lastError) {
            lastError = err
            Log.w(TAG, "GL error 0x${Integer.toHexString(err)} @ $where")
        }
    }

    fun clearErrors() {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain */ }
        lastError = 0
    }
}

/**
 * 单位四边形：2×2，顶点在 ±1（不是 ±0.5）。
 *
 * 依据：pack.json 里 background 实体的 `scale.y = 2.2222` 恰好等于 baseline 的 2400/1080,
 * 说明四边形纵跨 ±1 时乘以 scale 后能正好铺满 ortho（半宽 1、半高 视口高/宽）。
 * 用 ±0.5 会让所有云和太阳位置整体缩一半、偏到画面下半。
 *
 * 顶点布局：position(2) + texCoord(2)，交错存放在一个 VBO 里。
 * UV 取 (0,0)=左下、(1,1)=右上 —— 与天空渐变着色器的取向一致
 * （该着色器把 uv.y 映射到渐变位置，且 `_RangeStart=1/_RangeEnd=0`，即 uv.y=1 是天空顶部）。
 */
object QuadMesh {

    const val FLOATS_PER_VERTEX = 4
    const val VERTEX_COUNT = 4

    var vao: Int = 0; private set
    var vbo: Int = 0; private set
    var ibo: Int = 0; private set

    private val VERTICES = floatArrayOf(
        // x, y, u, v
        -1f, -1f, 0f, 0f,   // 左下
        1f, -1f, 1f, 0f,   // 右下
        -1f, 1f, 0f, 1f,   // 左上
        1f, 1f, 1f, 1f,   // 右上
    )

    private val INDICES = intArrayOf(0, 1, 2, 2, 1, 3)

    fun ensure() {
        if (vao != 0) return
        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        vao = vaoArr[0]
        GLES30.glBindVertexArray(vao)

        val buf = IntArray(2)
        GLES30.glGenBuffers(2, buf, 0)
        vbo = buf[0]; ibo = buf[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val vb = java.nio.ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(VERTICES).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, VERTICES.size * 4, vb, GLES30.GL_STATIC_DRAW)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 2 * 4)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val ib = java.nio.ByteBuffer.allocateDirect(INDICES.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(INDICES).position(0)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, INDICES.size * 4, ib, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
        GlCheck.check("QuadMesh.ensure")
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, INDICES.size, GLES30.GL_UNSIGNED_INT, 0)
    }
}
