package com.dailyweather.app.scene

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

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
            // 水珠体积感：强度上半提亮为冷白高光、下半压暗成玻璃阴影。
            float hi = smoothstep(0.35, 0.95, c);
            float3 base = (uNight > 0.5) ? float3(0.60, 0.70, 0.85) : float3(0.93, 0.97, 1.0);
            float3 col = base * (0.30 + 0.70 * hi) - float3(0.05, 0.06, 0.08) * (1.0 - hi);
            return half4(col, c * 0.85 * uVisibility);
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

        float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }

        half4 main(float2 coord) {
            float2 halfSize = uSize * 0.5;
            float2 centeredCoord = coord - halfSize;
            float radius = uCornerRadius;
            float sd = sdRoundedRect(centeredCoord, halfSize, radius);
            if (-sd >= uRefractionHeight) return content.eval(coord);
            sd = min(sd, 0.0);
            float d = circleMap(1.0 - -sd / uRefractionHeight) * uRefractionAmount;
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));
            float2 refractedCoord = coord + d * grad;
            return content.eval(refractedCoord);
        }
    """

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
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uStaticDropSize", 1f)
        shader.setFloatUniform("uStaticDropAmount", 1.3f * m + 0.5f)
        shader.setFloatUniform("uStaticDropSpeed", 0.4f * m + 0.9f)
        shader.setFloatUniform("uRunningDropSize", 1f)
        shader.setFloatUniform("uRunningDropAmount", 1.6f * m + 0.7f)
        shader.setFloatUniform("uRunningDropSpeed", m * 0.5f + 0.85f)
        shader.setFloatUniform("uVisibility", visibility.coerceIn(0f, 1f))
    }

    /** 玻璃卡高光 uniforms。 */
    fun applyHighlightUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        timeSec: Float,
        intensity: Float,
        night: Boolean,
        visibility: Float,
    ) {
        val m = intensity.coerceIn(0.2f, 1f)
        shader.setFloatUniform("uResolution", width, height)
        shader.setFloatUniform("uTime", timeSec)
        shader.setFloatUniform("uIntensity", m)
        shader.setFloatUniform("uNight", if (night) 1f else 0f)
        shader.setFloatUniform("uVisibility", visibility.coerceIn(0f, 1f))
        shader.setFloatUniform("uStaticDropSize", 1f)
        shader.setFloatUniform("uStaticDropAmount", 1.3f * m + 0.5f)
        shader.setFloatUniform("uStaticDropSpeed", 0.4f * m + 0.9f)
        shader.setFloatUniform("uRunningDropSize", 1f)
        shader.setFloatUniform("uRunningDropAmount", 1.6f * m + 0.7f)
        shader.setFloatUniform("uRunningDropSpeed", m * 0.5f + 0.85f)
    }

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
    }

    /** API 33+ 才有 RuntimeShader；此判定集中一处，调用方不必重复写版本分支。 */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 便捷：创建 RuntimeShader（仅 API 33+，否则 null）。 */
    fun newShader(source: String): RuntimeShader? =
        if (supported()) RuntimeShader(source) else null

    /** 便捷：包装成 Compose graphicsLayer 可用的 RenderEffect（仅 API 33+）。 */
    fun composeEffect(
        shader: RuntimeShader,
        inputName: String,
    ): androidx.compose.ui.graphics.RenderEffect? =
        if (supported()) {
            RenderEffect.createRuntimeShaderEffect(shader, inputName).asComposeRenderEffect()
        } else null
}
