package com.dailyweather.app.scene.oppo.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * OPPO 场景的 GL 承载视图（TextureView + 独立 GL 线程）。
 *
 * ## 为什么是 TextureView，不是 GLSurfaceView
 * 顶栏与卡片的 Haze 毛玻璃要**采样天空层**（`Modifier.hazeSource`）。Haze 的原理是把源 composable
 * 渲染进一张离屏图层再对它做高斯模糊。而 `SurfaceView`（含 `GLSurfaceView`）走的是
 * **独立 Surface + SurfaceFlinger 合成**，内容不在宿主 View 的 display list 里，
 * Haze 采样不到 —— 结果就是顶栏下方一片黑。
 *
 * `TextureView` 的内容来自 `SurfaceTexture`，作为硬件层**绘制进父 View 的画布**，
 * 因此能被 Haze 的图层捕获。代价是比 SurfaceView 多一次合成，换来毛玻璃可用。
 *
 * ## EGL 自己管
 * `TextureView` 不提供 `GLSurfaceView` 那套 EGL 管理，所以这里手动：EGL14 初始化 →
 * 选 RGBA8888 配置 → 建 GLES3 上下文 → 用 SurfaceTexture 建 window surface。
 *
 * ## 帧率
 * 默认连续渲染（云/雨的噪声位移需要逐帧推进）。可设 [targetFps] 上限省电；
 * 页面不可见时应调用 [setPaused] 停帧，避免后台白耗电。
 */
class OppoGlSceneView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private companion object {
        const val TAG = "OppoGlView"
        const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }

    /**
     * 帧率上限；<=0 表示不限（使用设备原生刷新率）。
     * 默认 0：云层漂移等动画需要完整刷新率才能看出轻微移动，锁 60fps 在 120Hz 屏上会显得卡顿。
     */
    @Volatile
    var targetFps: Int = 0

    /**
     * GL 就绪回调（在 GL 线程调用，参数 = 是否可用）。
     * UI 侧据此决定「用 GL 天空」还是「回退 Compose 天空」——**不能在组合期同步判断**，
     * 因为 GLES 版本只有上下文创建之后才知道。
     */
    @Volatile
    var onGlReady: ((Boolean) -> Unit)? = null

    /**
     * compute 粒子（雨/雪/星/流星/沙尘）是否可用（GLES 3.2）。
     * 与 [onGlReady] 是两档：3.0/3.1 设备天空/云/雷帧能画但粒子没有，
     * UI 层据此决定是否启用 Canvas 特效兜底——避免 GL 粒子与 Canvas 特效双层叠画。
     */
    @Volatile
    var onParticlesActive: ((Boolean) -> Unit)? = null

    @Volatile
    private var paused = false

    @Volatile
    private var pendingPreset: String? = null

    private val renderer = OppoSceneRenderer(context)

    private var thread: RenderThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    /** 切场景（线程安全，下一帧生效）。 */
    fun setPreset(key: String) {
        pendingPreset = key
    }

    fun setPaused(p: Boolean) {
        paused = p
    }

    /** GL 能力探测结果（在 GL 线程首次连接后可用）。 */
    val glReady: Boolean get() = OppoGlCaps.probed

    // ---------------- SurfaceTextureListener（UI 线程） ----------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startThread(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        stopThread()
        super.onDetachedFromWindow()
    }

    private fun startThread(surface: SurfaceTexture, w: Int, h: Int) {
        stopThread()
        val t = RenderThread(surface, w, h)
        thread = t
        t.start()
    }

    private fun stopThread() {
        thread?.let { t ->
            t.shutdown()
            runCatching { t.join(2000) }
        }
        thread = null
    }

    // ---------------- GL 线程 ----------------

    private inner class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
    ) : Thread("OppoGlRender") {

        @Volatile private var running = true

        @Volatile private var resizeW = -1
        @Volatile private var resizeH = -1

        /** 停帧状态下仍要补画一帧的标记：预设切换/尺寸变化时置位（动效关闭 ≠ 黑屏）。 */
        @Volatile private var needsFrame = false

        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var config: EGLConfig? = null

        fun resize(w: Int, h: Int) {
            resizeW = w
            resizeH = h
        }

        fun shutdown() {
            running = false
        }

        override fun run() {
            try {
                if (!initEgl()) {
                    notifyReady(false)
                    return
                }
                val usable = renderer.onSurfaceCreated()
                notifyReady(usable)
                renderer.onSurfaceChanged(width, height)

                var last = System.nanoTime()
                while (running) {
                    val now = System.nanoTime()
                    val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                    last = now

                    if (resizeW > 0 && resizeH > 0) {
                        width = resizeW; height = resizeH
                        resizeW = -1; resizeH = -1
                        surfaceTexture.setDefaultBufferSize(width, height)
                        renderer.onSurfaceChanged(width, height)
                        needsFrame = true
                    }

                    pendingPreset?.let { renderer.setPreset(it); pendingPreset = null; needsFrame = true }

                    // 停帧（用户关掉「天气动效」）≠ 不画：预设/尺寸变化时补一帧静态画面。
                    if (!paused || needsFrame) {
                        renderer.advance(if (paused) 0f else dt)
                        renderer.drawFrame()
                        if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                            Log.w(TAG, "eglSwapBuffers 失败 0x${Integer.toHexString(EGL14.eglGetError())}")
                        }
                        needsFrame = false
                    }

                    val fps = targetFps
                    if (fps > 0) {
                        val frameMs = 1000L / fps
                        val spent = (System.nanoTime() - now) / 1_000_000
                        val sleepMs = frameMs - spent
                        if (sleepMs > 1) sleep(sleepMs)
                    } else {
                        sleep(1)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "渲染线程异常退出：${t.message}", t)
            } finally {
                renderer.onSurfaceReleased()
                releaseEgl()
            }
        }

        private fun notifyReady(ok: Boolean) {
            post {
                runCatching { onGlReady?.invoke(ok) }
                runCatching { onParticlesActive?.invoke(renderer.particlesActive()) }
            }
        }

        private fun initEgl(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay 失败"); return false
            }
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                Log.e(TAG, "eglInitialize 失败 0x${Integer.toHexString(EGL14.eglGetError())}"); return false
            }
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] <= 0) {
                Log.e(TAG, "eglChooseConfig 失败"); return false
            }
            config = cfgs[0]
            val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext 失败 0x${Integer.toHexString(EGL14.eglGetError())}"); return false
            }
            surfaceTexture.setDefaultBufferSize(width, height)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface 失败"); return false
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.e(TAG, "eglMakeCurrent 失败"); return false
            }
            return true
        }

        private fun releaseEgl() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}

/**
 * Compose 封装。放在天空层位置（最底），UI 叠在其上。
 *
 * 注意：`TextureView` 会画进父 View 画布，所以放在 `Box` 底层即可被上层的
 * `Modifier.hazeSource` 采样到——这正是选它而非 `GLSurfaceView` 的原因。
 */
@Composable
fun OppoGlSceneLayer(
    presetKey: String?,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    targetFps: Int = 60,
    onReady: (Boolean) -> Unit = {},
    onParticlesActive: (Boolean) -> Unit = {},
    onView: (OppoGlSceneView) -> Unit = {},
) {
    val currentOnView = rememberUpdatedState(onView)
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnParticlesActive = rememberUpdatedState(onParticlesActive)
    val viewRef = remember { arrayOfNulls<OppoGlSceneView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            OppoGlSceneView(ctx).also {
                it.onGlReady = { ok -> currentOnReady.value(ok) }
                it.onParticlesActive = { active -> currentOnParticlesActive.value(active) }
                viewRef[0] = it
                currentOnView.value(it)
            }
        },
        update = { v ->
            presetKey?.let(v::setPreset)
            v.setPaused(paused)
            v.targetFps = targetFps
        },
    )

    DisposableEffect(Unit) {
        onDispose { viewRef[0]?.setPaused(true) }
    }
}
