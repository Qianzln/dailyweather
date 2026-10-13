package com.dailyweather.app.scene.oppo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * OPPO COE 场景图（南风 v4.3.99 动效底座）的运行时模型。
 *
 * 数据来源：`assets/oppo/presets.json`，由 `tools/oppo_pack_to_presets.py` 从原版 `pack.json` 生成。
 * 生成时已完成三件事：① GUID 解析为文件路径；② 实体内容去重（560 个唯一实体，省 513 处重复引用）；
 * ③ 剔除编辑器残留（guid / animations / sha256 / originalPath）。
 *
 * ## 坐标系（从数据反推，已核对）
 * ```
 * x ∈ [-1, 1]        左 → 右
 * y ∈ [-s, +s]       上为正，s = 视口高/宽
 * ```
 * 依据：baseline = 1080×2400 → s = 2400/1080 = 2.2222…，与背景实体的 `scale.y` 完全一致
 * （单位四边形 scale=(1, 2.2222) 正好铺满全屏）。所以投影矩阵只需按视口宽高比换算，
 * 不要按 baseline 硬编码——设备宽高比不同时 s 要跟着变，否则云会偏出屏幕。
 *
 * ## 贴图解码约束（原版 README 明确要求）
 * 云贴图是 RGBA 双通道（RGB = normal map，A = 不透明度）。必须**不缩放、不颜色校正、
 * 不垂直翻转、不预乘**地解码，否则云的光照方向与边缘会整体错掉。
 */
data class OppoVec(val x: Float, val y: Float, val z: Float) {
    val array: FloatArray get() = floatArrayOf(x, y, z)
}

data class OppoLights(
    /** 环境光：`AmbientLight`，原始数据里 color 常为 0（由背景渐变承担天光）。 */
    val ambient: Ambient?,
    val directional: List<Directional>,
    val point: Point?,
) {
    data class Ambient(val color: FloatArray, val intensity: Float)
    data class Directional(val name: String, val dir: FloatArray, val color: FloatArray, val intensity: Float, val enabled: Boolean)
    data class Point(
        val name: String,
        val pos: FloatArray,
        val color: FloatArray,
        val intensity: Float,
        /** 绝对量纲（0~数千）。着色器里 `range = light.range / 200.`，为 0 会导致除零。 */
        val range: Float,
        val attenuation: FloatArray,
        val enabled: Boolean,
    )

    companion object { val EMPTY = OppoLights(null, emptyList(), null) }
}

/**
 * 一个场景实体。字段全部可选——不同实体带的信息不同：
 * 背景只带 uniform + 噪点贴图；云带 `cloud` 参数化 + normal map；灯在 [OppoLights] 里单独给。
 */
class OppoEntity(
    val name: String,
    val sort: Int,
    val visible: Boolean,
    val pos: FloatArray,
    val scale: FloatArray,
    val rot: FloatArray,
    val shaderVert: String?,
    val shaderFrag: String?,
    /** 标量存成长度 1 的数组，vec2/3/4 存对应长度；上传时按长度选 glUniformNf。 */
    val uniforms: Map<String, FloatArray>,
    /** 角色名 → assets 内相对路径，如 `u_NormalTex` → `textures/cloud_b/b_02_normal.png`。 */
    val textures: Map<String, String>,
    val blend: Map<String, String>?,
    val cloud: Cloud?,
    val sunHalo: SunHalo?,
    val sunMain: SunMain?,
    val isSunPoint: Boolean,
    val thunder: Thunder?,
    val particle: Particle?,
) {
    /**
     * 云实体名里内嵌的运行参数。名字形如 `cloud_c05_01_0.3_1500_1500`
     * → 组 c / 编号 05 / 槽位 01 / alpha 0.3 / 偏移 1500,1500。
     * 槽位 02、03 通常是同组的其他云，原场景里多为 `visible=false`（留给编辑器切）。
     */
    data class Cloud(val group: String, val id: String, val slot: String, val alpha: Float, val px: Float, val py: Float)

    /** 太阳光晕层：`sun_halo1_0.75` → level 1、scale 0.75；数字越大越靠外。 */
    data class SunHalo(val level: Int, val scale: Float)

    /** 太阳本体：`sun_main_0.22` → scale 0.22。 */
    data class SunMain(val scale: Float)

    /** 闪电帧：`thunder_0`…`thunder_4`。 */
    data class Thunder(val frame: Int)

    /**
     * 粒子系统：`particle_rain` / `particle_rainfront` / `particle_snow` / `particle_dust` / `particle_star` / `particle_meteor`。
     *
     * 原以为粒子参数在未导出的 materials 表里 —— **不是**，整段就在 pack.json 的实体里
     * （早期版本的转换器把这一整段丢了，只留了 role）。字段含义：
     * - [compute] 主发射 compute shader（`particle/<guid>.cs`，`#version 320 es` + SSBO 双缓冲）
     * - [uniforms] 发射参数：`activeParticeNumber`(粒子数) / `emitterPosition` / `emitterSize` /
     *   `depthRatio`(景深分层) / `direcMin|Max` / `minColor|maxColor` / `alphaRange` /
     *   `speed` / `lifeTime` / `sizeAndNoise` / `rotationAxis` / `emitterInfo`
     *   / 三档景深的 `closeSpeed|closeSize|closeAlpha|closeDisturb`(+ mid/far)
     * - [effects] 追加的 compute 通道（风、湍流），各有自己的 uniforms
     * - [textures] 通常为空；精灵靠实体 `material.textures` 的角色名（`rain`/`snow`）对应素材
     *
     * ⚠️ 粒子的贴图引用写成 `material/<uuid>/<role>`，指向未导出的表；
     * 转换器按角色名兜底到 `textures/particle/{rain-p,snow-p,star3}.png`（替代映射，非原引用）。
     */
    data class Particle(
        val role: String,
        val compute: String?,
        val uniforms: Map<String, FloatArray>,
        val textures: Map<String, String>,
        val effects: List<Effect>,
    ) {
        data class Effect(
            val compute: String?,
            val uniforms: Map<String, FloatArray>,
            val textures: Map<String, String>,
        )

        /** 该粒子系统的目标粒子数（原版 `activeParticeNumber`）。 */
        val particleCount: Int get() = (uniforms["activeParticeNumber"]?.firstOrNull() ?: 0f).toInt()
    }

    /** 背景 5 段天空渐变（`_Color0..4` + `_Segment1..3`）。 */
    val skyGradient: SkyGradient?
        get() {
            if (!uniforms.containsKey("_Color0")) return null
            val cols = arrayOfNulls<FloatArray>(5)
            for (i in 0 until 5) cols[i] = uniforms["_Color$i"] ?: return null
            fun f(k: String, d: Float) = uniforms[k]?.firstOrNull() ?: d
            return SkyGradient(
                colors = cols.requireNoNulls(),
                segments = floatArrayOf(f("_Segment1", 0.25f), f("_Segment2", 0.5f), f("_Segment3", 0.75f)),
                rangeStart = f("_RangeStart", 1f),
                rangeEnd = f("_RangeEnd", 0f),
                noiseScale = f("u_NoiseScale", 4f),
                noiseStrength = f("u_NoiseStrength", 0.01f),
                alpha = f("u_Alpha", 1f),
            )
        }
}

data class SkyGradient(
    val colors: Array<FloatArray>,
    val segments: FloatArray,
    val rangeStart: Float,
    val rangeEnd: Float,
    val noiseScale: Float,
    val noiseStrength: Float,
    val alpha: Float,
)

/** 一个可选场景预设（62 个 + `0_0_empty` + `MainScene`）。 */
class OppoScenePreset(
    val key: String,
    val lighting: OppoLights,
    val entities: List<OppoEntity>,
)

/** 贴图元数据：采样方式与原始尺寸。 */
data class OppoTextureMeta(val wrapRepeat: Boolean, val width: Int, val height: Int)

/**
 * 场景预设注册表。`presets.json` 约 729 KB，**不要在首帧同步解析**——
 * 走 [prewarm] 在后台解析一次并常驻内存；未就绪时 [preset] 返回 null，调用方应保持上一帧场景。
 */
object OppoSceneRegistry {

    private const val TAG = "OppoScene"
    private const val ASSET = "oppo/presets.json"

    /** 世界坐标的纵向半高系数：s = 视口高 / 视口宽（baseline 下等于 2.2222）。 */
    const val BASELINE_S = 2.222222f

    @Volatile
    private var presets: Map<String, OppoScenePreset> = emptyMap()

    @Volatile
    private var entityPool: List<OppoEntity> = emptyList()

    @Volatile
    private var textureMeta: Map<String, OppoTextureMeta> = emptyMap()

    val ready: Boolean get() = presets.isNotEmpty()

    val keys: Set<String> get() = presets.keys

    /** 去重后的实体池（560 个）。渲染器按索引复用，不要修改元素。 */
    val entities: List<OppoEntity> get() = entityPool

    /** 贴图元数据（键 = assets 内相对路径）。采样方式靠它区分 CLAMP / REPEAT。 */
    val textures: Map<String, OppoTextureMeta> get() = textureMeta

    fun preset(key: String): OppoScenePreset? = presets[key]

    /**
     * 冷启动预热：后台解析一次并常驻内存。调用点无需自行切线程。
     * 与 `ParticleSprites.prewarm` 同样的模式（一次调用，内部起协程，幂等）。
     */
    fun prewarm(context: Context) {
        if (ready) return
        val app = context.applicationContext
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { load(app) }
    }

    @Synchronized
    private fun load(context: Context) {
        if (ready) return
        val t0 = System.currentTimeMillis()
        try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(text)

            val poolArr = root.getJSONArray("entities")
            val pool = ArrayList<OppoEntity>(poolArr.length())
            for (i in 0 until poolArr.length()) pool += parseEntity(poolArr.getJSONObject(i))
            entityPool = pool

            val meta = LinkedHashMap<String, OppoTextureMeta>()
            root.optJSONObject("textures")?.let { to ->
                val ks = to.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val v = to.getJSONObject(k)
                    meta[k] = OppoTextureMeta(
                        wrapRepeat = v.optString("wrap").equals("REPEAT", ignoreCase = true),
                        width = v.optInt("w", 0),
                        height = v.optInt("h", 0),
                    )
                }
            }
            textureMeta = meta

            val pj = root.getJSONObject("presets")
            val map = LinkedHashMap<String, OppoScenePreset>(pj.length())
            val names = pj.keys()
            while (names.hasNext()) {
                val key = names.next()
                val o = pj.getJSONObject(key)
                val idxs = o.optJSONArray("entityIdx") ?: JSONArray()
                val ents = ArrayList<OppoEntity>(idxs.length())
                for (i in 0 until idxs.length()) {
                    val idx = idxs.getInt(i)
                    if (idx in pool.indices) ents += pool[idx]
                }
                map[key] = OppoScenePreset(key, parseLights(o.optJSONObject("lighting")), ents)
            }
            presets = map
            Log.i(TAG, "已加载 ${map.size} 个预设 / ${pool.size} 个实体，用时 ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.e(TAG, "场景预设加载失败：${e.message}", e)
        }
    }

    // ---------- 解析 ----------

    private fun parseEntity(o: JSONObject): OppoEntity {
        val u = parseUniforms(o.optJSONObject("uniforms"))
        val tex = parseStringMap(o.optJSONObject("textures"))
        val blend = o.optJSONObject("blend")?.let { bo ->
            LinkedHashMap<String, String>().also { m ->
                val ks = bo.keys()
                while (ks.hasNext()) { val k = ks.next(); m[k] = bo.getString(k) }
            }
        }
        return OppoEntity(
            name = o.optString("name"),
            sort = o.optInt("sort"),
            visible = o.optBoolean("visible", true),
            pos = floatArrayOf(o.optJSONArray("pos")),
            scale = floatArrayOf(o.optJSONArray("scale")),
            rot = floatArrayOf(o.optJSONArray("rot")),
            shaderVert = o.optString("shaderVert").takeIf { it.isNotBlank() && it != "null" },
            shaderFrag = o.optString("shaderFrag").takeIf { it.isNotBlank() && it != "null" },
            uniforms = u,
            textures = tex,
            blend = blend,
            cloud = o.optJSONObject("cloud")?.let {
                OppoEntity.Cloud(
                    group = it.getString("group"), id = it.getString("id"), slot = it.getString("slot"),
                    alpha = it.optDouble("alpha", 1.0).toFloat(),
                    px = it.optDouble("px", 0.0).toFloat(), py = it.optDouble("py", 0.0).toFloat(),
                )
            },
            sunHalo = o.optJSONObject("sunHalo")?.let {
                OppoEntity.SunHalo(it.optInt("level"), it.optDouble("scale", 1.0).toFloat())
            },
            sunMain = o.optJSONObject("sunMain")?.let {
                OppoEntity.SunMain(it.optDouble("scale", 1.0).toFloat())
            },
            isSunPoint = o.optBoolean("sunPoint", false),
            thunder = o.optJSONObject("thunder")?.let { OppoEntity.Thunder(it.optInt("frame")) },
            particle = o.optJSONObject("particle")?.let { p ->
                OppoEntity.Particle(
                    role = p.optString("role"),
                    compute = p.optString("compute").takeIf { it.isNotBlank() && it != "null" },
                    uniforms = parseUniforms(p.optJSONObject("uniforms")),
                    textures = parseStringMap(p.optJSONObject("textures")),
                    effects = run {
                        val arr = p.optJSONArray("effects") ?: return@run emptyList()
                        ArrayList<OppoEntity.Particle.Effect>(arr.length()).also { list ->
                            for (i in 0 until arr.length()) {
                                val e = arr.getJSONObject(i)
                                list += OppoEntity.Particle.Effect(
                                    compute = e.optString("compute").takeIf { it.isNotBlank() && it != "null" },
                                    uniforms = parseUniforms(e.optJSONObject("uniforms")),
                                    textures = parseStringMap(e.optJSONObject("textures")),
                                )
                            }
                        }
                    },
                )
            },
        )
    }

    /** 统一解析 `uniforms`：标量存长度 1，数组按长度存；bool 转 0/1。 */
    private fun parseUniforms(o: JSONObject?): Map<String, FloatArray> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, FloatArray>(o.length())
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            when (val v = o.get(k)) {
                is Number -> out[k] = floatArrayOf(v.toFloat())
                is Boolean -> out[k] = floatArrayOf(if (v) 1f else 0f)
                is JSONArray -> out[k] = FloatArray(v.length()) { v.optDouble(it, 0.0).toFloat() }
            }
        }
        return out
    }

    private fun parseStringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>(o.length())
        val ks = o.keys()
        while (ks.hasNext()) { val k = ks.next(); out[k] = o.getString(k) }
        return out
    }

    private fun parseLights(o: JSONObject?): OppoLights {
        if (o == null) return OppoLights.EMPTY
        val amb = o.optJSONObject("ambient")?.let {
            OppoLights.Ambient(floatArrayOf(it.optJSONArray("color")), it.optDouble("intensity", 1.0).toFloat())
        }
        val dirs = ArrayList<OppoLights.Directional>()
        o.optJSONArray("directional")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                dirs += OppoLights.Directional(
                    name = d.optString("name"),
                    dir = floatArrayOf(d.optJSONArray("dir")),
                    color = floatArrayOf(d.optJSONArray("color")),
                    intensity = d.optDouble("intensity", 1.0).toFloat(),
                    enabled = d.optBoolean("enabled", true),
                )
            }
        }
        val pt = o.optJSONObject("point")?.let {
            OppoLights.Point(
                name = it.optString("name"),
                pos = floatArrayOf(it.optJSONArray("pos")),
                color = floatArrayOf(it.optJSONArray("color")),
                intensity = it.optDouble("intensity", 1.0).toFloat(),
                range = it.optDouble("range", 0.0).toFloat(),
                attenuation = floatArrayOf(it.optJSONArray("attenuation")),
                enabled = it.optBoolean("enabled", true),
            )
        }
        return OppoLights(amb, dirs, pt)
    }

    /** JSONArray → FloatArray，缺位补 0。默认维度 3（pos/scale/rot 标准长度）。 */
    private fun floatArrayOf(a: JSONArray?, defaultDim: Int = 3): FloatArray {
        if (a == null) return FloatArray(defaultDim)
        return FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }
    }
}
