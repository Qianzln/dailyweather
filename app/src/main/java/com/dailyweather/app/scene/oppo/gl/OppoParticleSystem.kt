package com.dailyweather.app.scene.oppo.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.dailyweather.app.scene.oppo.OppoEntity

/**
 * GPU 粒子系统 —— 南风 v4.3.99 / OPPO COE 的 compute 粒子管线。
 *
 * ## 数据链路（全部取自 pack.json，无自拟参数）
 * ```
 * particle.uniforms  ─┬─→ 主发射 compute（particle/<uuid>.cs）      入 SSBO → 出 SSBO
 *                     ├─→ effects[0].uniforms → wind   compute      SSBO 乒乓
 *                     └─→ effects[1].uniforms → 扰动   compute      SSBO 乒乓
 *                                                   ↓
 *                     SSBO 直接当顶点缓冲 → glDrawArrays(GL_POINTS) → 实体自己的 frag 采样精灵
 * ```
 *
 * ## 粒子结构（96 字节，6×vec4，三处必须一致）
 * ```
 * offset  0  position      xyzw   xyz=位置(裁剪空间!) w=大小 → gl_PointSize
 * offset 16  acceleration  xyzw   x=随机角, z=景深扰动系数, w=预留
 * offset 32  speed         xyzw   xyz=速度, w=随机种子
 * offset 48  color         rgba
 * offset 64  lifeTime      xyzw   x=当前生命, y=总生命, z=保留, w=alpha随机
 * offset 80  originPos     xyzw   仅 compute 用，不参与顶点属性
 * ```
 * 顶点着色器显式声明了 `layout(location = 0..4) in vec4`，只用到前 5 个 vec4
 * （跨度仍是 96 字节），所以 `glVertexAttribPointer(loc, 4, FLOAT, false, 96, loc*16)`。
 *
 * ## 坐标系：粒子位置**已经是裁剪空间**
 * `gl_Position = vec4(position.xyz, 1.0)` —— 不乘任何矩阵。
 * 雨的 `emitterPosition = [-0.25, 1.2]`、`emitterSize = [1.2, 0.2]`
 * → 出生点 y ∈ [1.0, 1.4]，即在**上边缘之外**，然后以 `direc = [0.25, -1, 0]` 落进视野。
 * 这不是 bug，是雨的设计。
 *
 * ## GLES 版本要求
 * compute 与粒子着色器全部是 `#version 320 es` → 需要 **GLES 3.2**（见 [OppoGlCaps.hasGles32]）。
 * 3.0/3.1 设备上直接跳过粒子（天空与云仍正常，因为那批是 `#version 300 es`）。
 */
class OppoParticleSystem(private val context: Context) {

    private companion object {
        const val TAG = "OppoParticles"
        const val STRIDE_BYTES = 96
        const val LOCAL_SIZE = 256
        /** 原版 `mesh.particleMaxNum` 默认值；本包没导出 mesh，用 activeParticeNumber 当容量。 */
        const val FALLBACK_CAPACITY = 1000
    }

    /** 一个粒子实体的运行期状态。按实体名（`particle_rain` 等）缓存，跨帧复用。 */
    private class Instance(val entity: OppoEntity) {
        val cfg = entity.particle!!
        val role: String = cfg.role
        val capacity: Int = cfg.particleCount.coerceIn(1, FALLBACK_CAPACITY)

        var vao = 0
        val ssbo = IntArray(2)
        var cur = 0
        var initialized = false

        /** 主发射：程序 + 参数。 */
        var mainProg: OppoShaderLibrary.Program? = null

        /** effect 通道：程序 + 各自参数，按 pack.json 的顺序执行。 */
        var effectProgs: List<Pair<OppoShaderLibrary.Program, Map<String, FloatArray>>> = emptyList()

        /** 渲染用的顶点/片段程序（实体自己的 shaderVert / shaderFrag）。 */
        var renderProg: OppoShaderLibrary.Program? = null

        /** 编译失败的实体只记一次，不再重试。 */
        var failed = false

        var lastError = 0
    }

    private val instances = HashMap<String, Instance>()

    @Volatile
    private var enabled = false

    /** GL 线程：上下文创建后调用。返回是否启用粒子路径。 */
    fun onSurfaceCreated(): Boolean {
        enabled = OppoGlCaps.hasGles32
        if (!enabled) {
            Log.i(TAG, "跳过粒子：需要 GLES 3.2（当前 ${OppoGlCaps.major}.${OppoGlCaps.minor}）")
        }
        return enabled
    }

    fun release() {
        for (inst in instances.values) {
            if (inst.vao != 0) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(inst.vao), 0)
                inst.vao = 0
            }
            val bufs = inst.ssbo.filter { it != 0 }.toIntArray()
            if (bufs.isNotEmpty()) GLES30.glDeleteBuffers(bufs.size, bufs, 0)
            inst.ssbo.fill(0)
            inst.initialized = false
        }
        instances.clear()
    }

    /** 能力是否就绪。 */
    val active: Boolean get() = enabled

    /**
     * 绘制某个粒子实体（GL 线程，在场景实体的绘制顺序里调用）。
     *
     * 首次调用会编译 3 个 compute（主发射 + 2 个 effect）与 2 个渲染着色器，
     * 并创建 2 个 SSBO。任一环节失败即把该实体标记 `failed` 并永久跳过，不影响其它层。
     */
    fun draw(entity: OppoEntity, timeSec: Float, dt: Float, width: Int, height: Int) {
        if (!enabled) return
        val cfg = entity.particle ?: return
        val inst = instances.getOrPut(entity.name) { Instance(entity) }
        if (inst.failed) return

        if (!inst.initialized && !setup(inst)) {
            inst.failed = true
            return
        }

        // ---------- 1) 主发射 ----------
        val main = inst.mainProg ?: run { inst.failed = true; return }
        GLES30.glUseProgram(main.id)
        uploadDeclared(main, cfg.uniforms)
        // 引擎提供的量（不在 pack.json 里）
        set1f(main, "dt", dt)
        set1f(main, "u_Time", timeSec)
        set1i(main, "u_Active", inst.capacity)
        set2f(main, "u_resolution", width.toFloat(), height.toFloat())

        dispatch(inst, main, read = inst.cur, write = other(inst.cur))
        inst.cur = other(inst.cur)

        // ---------- 2) effect 通道（风 / 湍流） ----------
        for ((prog, uniforms) in inst.effectProgs) {
            GLES30.glUseProgram(prog.id)
            uploadDeclared(prog, uniforms)
            set1f(prog, "dt", dt)
            set1f(prog, "u_Time", timeSec)
            dispatch(inst, prog, read = inst.cur, write = other(inst.cur))
            inst.cur = other(inst.cur)
        }

        // ---------- 3) 渲染 ----------
        val rp = inst.renderProg ?: run { inst.failed = true; return }
        GLES30.glUseProgram(rp.id)
        uploadDeclared(rp, entity.uniforms)
        set2f(rp, "u_resolution", width.toFloat(), height.toFloat())

        // 精灵：pack.json 的贴图角色名就是片段着色器的 sampler 名（雨是 `rain`）。
        val bound = HashSet<String>()
        var unit = 0
        for ((roleName, path) in entity.textures) {
            val loc = rp.loc(roleName)
            if (loc < 0) continue
            val tex = OppoTextureCache.texture(path)
            if (tex == 0) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(loc, unit)
            bound += roleName
            unit++
        }
        // 没绑到贴图的 sampler（实测 particle_dust 就是）统一给白色，避免串到上一层的图。
        OppoTextureCache.bindFallbackSamplers(rp, bound, unit)

        // 混合：粒子实体自带 blend（SRC_ALPHA / ONE_MINUS_SRC_ALPHA）
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        bindVertexArray(inst, rp, inst.ssbo[inst.cur])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, inst.capacity)
        GLES30.glBindVertexArray(0)
        GlCheck.check("particles ${entity.name}")
    }

    // ---------------- 内部 ----------------

    private fun other(i: Int) = 1 - i

    private fun setup(inst: Instance): Boolean {
        val cfg = inst.cfg

        // 主发射 compute
        OppoShaderLibrary.computeAssetName(cfg.compute)?.let { path ->
            inst.mainProg = OppoShaderLibrary.computeProgram(path)
        }
        if (inst.mainProg == null) {
            Log.w(TAG, "${inst.entity.name}: 主发射 compute 缺失/编译失败（${cfg.compute}）")
            return false
        }

        // effect 通道（顺序即执行顺序）
        val effects = ArrayList<Pair<OppoShaderLibrary.Program, Map<String, FloatArray>>>(cfg.effects.size)
        for (e in cfg.effects) {
            val path = OppoShaderLibrary.computeAssetName(e.compute) ?: continue
            val p = OppoShaderLibrary.computeProgram(path)
            if (p == null) {
                Log.w(TAG, "${inst.entity.name}: effect 编译失败（$path），跳过该通道")
                continue
            }
            effects += p to e.uniforms
        }
        inst.effectProgs = effects

        // 渲染程序
        val vert = OppoShaderLibrary.assetName(inst.entity.shaderVert)
        val frag = OppoShaderLibrary.assetName(inst.entity.shaderFrag)
        if (vert == null || frag == null) {
            Log.w(TAG, "${inst.entity.name}: 缺少渲染着色器引用")
            return false
        }
        inst.renderProg = OppoShaderLibrary.program(vert, frag)
        if (inst.renderProg == null) {
            Log.w(TAG, "${inst.entity.name}: 渲染着色器编译失败（$vert + $frag）")
            return false
        }

        // SSBO ×2：静态容量，初值全 0（lifeTime = 0 → 首帧全部走重生分支）
        val bytes = inst.capacity * STRIDE_BYTES
        val zeros = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glGenBuffers(2, inst.ssbo, 0)
        for (b in inst.ssbo) {
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, b)
            GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, zeros, GLES30.GL_DYNAMIC_COPY)
            zeros.position(0)
        }
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        // 顶点数组对象（属性在每次渲染前按程序重新绑定）
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        inst.vao = vaos[0]

        inst.initialized = true
        Log.i(TAG, "${inst.entity.name}: 就绪 role=${inst.role} capacity=${inst.capacity} effects=${effects.size}")
        return true
    }

    /** 绑定 SSBO 到 binding 0/1 并派发；然后插内存屏障让后续顶点读取可见。 */
    private fun dispatch(
        inst: Instance,
        prog: OppoShaderLibrary.Program,
        read: Int,
        write: Int,
    ) {
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, inst.ssbo[read])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, inst.ssbo[write])
        val groups = (inst.capacity + LOCAL_SIZE - 1) / LOCAL_SIZE
        GLES31.glDispatchCompute(groups, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
        )
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, 0)
    }

    /** 把 SSBO 当作顶点缓冲：属性位置与偏移都按声明来（name→location，vec4，跨度 96）。 */
    private fun bindVertexArray(inst: Instance, prog: OppoShaderLibrary.Program, buffer: Int) {
        GLES30.glBindVertexArray(inst.vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        for ((name, loc) in prog.attributes) {
            if (loc < 0 || loc > 4) continue          // 只有前 5 个 vec4 参与渲染
            GLES30.glEnableVertexAttribArray(loc)
            GLES30.glVertexAttribPointer(loc, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, loc * 16)
        }
        // 顶点着色器只声明 5 个属性；若程序多出属性（不存在），这里不做处理
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GlCheck.check("particles VAO")
    }

    // ---------------- uniform 上传 ----------------

    /** 按着色器**声明**的类型上传一组值（标量存长度 1，vec2/3/4 按长度）。 */
    private fun uploadDeclared(prog: OppoShaderLibrary.Program, values: Map<String, FloatArray>) {
        for ((name, v) in values) {
            val u = prog.uniforms[name] ?: continue
            val loc = prog.loc(name)
            if (loc < 0) continue
            when (u.kind) {
                OppoShaderLibrary.Kind.FLOAT -> GLES30.glUniform1f(loc, v.firstOrNull() ?: 0f)
                OppoShaderLibrary.Kind.VEC2 -> GLES30.glUniform2f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                OppoShaderLibrary.Kind.VEC3 -> GLES30.glUniform3f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                OppoShaderLibrary.Kind.VEC4 -> GLES30.glUniform4f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f }, v.getOrElse(3) { 0f })
                OppoShaderLibrary.Kind.INT -> GLES30.glUniform1i(loc, (v.firstOrNull() ?: 0f).toInt())
                OppoShaderLibrary.Kind.BOOL -> GLES30.glUniform1i(loc, if ((v.firstOrNull() ?: 0f) != 0f) 1 else 0)
                else -> Unit
            }
        }
    }

    private fun set1f(prog: OppoShaderLibrary.Program, name: String, v: Float) {
        val l = prog.loc(name); if (l >= 0) GLES30.glUniform1f(l, v)
    }

    private fun set1i(prog: OppoShaderLibrary.Program, name: String, v: Int) {
        val l = prog.loc(name); if (l >= 0) GLES30.glUniform1i(l, v)
    }

    private fun set2f(prog: OppoShaderLibrary.Program, name: String, x: Float, y: Float) {
        val l = prog.loc(name); if (l >= 0) GLES30.glUniform2f(l, x, y)
    }
}
