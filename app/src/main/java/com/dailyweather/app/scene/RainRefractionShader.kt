package com.dailyweather.app.scene

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.dailyweather.app.BuildConfig

/**
 * 降雨折射着色器（v0.1.30 · P0）—— 南风 GlassRainDropsCard 的底层引擎。
 *
 * 逆向文档三·3.1 的 MIUI rain_drop_new **1:1 移植**（AGSL 与 GLSL ES 3.0 同构）：
 * 雨滴不是叠上去的半透明贴片，而是把天空层作为纹理（uTex），计算雨滴法线后
 * 对背景做折射偏移——「透过水滴看世界」的真实玻璃效果。
 *
 * ## 应用方式
 * `RenderEffect.createRuntimeShaderEffect(shader, "uTex")` 作用于天空层
 * （WeatherScreen 的 hazeSource Box）：uTex 即该层自身的绘制结果（天空渐变 + 云 +
 * 降水动效），折射直接扭曲整片天空。API 33+（RuntimeShader），低版本自动不挂载。
 *
 * ## Uniform 更新
 * RenderEffect 创建后 RuntimeShader 的 uniform 仍可更新（API 33+ 语义）：
 * graphicsLayer 的 draw 阶段按 precip 帧率 setFloatUniform 即可驱动动画，
 * 不需要重建 RenderEffect。
 */
@RequiresApi(33)
object RainRefractionShader {

    private const val TAG = "RainRefractionShader"

    /**
     * 公共函数库（两个 shader 共用）：三维哈希噪声、锯齿波、静止水珠、滑落水滴。
     * 与文档 3.1 逐行对应。
     */
    internal const val COMMON = """
        float3 N13(float p) {
            float3 p3 = fract(float3(p) * float3(0.1031, 0.11369, 0.13787));
            p3 += dot(p3, p3.yzx + 19.19);
            return fract(float3((p3.x + p3.y) * p3.z, (p3.x + p3.z) * p3.y, (p3.y + p3.z) * p3.x));
        }

        float Saw(float b, float t) {
            return smoothstep(0.0, b, t) * smoothstep(1.0, b, t);
        }

        float N1(float t) {
            return fract(sin(t * 12345.564) * 7658.76);
        }

        // 滑落水滴层：MIUI rain_drop_new 的忠实移植（v4.3.68 观感）。
        float2 DropLayer(float2 uv, float t) {
            t *= uRunningDropSpeed;
            float2 UV = uv;
            uv /= uRunningDropSize;
            uv.y += t * 0.55;
            float2 a = float2(6.0, 1.0);
            float2 grid = a * 3.0;
            float2 id = floor(uv * grid);
            uv.y += N1(id.x);
            id = floor(uv * grid);
            float3 n = N13(id.x * 35.2 + id.y * 2376.1);
            float2 st = fract(uv * grid) - float2(0.5, 0.0);
            float x = (n.x - 0.5) * 0.7;
            float ti = fract(t + n.z);
            float y = (Saw(0.9, ti) - 0.5) * 0.9 + 0.5;
            float2 p = float2(x, y);
            float d = length((st - p) * a.yx);
            float mainDrop = smoothstep(0.4, 0.0, d);
            float rr = smoothstep(1.0, y, st.y);
            float r = sqrt(rr);
            float cd = abs(st.x - x);
            float trail = smoothstep(0.23 * r, 0.15 * rr, cd);
            float trailFront = smoothstep(-0.02, 0.02, st.y - y);
            trail *= trailFront * rr;
            float yBead = fract(UV.y * 10.0) + (st.y - 0.5);
            float dd = length(st - float2(x, yBead));
            float droplets = smoothstep(0.3, 0.0, dd);
            float m = mainDrop + droplets * r * trailFront;
            return float2(m, trail);
        }

        float StaticDrops(float2 uv, float t) {
            t *= uStaticDropSpeed;
            uv *= 40.0 / uStaticDropSize;
            float2 id = floor(uv);
            uv = fract(uv) - 0.5;
            float3 n = N13(id.x * 107.45 + id.y * 3543.654);
            float2 p = (n.xy - 0.5) * 0.7;
            float d = length(uv - p);
            float fade = Saw(0.025, fract(t + n.z));
            return smoothstep(0.3, 0.0, d) * fract(n.z * 10.0) * fade;
        }

        float Drops(float2 uv, float t, float rs, float ra) {
            float s = StaticDrops(uv, t * 1.2) * rs * uStaticDropAmount;
            float2 m1 = DropLayer(uv, t * 1.85) * ra * uRunningDropAmount;
            return smoothstep(0.3, 1.0, s + m1.x);
        }
    """

    /** 全屏折射 main：uTex = 天空层内容，法线折射偏移采样。 */
    const val REFRACTION = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uStaticDropSize;
        uniform float uStaticDropAmount;
        uniform float uStaticDropSpeed;
        uniform float uRunningDropSize;
        uniform float uRunningDropAmount;
        uniform float uRunningDropSpeed;
        uniform float uVisibility;
        uniform shader uTex;
    """ + COMMON + """
        half4 main(float2 xy) {
            float2 UV = xy / uResolution;
            float2 uv = (xy - 0.5 * uResolution) / uResolution.y;
            uv.y = 1.0 - uv.y;
            float t = uTime * 2.3 * 0.2;
            float rainAmount = sin(UV.x * UV.y) * 0.3 + 0.7;
            float staticDrops = smoothstep(-0.5, 1.0, rainAmount) * 2.0;
            float c = Drops(uv, t, staticDrops, rainAmount);
            float cx = Drops(uv + float2(0.0005, 0.0), t, staticDrops, rainAmount);
            float2 n = float2(c - cx, cx - c);
            float2 texUV = UV + n;
            half4 baseColor = uTex.eval(UV * uResolution);
            half4 refracted = uTex.eval(texUV * uResolution);
            return mix(baseColor, refracted, uVisibility);
        }
    """

    /** 玻璃卡高光层：同函数库计算水珠强度，输出「亮核+暗缘」的水珠体积光。 */
    const val HIGHLIGHT = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uIntensity;
        uniform float uNight;
        uniform float uVisibility;
        // COMMON 的 Drops()/DropLayer() 会用到下面这 6 个，缺一个就是 AGSL 编译错误。
        // ⚠️ 曾漏掉 uRunningDropSize（RainRefractionShader.kt:52 的 `uv /= uRunningDropSize;`），
        // 表现为**雨天/雪天/雷天一进首页就崩溃**：
        //   IllegalArgumentException: error: 31: unknown identifier 'uRunningDropSize'
        uniform float uRunningDropSize;
        uniform float uRunningDropSpeed;
        uniform float uRunningDropAmount;
        uniform float uStaticDropSpeed;
        uniform float uStaticDropAmount;
        uniform float uStaticDropSize;
    """ + COMMON + """
        half4 main(float2 xy) {
            float2 UV = xy / uResolution;
            float2 uv = (xy - 0.5 * uResolution) / uResolution.y;
            uv.y = 1.0 - uv.y;
            float t = uTime * 2.3 * 0.2;
            float staticDrops = smoothstep(-0.5, 1.0, uIntensity) * 2.0;
            float c = Drops(uv, t, staticDrops, uIntensity);
            // 玻璃水珠 = **轮廓光**，不是实心贴片。
            // 旧实现把水珠mask（c）直接当 alpha 填一块冷灰色（夜间 0.60,0.70,0.85 × 0.55），
            // 装机看就是"一坨坨灰白斑点"（2026-10-12 截图复现），完全不像水。
            // 现在对 c 做双向有限差分得水珠法线，只点亮/压暗边缘：
            //   hi    = 左上入射光的镜面高光（水珠上缘一道亮弧）
            //   shade = 右下缘的玻璃暗边
            //   body  = 珠体极淡的冷色（0.06），保持"透明水"而不是"灰漆"。
            float e = 0.0015;
            float cx = Drops(uv + float2(e, 0.0), t, staticDrops, uIntensity);
            float cy = Drops(uv + float2(0.0, e), t, staticDrops, uIntensity);
            float2 n = normalize(float2(c - cx, c - cy) + float2(1e-5, 1e-5));
            float mask = smoothstep(0.05, 0.35, c);
            float hi = pow(clamp(dot(n, normalize(float2(-0.55, 0.83))), 0.0, 1.0), 6.0) * mask;
            float shade = pow(clamp(dot(n, normalize(float2(0.60, -0.80))), 0.0, 1.0), 3.0) * mask;
            float body = smoothstep(0.25, 0.60, c);
            float3 lit = (uNight > 0.5) ? float3(0.72, 0.83, 1.00) : float3(1.00, 1.00, 1.00);
            float3 col = lit * hi + float3(0.02, 0.03, 0.05) * shade + lit * body * 0.10;
            float alpha = (hi * 0.60 + shade * 0.22 + body * 0.06) * uVisibility;
            return half4(col, alpha);
        }
    """

    /** SDF 圆角矩形折射（文档 3.3，LiquidGlassSurface 核心）：content = 卡片内容。 */
    const val SDF = """
        uniform shader content;
        uniform float2 uSize;
        uniform float uCornerRadius;
        uniform float uRefractionHeight;
        uniform float uRefractionAmount;

        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                return sign(coord) * normalize(max(cornerCoord, 0.0));
            } else {
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }
        }

        float circleMap(float x) {
            // ⚠️ 必须 clamp：`1.0 - x*x` 一旦为负，sqrt 返回 NaN，
            // 经 `d -> refractedCoord` 传染后 `content.eval(NaN)` 整层输出透明。
            // 实测症状：**整个卡片（含水珠层与文字）全空白**。
            float t = clamp(1.0 - x * x, 0.0, 1.0);
            return 1.0 - sqrt(t);
        }

        half4 main(float2 coord) {
            // 兜底：尺寸/厚度还没设好（首帧）或非法时**直通**，绝不让 NaN 流出去。
            if (uSize.x <= 1.0 || uSize.y <= 1.0 || uRefractionHeight <= 0.5) {
                return content.eval(coord);
            }
            float2 halfSize = uSize * 0.5;
            float2 centeredCoord = coord - halfSize;
            float radius = min(uCornerRadius, min(halfSize.x, halfSize.y));
            float sd = sdRoundedRect(centeredCoord, halfSize, radius);
            if (-sd >= uRefractionHeight) return content.eval(coord);
            sd = min(sd, 0.0);
            float d = circleMap(1.0 - -sd / uRefractionHeight) * uRefractionAmount;
            float gradRadius = max(min(radius * 1.5, min(halfSize.x, halfSize.y)), 0.5);
            float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));
            float2 refractedCoord = clamp(coord + d * grad, float2(0.0), uSize - float2(1.0));
            return content.eval(refractedCoord);
        }
    """

    private var lastLoggedSize = ""

    /** SDF 边缘折射 uniforms。 */
    fun applySdfUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadiusPx: Float,
        refractionHeightPx: Float,
        refractionAmountPx: Float,
    ) {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uCornerRadius", cornerRadiusPx)
        shader.setFloatUniform("uRefractionHeight", refractionHeightPx)
        shader.setFloatUniform("uRefractionAmount", refractionAmountPx)
        // 诊断：仅在 debug 且尺寸**发生变化**时打印一次（排查"整卡空白"，见 GlassRaindropsCard）。
        if (BuildConfig.DEBUG) {
            val tag = "${width}x${height}"
            if (tag != lastLoggedSize) {
                lastLoggedSize = tag
                Log.i(
                    TAG,
                    "SDF uniforms: size=$tag corner=$cornerRadiusPx " +
                        "refractH=$refractionHeightPx amount=$refractionAmountPx",
                )
            }
        }
    }

    /** API 33+ 才有 RuntimeShader；此判定集中一处，调用方不必重复写版本分支。 */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 水珠网格的「参考高度」：MIUI `rain_drop_new` 的 `uStaticDropSize / uRunningDropSize = 1.0`
     * 是**按 1080×2400 全屏**标定的（Drops 里 `uv *= 40.0 / size`，即每 60px 一颗静止水珠）。
     *
     * AGSL 的 `uv = (xy - 0.5*res) / res.y` 让网格数量**只随层高变化**，所以同一套参数搬到
     * 462px 高的玻璃卡上，水珠会密 5 倍——实测看起来像噪点雪花，而不是"雨滴打在玻璃上"。
     *
     * 按「每 60px 一颗」反解：`size = 2400 / 层高`。
     * 全屏时（height=2400）恰好退回 **1.0**，与原版参数**完全一致**（天空层折射不受影响）；
     * 卡片（height=462）得到 5.2 → 水珠疏密与全屏同源。
     */
    private fun dropScaleFor(height: Float): Float =
        if (height > 1f) (2400f / height).coerceAtLeast(1f) else 1f

    /** 全屏折射 uniforms（文档 3.2 精确公式；m = intensity 0–1）。 */
    fun applyRefractionUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        timeSec: Float,
        intensity: Float,
        visibility: Float,
    ) {
        val m = intensity.coerceIn(0f, 1f)
        val dropScale = dropScaleFor(height)
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uStaticDropSize", dropScale)
        shader.setFloatUniform("uStaticDropAmount", 1.3f * m + 0.5f)
        shader.setFloatUniform("uStaticDropSpeed", 0.4f * m + 0.9f)
        shader.setFloatUniform("uRunningDropSize", dropScale)
        shader.setFloatUniform("uRunningDropAmount", 1.6f * m + 0.7f)
        shader.setFloatUniform("uRunningDropSpeed", m * 0.5f + 0.85f)
        shader.setFloatUniform("uVisibility", visibility.coerceIn(0f, 1f))
    }

    /**
     * 玻璃卡高光 uniforms。
     *
     * @param dropSizeMul 水珠尺寸倍率（>1 = 更小的水珠）。MIUI 的 `Drops` 网格是按
     *   全屏标定的，卡片上即使按 [dropScaleFor] 反解，珠子仍偏大偏糊（实机截图像雪花噪点）；
     *   卡片传 1.6 让珠子收小到"玻璃上的雨滴"尺度，全屏折射保持 1.0 与原版一致。
     */
    fun applyHighlightUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        timeSec: Float,
        intensity: Float,
        night: Boolean,
        visibility: Float,
        dropSizeMul: Float = 1f,
    ) {
        val m = intensity.coerceIn(0.2f, 1f)
        val dropScale = dropScaleFor(height) * dropSizeMul.coerceAtLeast(0.5f)
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uIntensity", m)
        shader.setFloatUniform("uNight", if (night) 1f else 0f)
        shader.setFloatUniform("uVisibility", visibility.coerceIn(0f, 1f))
        shader.setFloatUniform("uStaticDropSize", dropScale)
        shader.setFloatUniform("uStaticDropAmount", 1.3f * m + 0.5f)
        shader.setFloatUniform("uStaticDropSpeed", 0.4f * m + 0.9f)
        shader.setFloatUniform("uRunningDropSize", dropScale)
        shader.setFloatUniform("uRunningDropAmount", 1.6f * m + 0.7f)
        shader.setFloatUniform("uRunningDropSpeed", m * 0.5f + 0.85f)
    }

    /**
     * 便捷：创建 RuntimeShader（仅 API 33+，否则 null）。
     *
     * ⚠️ 必须吞掉编译异常：AGSL 在**运行时**才编译，`RuntimeShader(source)` 一旦源码有
     * （如漏声明 uniform）就抛 `IllegalArgumentException`——这不是异常分支，而是**必崩**。
     * 实测：`HIGHLIGHT` 漏了 `uRunningDropSize` 时，雨天/雪天/雷天一进首页就闪退。
     * 降级为 null（只是没有雨滴效果）远好于崩溃。
     */
    fun newShader(source: String): RuntimeShader? {
        if (!supported()) return null
        return runCatching { RuntimeShader(source) }
            .onFailure { Log.e(TAG, "AGSL 编译失败，降级为无雨滴效果：${it.message}") }
            .getOrNull()
    }

    /** 便捷：包装成 Compose graphicsLayer 可用的 RenderEffect（仅 API 33+）。 */
    fun composeEffect(
        shader: RuntimeShader,
        inputName: String,
    ): androidx.compose.ui.graphics.RenderEffect? =
        if (supported()) {
            RenderEffect.createRuntimeShaderEffect(shader, inputName).asComposeRenderEffect()
        } else null
}
