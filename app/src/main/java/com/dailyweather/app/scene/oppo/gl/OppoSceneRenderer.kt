package com.dailyweather.app.scene.oppo.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.dailyweather.app.scene.oppo.OppoEntity
import com.dailyweather.app.scene.oppo.OppoLights
import com.dailyweather.app.scene.oppo.OppoScenePreset
import com.dailyweather.app.scene.oppo.OppoSceneRegistry

/**
 * 场景图渲染器：把 pack.json 的预设按原版着色器画出来。
 *
 * ## 绘制顺序
 * 按实体的 `sort` 升序，纯 alpha 合成，**不开深度测试**（这是 2D 图层叠加，不是 3D 场景）。
 * 实测顺序：`background(0)` → `DirectionalLight(0)` → `sun_main(11)` → `cloud(17)` → …
 * 即云画在太阳之上。
 *
 * ## 坐标系与矩阵
 * ```
 * x ∈ [-1, 1]            y ∈ [-s, +s]，s = 视口高 / 宽
 * P = ortho(-1, 1, -s, s, 0.01, 100)
 * V = lookAt(0,0,1  →  0,0,0)   摄像机在 z=1，实体 z≈0.2~0.8，落在近远平面之间
 * M = T(pos) · R(rot: XYZ 欧拉，度) · S(scale)
 * gl_Position = P · V · M · a_position
 * ```
 * 四边形是 **2×2**（顶点 ±1）。依据：background 的 `scale.y = 2.2222` 恰等于 baseline 的
 * 2400/1080，乘上 2×2 半高 1 后纵跨 ±2.2222，正好等于 ortho 半高 → 铺满全屏。
 *
 * ## 引擎提供的 uniform（不在 pack.json 里）
 * `u_projection` / `u_view` / `u_model` / `u_time`，以及云着色器要的结构化光照
 * `u_ambientLight` / `u_directionalLights[]` / `u_pointLights[]` / `u_numDirLights` / `u_numPointLights`。
 * 其余 uniform 一律来自实体自己的 `uniforms` 表，按声明类型（float/vec2/vec3/vec4/int）上传。
 */
class OppoSceneRenderer(private val context: Context) {

    private companion object {
        const val TAG = "OppoRenderer"
        const val MAX_DIR = 10
        const val MAX_POINT = 10
    }

    /** 该预设里的闪电帧数量（保留给调试/日志）。 */
    private fun thunderCount(preset: OppoScenePreset): Int =
        preset.entities.count { it.thunder != null }.coerceAtLeast(1)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var viewportW = 0
    private var viewportH = 0

    @Volatile
    private var pendingKey: String? = null

    @Volatile
    private var currentKey: String? = null

    @Volatile
    private var timeSeconds = 0f

    /** 已预热的贴图路径（首帧前集中上传，避免运行中卡顿）。 */
    private var preloaded = false

    /** 首帧之后待分块上传的剩余贴图：每帧 4 张，把几百 ms 的全量预热摊到 ~10 帧。 */
    private val preloadQueue = ArrayDeque<String>()

    /** GPU 粒子（compute + SSBO 乒乓）。GLES < 3.2 时自动跳过。 */
    private val particles = OppoParticleSystem(context)

    /** 上一帧的 dt，喂给粒子 compute。 */
    private var lastDt = 1f / 60f

    /**
     * 场景入场淡入起点（timeSeconds 基准，-1 = 已播完）。
     * 原版 MainScene 的 cloud_fade/sun_fade/moon_fade/star_fade/wind_fade/fog_fade
     * 在切换场景时把各元素从 0 淡入（0.4~1.1s，云的 CloudFadeAnim 最慢 1.1s）。
     * 这里统一成 0.9s 的全局淡入（换预设/冷启动时各元素从天空上"长出来"，而不是硬切）。
     */
    private var sceneFadeStart = 0f

    // ---------------- 生命周期（GL 线程调用） ----------------

    /**
     * @return GLES 上下文是否可用（GLES 3.x）。返回 false 时上层应回退到 Compose 渲染器。
     * 注意：这只代表"渲染器能跑"，不含 compute 能力——[OppoGlCaps.hasCompute] 才是粒子走 GPU 的前提。
     */
    fun onSurfaceCreated(): Boolean {
        OppoGlCaps.probe()
        OppoShaderLibrary.attach(context)
        OppoTextureCache.attach(context)
        QuadMesh.ensure()

        // 纯 alpha 合成：关深度、关剔除、开混合。
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GlCheck.clearErrors()
        // 粒子需要 GLES 3.2（compute 与粒子着色器都是 #version 320 es）
        particles.onSurfaceCreated()
        return OppoGlCaps.major >= 3
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportW = width
        viewportH = height
        GLES30.glViewport(0, 0, width, height)

        // s = 高/宽：与 baseline 推导一致，且随设备宽高比自适应（硬编码 baseline 会让云偏出屏）。
        val s = height.toFloat() / width.toFloat()
        Matrix.orthoM(proj, 0, -1f, 1f, -s, s, 0.01f, 100f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    fun setPreset(key: String) {
        pendingKey = key
        // 只在实际生效的键变化时打日志：这是 release 包下确认「场景选对没有」的唯一抓手
        // （fixture 注入被 BuildConfig.DEBUG 门禁，release 里只能跑真实天气）。
        if (key != currentKey) {
            Log.i(TAG, "场景键切换: ${currentKey ?: "(无)"} -> $key")
        }
    }

    /** 推进时间（驱动云的 `u_time` 噪声位移与粒子积分）。 */
    fun advance(dtSeconds: Float) {
        timeSeconds += dtSeconds
        lastDt = dtSeconds.coerceIn(0f, 0.1f)
    }

    /** 当前实际生效的预设键（供 UI/调试显示）。 */
    fun activeKey(): String? = currentKey

    /** compute 粒子（雨/雪/星/流星/沙尘）是否可用（GLES 3.2）。UI 层据此决定 Canvas 兜底。 */
    fun particlesActive(): Boolean = particles.active

    fun onSurfaceReleased() {
        OppoShaderLibrary.invalidate()
        OppoTextureCache.invalidate()
        particles.release()
        OppoGlCaps.reset()
        preloaded = false
        preloadQueue.clear()
        currentKey = null
    }

    // ---------------- 每帧 ----------------

    fun drawFrame() {
        // 切场景：等注册表就绪；未就绪就保持上一帧内容（不清空，避免闪白）。
        pendingKey?.let { k ->
            if (OppoSceneRegistry.ready && OppoSceneRegistry.preset(k) != null) {
                pendingKey = null
                if (k != currentKey) {
                    currentKey = k
                    sceneFadeStart = timeSeconds      // 换场景 → 元素淡入重播
                }
                OppoGlCaps.probe()
            }
        }
        val preset = currentKey?.let { OppoSceneRegistry.preset(it) } ?: return

        if (!preloaded && OppoSceneRegistry.ready) {
            // 首帧只上传**当前预设用到的贴图**（背景 + 该天气的几张云/粒子图），
            // 让天空/云层以最快速度亮起来；其余贴图画后分块补（[drainPreloadQueue]）。
            // 旧实现首帧 preloadAll() 在 GL 线程阻塞几百毫秒——进页面云层"愣一下"才出现
            // （2026-10-12 启动优化项之一）。
            val t0 = System.currentTimeMillis()
            val paths = preset.entities.flatMap { it.textures.values }.distinct()
            val n = OppoTextureCache.preload(paths)
            Log.i(TAG, "当前场景贴图预热 $n/${paths.size} 张，用时 ${System.currentTimeMillis() - t0}ms")
            preloaded = true
            preloadQueue.clear()
            preloadQueue.addAll(OppoSceneRegistry.textures.keys)
        }
        drainPreloadQueue()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // 背景：先铺天空渐变（其它层都叠在它上面）。
        preset.entities.firstOrNull { it.name == "background" }?.let { drawEntity(it, preset, isBackground = true) }

        for (e in preset.entities) {
            if (!e.visible) continue
            if (e.name == "background") continue
            if (e.shaderFrag == null) continue          // 灯光等无材质实体
            if (e.name.startsWith("Camera")) continue
            drawEntity(e, preset, isBackground = false)
        }
        GlCheck.check("drawFrame")
    }

    /** 每帧最多上传 4 张剩余贴图：全量预热摊到多帧，首帧不被阻塞。 */
    private fun drainPreloadQueue() {
        if (preloadQueue.isEmpty()) return
        val batch = ArrayList<String>(4)
        repeat(4) { if (preloadQueue.isNotEmpty()) batch += preloadQueue.removeFirst() }
        if (batch.isNotEmpty()) OppoTextureCache.preload(batch)
    }

    private fun drawEntity(e: OppoEntity, preset: OppoScenePreset, isBackground: Boolean) {
        // 粒子实体走 compute 路径：位置在裁剪空间、用 gl_PointCoord 采样精灵，
        // 与四边形那套（a_position/a_texCoord + MVP）完全不同，必须在这里分流。
        if (e.particle != null) {
            particles.draw(e, timeSeconds, lastDt, viewportW, viewportH)
            return
        }

        // 闪电帧动画（按 21_2 预设 thunder_animator 关键帧重建，2026-10-13 核对修正）：
        // 5 个 thunder_N 是同位叠画的 5 种 bolt 形态（各有独立 u_texLight0），
        // 原版对全部 5 层**同时**驱动同一组关键帧——不是序列帧、也不是轮换。
        // 动画值在 uniform 循环之后单独直接上传（见下），不再每帧复制整个 uniforms map。
        // 雷闪逻辑已抽离到 SceneAnimator，渲染器只消费结果。
        var thunderEnv = -1f      // <0 = 非雷帧
        var thunderDissolve = 0f
        if (e.thunder != null) {
            val state = SceneAnimator.thunderState(timeSeconds) ?: return  // 静默期整条不画
            thunderEnv = state.first
            thunderDissolve = state.second
        }

        val vert = OppoShaderLibrary.assetName(e.shaderVert) ?: OPPO_VERT_FALLBACK
        val frag = OppoShaderLibrary.assetName(e.shaderFrag) ?: return
        val prog = OppoShaderLibrary.program(vert, frag) ?: return

        GLES30.glUseProgram(prog.id)

        // 模型矩阵
        Matrix.setIdentityM(model, 0)
        // 云层漂移（重建）：pack.json 里云**没有平移动画**（动画只有太阳光晕 fadeIn/Out
        // 与雷闪），云的运动全靠着色器噪声形变（≈0.015 uv/s）——实机读作"云不在动"
        // （2026-10-13 反馈）。按天气应用通行做法给云一个右→左的缓慢平移 + 环绕回场：
        // 近景快、远景慢（视差），z≈-0.7 约 300s 穿屏、z≈0.6 约 150s 穿屏。
        // 云贴图四边 alpha 渐变到 0（CLAMP_TO_EDGE），滑出/滑入屏幕边缘不会出现硬切。
        // 漂移逻辑已抽离到 SceneAnimator，渲染器只消费结果。
        var posX = e.pos[0]
        if (e.cloud != null) {
            posX = SceneAnimator.cloudDriftX(e, timeSeconds)
        }
        Matrix.translateM(model, 0, posX, e.pos[1], e.pos.getOrElse(2) { 0f })
        if (e.rot.size >= 3) {
            if (e.rot[0] != 0f) Matrix.rotateM(model, 0, e.rot[0], 1f, 0f, 0f)
            if (e.rot[1] != 0f) Matrix.rotateM(model, 0, e.rot[1], 0f, 1f, 0f)
            if (e.rot[2] != 0f) Matrix.rotateM(model, 0, e.rot[2], 0f, 0f, 1f)
        }
        Matrix.scaleM(model, 0, e.scale[0], e.scale.getOrElse(1) { 1f }, e.scale.getOrElse(2) { 1f })

        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

        prog.uniforms["u_projection"]?.let { uploadMat4(prog, it.name, proj) }
        prog.uniforms["u_view"]?.let { uploadMat4(prog, it.name, view) }
        prog.uniforms["u_model"]?.let { uploadMat4(prog, it.name, model) }
        prog.uniforms["u_time"]?.let { GLES30.glUniform1f(prog.loc(it.name), timeSeconds) }

        uploadLights(prog, preset.lighting)

        // 场景入场淡入：把着色器的"总 alpha" uniform 乘上 fade 系数。
        // 背景不淡（它就是天空本身，淡出等于闪黑）；雷帧有自己的包络；粒子自带 fadeinTime。
        // 零分配实现：循环内直接乘系数，不再复制 uniforms map。
        val fade = sceneFade()
        val applyFade = fade < 1f && !isBackground && e.thunder == null

        // 实体自带的 uniform（淡入系数在上传时直接乘入）
        for ((name, v) in e.uniforms) {
            if (name in prog.structuralNames) continue
            val u = prog.uniforms[name] ?: continue
            val loc = prog.loc(name)
            if (loc < 0) continue
            val f = if (applyFade && name in prog.alphaUniformNames) fade else 1f
            when (u.kind) {
                OppoShaderLibrary.Kind.FLOAT -> GLES30.glUniform1f(loc, (v.firstOrNull() ?: 0f) * f)
                OppoShaderLibrary.Kind.VEC2 -> GLES30.glUniform2f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                OppoShaderLibrary.Kind.VEC3 -> GLES30.glUniform3f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                OppoShaderLibrary.Kind.VEC4 -> GLES30.glUniform4f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f }, v.getOrElse(3) { 0f } * f)
                OppoShaderLibrary.Kind.INT -> GLES30.glUniform1i(loc, (v.firstOrNull() ?: 0f).toInt())
                OppoShaderLibrary.Kind.BOOL -> GLES30.glUniform1i(loc, if ((v.firstOrNull() ?: 0f) != 0f) 1 else 0)
                else -> { /* sampler / mat 不在数据里 */ }
            }
        }

        // 雷帧动画 uniform：直接覆盖上传（数据里的静态 0 被动画值顶替），零分配。
        if (thunderEnv >= 0f) {
            GLES30.glUniform1f(prog.loc("u_alpha"), thunderEnv)
            GLES30.glUniform1f(prog.loc("u_flashStrength"), thunderEnv * 0.9f)
            GLES30.glUniform1f(prog.loc("u_DissolveValue"), thunderDissolve)
            GLES30.glUniform2f(prog.loc("u_resolution"), viewportW.toFloat(), viewportH.toFloat())
        }

        // 贴图绑定：按角色名顺序占纹理单元。sampler uniform 名与 pack.json 的 key 同名
        // （u_NormalTex / u_NoiseTex / u_texture / u_texLight0 …）。
        val bound = HashSet<String>()
        var unit = 0
        for ((role, path) in e.textures) {
            val loc = prog.loc(role)
            if (loc < 0) continue
            val tex = OppoTextureCache.texture(path)
            if (tex == 0) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(loc, unit)
            bound += role
            unit++
        }
        // 未绑定的 sampler 给白色兜底（避免采到上一层遗留的贴图）
        OppoTextureCache.bindFallbackSamplers(prog, bound, unit)

        // 混合：背景与有 blend 的实体按数据走，其余用默认 alpha 混合。
        applyBlend(e)

        QuadMesh.draw()
    }



    private fun sceneFade(): Float {
        val fade = SceneAnimator.sceneFade(timeSeconds, sceneFadeStart)
        if (SceneAnimator.sceneFadeDone(timeSeconds, sceneFadeStart)) {
            sceneFadeStart = -1f  // 标记已播完
        }
        return fade
    }

    private fun applyBlend(e: OppoEntity) {
        val b = e.blend ?: run {
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            return
        }
        val src = when (b["srcColorBlendFactor"]) {
            "ONE" -> GLES30.GL_ONE
            "SRC_ALPHA" -> GLES30.GL_SRC_ALPHA
            "ONE_MINUS_SRC_ALPHA" -> GLES30.GL_ONE_MINUS_SRC_ALPHA
            else -> GLES30.GL_SRC_ALPHA
        }
        val dst = when (b["dstColorBlendFactor"]) {
            "ONE" -> GLES30.GL_ONE
            "SRC_ALPHA" -> GLES30.GL_SRC_ALPHA
            "ONE_MINUS_SRC_ALPHA" -> GLES30.GL_ONE_MINUS_SRC_ALPHA
            else -> GLES30.GL_ONE_MINUS_SRC_ALPHA
        }
        GLES30.glBlendFunc(src, dst)
    }

    /** 结构化光照上传：云着色器用 `u_ambientLight` / `u_directionalLights[]` / `u_pointLights[]`。 */
    private fun uploadLights(prog: OppoShaderLibrary.Program, lights: OppoLights) {
        lights.ambient?.let { a ->
            u3(prog, "u_ambientLight.color", a.color)
            u1(prog, "u_ambientLight.intensity", a.intensity)
        }
        val dirs = lights.directional.take(MAX_DIR)
        dirs.forEachIndexed { i, d ->
            u3(prog, "u_directionalLights[$i].direction", d.dir)
            u3(prog, "u_directionalLights[$i].color", d.color)
            u1(prog, "u_directionalLights[$i].intensity", d.intensity)
            ui(prog, "u_directionalLights[$i].enable", if (d.enabled) 1 else 0)
        }
        ui(prog, "u_numDirLights", dirs.size)

        val pts = listOfNotNull(lights.point).take(MAX_POINT)
        pts.forEachIndexed { i, p ->
            u3(prog, "u_pointLights[$i].position", p.pos)
            u3(prog, "u_pointLights[$i].color", p.color)
            u1(prog, "u_pointLights[$i].intensity", p.intensity)
            // range 为 0 会让 `range/200` 除零 → attenuation 变 NaN，整层云变黑。给个安全下限。
            u1(prog, "u_pointLights[$i].range", if (p.range <= 0f) 200f else p.range)
            u3(prog, "u_pointLights[$i].attenuation", p.attenuation)
            ui(prog, "u_pointLights[$i].enable", if (p.enabled) 1 else 0)
        }
        ui(prog, "u_numPointLights", pts.size)
    }

    private fun u1(prog: OppoShaderLibrary.Program, name: String, v: Float) {
        val l = prog.loc(name); if (l >= 0) GLES30.glUniform1f(l, v)
    }

    private fun ui(prog: OppoShaderLibrary.Program, name: String, v: Int) {
        val l = prog.loc(name); if (l >= 0) GLES30.glUniform1i(l, v)
    }

    private fun u3(prog: OppoShaderLibrary.Program, name: String, v: FloatArray) {
        val l = prog.loc(name)
        if (l >= 0) GLES30.glUniform3f(l, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
    }

    private fun uploadMat4(prog: OppoShaderLibrary.Program, name: String, m: FloatArray) {
        val l = prog.loc(name)
        if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, 0)
    }
}

/** 顶点着色器回退：所有原版顶点着色器都是同一套接口，缺引用时用它。 */
private const val OPPO_VERT_FALLBACK = "cea10cdaffd67a68c62664a0b1625fc7.glsl"
