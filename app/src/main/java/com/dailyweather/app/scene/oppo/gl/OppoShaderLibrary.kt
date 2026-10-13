package com.dailyweather.app.scene.oppo.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 着色器资产管线：从 `assets/oppo/shader/` 下的 .glsl 加载、编译、缓存。
 *
 * ## 约定（已核对全部顶点着色器）
 * 顶点着色器一律使用：
 * ```glsl
 * in vec4 a_position;  in vec2 a_texCoord;
 * uniform mat4 u_projection, u_view, u_model;
 * ```
 * 这三个 mat4 **不在** pack.json 的 uniforms 里，是引擎提供的。
 *
 * ## 属性位置必须显式绑定
 * GLSL ES 3.0 不写 `layout(location=)` 时，属性位置由链接器自由分配。
 * 所以链接前用 [GLES30.glBindAttribLocation] 把 `a_position` 钉在 0、`a_texCoord` 钉在 1，
 * 与 [QuadMesh] 的顶点布局对应。不钉就会出现"位置属性拿到 UV 数据"这类诡异结果。
 *
 * ## uniform 名字来自哪儿
 * 文件里的 `// @Range(RangeStart,...)` 是**编辑器显示名**，与真实 uniform 名不一定一致
 * （例：注解 `RangeStart` vs 声明 `_RangeStart`）。所以真实名与类型一律从
 * `uniform <type> <name>;` 声明行解析，注解只当默认值提示用。
 */
object OppoShaderLibrary {

    private const val TAG = "OppoShader"

    /** assets 根目录下着色器所在前缀。 */
    private const val DIR = "oppo/shader/"

    enum class Kind { FLOAT, VEC2, VEC3, VEC4, INT, BOOL, SAMPLER2D, MAT4, MAT3, UNKNOWN }

    data class Uniform(val name: String, val kind: Kind, val arraySize: Int)

    class Program(
        val id: Int,
        val uniforms: Map<String, Uniform>,
        /** 顶点属性名 → 位置。 */
        val attributes: Map<String, Int>,
    ) {
        /** 结构化光照 uniform 走专门上传路径，不参与普通 uniform 批量上传。 */
        val structuralNames = setOf(
            "u_projection", "u_view", "u_model", "u_time",
            "u_ambientLight", "u_directionalLights", "u_pointLights",
            "u_numDirLights", "u_numPointLights",
        )

        /**
         * 着色器声明的 alpha 类 uniform 名集合（FLOAT/BOOL 类型且名字含 "alpha"）。
         * 由 [build] 在链接时自动推导，渲染期按需使用（场景淡入等）。
         * 不依赖硬编码名字，着色器新增 alpha uniform 自动识别。
         */
        val alphaUniformNames: Set<String> = uniforms
            .filter { (_, u) ->
                u.kind == Kind.FLOAT || u.kind == Kind.BOOL
            }
            .filterKeys { it.contains("alpha", ignoreCase = true) }
            .keys

        /**
         * uniform 名 → GL location 缓存。`glGetUniformLocation` 是驱动侧字符串哈希查询，
         * 渲染期每帧每实体每 uniform 调一次（全场景每帧数百次）纯属浪费——链接后按名缓存，
         * 不存在的位置（驱动返回 -1）也缓存住，避免反复查询。仅 GL 线程访问，无需并发结构。
         */
        private val locCache = HashMap<String, Int>()

        /** 取 uniform location；不存在的名字返回负数（已缓存，不重复问驱动）。 */
        fun loc(name: String): Int = locCache.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    private val cache = ConcurrentHashMap<String, Program?>()

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 按 (vert, frag) 资产路径取程序；失败返回 null 并记日志（该材质整体跳过，不影响其它层）。 */
    fun program(vertAsset: String, fragAsset: String): Program? {
        val key = "$vertAsset|$fragAsset"
        return cache.getOrPut(key) { build(vertAsset, fragAsset) }
    }

    fun invalidate() {
        cache.values.filterNotNull().forEach { GLES30.glDeleteProgram(it.id) }
        cache.clear()
        computeCache.values.filterNotNull().forEach { GLES30.glDeleteProgram(it.id) }
        computeCache.clear()
    }

    private fun build(vertAsset: String, fragAsset: String): Program? {
        val ctx = appContext ?: run {
            Log.e(TAG, "未 attach(context)")
            return null
        }
        val vs = read(ctx, vertAsset) ?: return null
        val fs = read(ctx, fragAsset) ?: return null

        val vid = compile(GLES30.GL_VERTEX_SHADER, vs, vertAsset) ?: return null
        val fid = compile(GLES30.GL_FRAGMENT_SHADER, fs, fragAsset) ?: run {
            GLES30.glDeleteShader(vid)
            return null
        }

        // 顶点属性位置：
        // 场景图那批顶点着色器不写 layout，靠约定 a_position→0 / a_texCoord→1；
        // 粒子那批（`#version 320 es`）**显式写了** `layout(location = N) in vec4 xxx;`，
        // 必须按它自己声明的编号绑，否则会把位置数据喂给速度属性。
        val attribs = LinkedHashMap<String, Int>()
        val explicit = LinkedHashMap<String, Int>()
        Regex("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*in\\s+\\w+\\s+(\\w+)")
            .findAll(vs).forEach { explicit[it.groupValues[2]] = it.groupValues[1].toInt() }
        if (explicit.isNotEmpty()) {
            attribs.putAll(explicit)
        } else {
            attribs["a_position"] = 0
            attribs["a_texCoord"] = 1
            var next = 2
            Regex("\\bin\\s+\\w+\\s+(\\w+)").findAll(vs).forEach {
                val n = it.groupValues[1]
                if (n !in attribs) attribs[n] = next++
            }
        }

        val pid = GLES30.glCreateProgram()
        GLES30.glAttachShader(pid, vid)
        GLES30.glAttachShader(pid, fid)
        // glBindAttribLocation 必须在 glLinkProgram **之前**调用才生效。
        attribs.forEach { (name, loc) -> GLES30.glBindAttribLocation(pid, loc, name) }
        GLES30.glLinkProgram(pid)

        val ok = IntArray(1)
        GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vid)
        GLES30.glDeleteShader(fid)
        if (ok[0] == 0) {
            Log.e(TAG, "链接失败 $vertAsset+$fragAsset: ${GLES30.glGetProgramInfoLog(pid)}")
            GLES30.glDeleteProgram(pid)
            return null
        }

        val uniforms = LinkedHashMap(parseUniforms(fs))
        parseUniforms(vs).forEach { (k, v) -> uniforms.putIfAbsent(k, v) }
        return Program(pid, uniforms, attribs)
    }

    /**
     * 解析 `uniform <type> <name>;` 与 `uniform <type> <name>[N];`。
     * 跳过结构体类型（`AmbientLight` / `PointLight` / `DirectionalLight`）——它们由光照路径单独上传。
     */
    private fun parseUniforms(src: String): Map<String, Uniform> {
        val out = LinkedHashMap<String, Uniform>()
        val re = Regex("^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\])?\\s*;", RegexOption.MULTILINE)
        for (m in re.findAll(src)) {
            val type = m.groupValues[1]
            val name = m.groupValues[2]
            val size = m.groupValues[3].toIntOrNull() ?: 0
            val kind = when (type) {
                "float" -> Kind.FLOAT
                "vec2" -> Kind.VEC2
                "vec3" -> Kind.VEC3
                "vec4" -> Kind.VEC4
                "int" -> Kind.INT
                "bool" -> Kind.BOOL
                "mat3" -> Kind.MAT3
                "mat4" -> Kind.MAT4
                "sampler2D" -> Kind.SAMPLER2D
                else -> Kind.UNKNOWN     // 结构体数组：光照路径处理
            }
            if (kind != Kind.UNKNOWN || size > 0) out[name] = Uniform(name, kind, size)
        }
        return out
    }

    private fun read(context: Context, assetPath: String): String? = try {
        context.assets.open("$DIR$assetPath").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "读取着色器失败 $assetPath: ${e.message}")
        null
    }

    private fun compile(type: Int, src: String, label: String): Int? {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src)
        GLES30.glCompileShader(id)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "编译失败 $label: ${GLES30.glGetShaderInfoLog(id)}")
            GLES30.glDeleteShader(id)
            return null
        }
        return id
    }

    /** 把 pack.json 里的 `shader/xxxx` 引用转成 assets 内的文件名。 */
    fun assetName(ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        val base = ref.substringAfterLast('/')
        return if (base.endsWith(".glsl")) base else "$base.glsl"
    }

    // ---------------- compute shader（粒子系统） ----------------

    private val computeCache = ConcurrentHashMap<String, Program?>()

    /**
     * `computeSourcePath` → assets 内路径。
     *
     * pack.json 里写的是 `particle/<uuid>.cs`（主发射）与 `particle/<uuid>/effectN.cs`（通道），
     * 而文件实际落在 `assets/oppo/compute/` 下 —— 把开头的 `particle/` 换成 `compute/`。
     */
    fun computeAssetName(ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        val trimmed = ref.trim()
        return when {
            trimmed.startsWith("particle/") -> "compute/" + trimmed.removePrefix("particle/")
            trimmed.startsWith("compute/") -> trimmed
            else -> trimmed
        }
    }

    /** 取 compute 程序（`#version 320 es`，需 GLES 3.2）。失败返回 null，调用方跳过粒子。 */
    fun computeProgram(assetPath: String): Program? =
        computeCache.getOrPut(assetPath) { buildCompute(assetPath) }

    private fun buildCompute(assetPath: String): Program? {
        val ctx = appContext ?: run {
            Log.e(TAG, "未 attach(context)")
            return null
        }
        val src = read(ctx, assetPath) ?: return null
        val id = compile(GLES31.GL_COMPUTE_SHADER, src, assetPath) ?: return null

        val pid = GLES30.glCreateProgram()
        GLES30.glAttachShader(pid, id)
        GLES30.glLinkProgram(pid)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(id)
        if (ok[0] == 0) {
            Log.e(TAG, "compute 链接失败 $assetPath: ${GLES30.glGetProgramInfoLog(pid)}")
            GLES30.glDeleteProgram(pid)
            return null
        }
        // compute 没有顶点属性
        return Program(pid, parseUniforms(src), emptyMap())
    }
}
