package com.dailyweather.app.scene

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.Modifier

/**
 * GLSL/AGSL 噪声云氛围层 —— FBM（分形布朗运动）连续演化的高空流动云。
 *
 * 定位与南风 PNG 精灵云的分工：
 * - 精灵云（SpriteClouds / skyframes）：云的"形"——写实、1:1 南风质感，是主云层；
 * - 本层：云的"息"——连续演化的噪声云，垫在渐变底与 PNG 帧之间，弥补 8 帧轮播的
 *   离散停顿感，让天空永远在微妙变化；
 * - 结构从"均匀雾"升级为"带明暗的云"：低频 FBM 定云团形状（alpha），高频细节做
 *   内部亮暗 + 竖直光照（云顶亮、云底暗），边缘用较陡的 smoothstep 收出形状——
 *   这样才敢把 alpha 提到 0.2~0.35 而不糊成灰纱。
 * - FBM + domain warp（fbm(p + fbm(p))）是 GLSL 云的标准做法（Shadertoy 同构），
 *   time uniform 由分图层帧率时钟驱动（24fps 足够，云是慢变量）。
 *
 * 实现用 AGSL（SkSL，RuntimeShader，API 33+）；低版本/低画质档直接不绘制，
 * 不做 OpenGL 兜底——氛围层不值得那套复杂度。
 */
private val NOISE_HAZE_AGSF = """
uniform float2 uResolution;
uniform float uTime;
uniform float uIntensity;
layout(color) uniform half4 uTint;

float hash21(float2 p) {
    p = fract(p * float2(234.34, 435.345));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        v += amp * valueNoise(p);
        p = p * 2.03 + float2(17.3, 9.1);
        amp *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    // uv.y = 1.0 是顶部（OpenGL/AGSL 坐标系 y 轴向下）；画布 y=0 也是顶部，
    // 所以 uv.y=1.0 处需要完全遮罩。这里 invert: topMask = smoothstep(1.0, 0.90, uv.y)
    // 使得 y≥1.0（顶部）→ mask=0，y≤0.90 → mask=1（全量）。
    float topMask = smoothstep(1.0, 0.90, uv.y);
    float t = uTime;
    // 云团形状：低频、慢漂移 + domain warp。
    float2 p = uv * float2(2.1, 1.7);
    p.x += t * 0.010;
    p.y += t * 0.003;
    float warp = fbm(p * 1.4 + t * 0.008);
    float shape = fbm(p + warp * 0.6);

    // 内部明暗细节：更高频、更快，让云有"厚度"而非平面剪影。
    float2 q = uv * float2(4.8, 3.8) + float2(t * 0.020, t * 0.009);
    float detail = fbm(q + warp * 0.9);

    // 云体形状：阈值稍陡，团块边缘收得住（避免均匀雾般的灰纱）。
    float cloud = smoothstep(0.40, 0.66, shape);
    // 明暗：云顶亮、云底暗（uv.y=0 是顶部）+ 细节高光。
    float vertical = 1.0 - uv.y * 0.45;
    float shade = (0.45 + 0.55 * detail) * vertical;
    float a = cloud * uIntensity * (0.40 + 0.60 * shade) * topMask;
    // 颜色：暗部压到 tint 的 60%，亮部接近 tint（超 1 自动 clamp）。
    half3 c = uTint.rgb * (0.55 + 0.55 * shade);
    return half4(c, half(a));
}
""".trimIndent()

/** 按天气取噪声云强度：云团明暗结构撑得起更高 alpha，整体抬升；雨天/雾天最浓、晴天最淡。 */
private fun hazeIntensityFor(state: WeatherSceneState): Float {
    val base = when (state.kind) {
        WeatherKind.CLEAR -> 0.20f
        WeatherKind.PARTLY_CLOUDY -> 0.20f
        WeatherKind.WIND -> 0.22f
        WeatherKind.CLOUDY -> 0.24f
        WeatherKind.RAIN -> 0.28f
        WeatherKind.THUNDER -> 0.30f
        WeatherKind.SNOW, WeatherKind.SLEET -> 0.24f
        WeatherKind.FOG -> 0.30f
    }
    // 夜间减半多一点：夜里主体是精灵云与星空，噪声太亮会浮。
    return if (state.isNight) base * 0.55f else base
}

/** FBM 噪声云层。API 33+ 且画质档 Balanced 以上才绘制；时钟与天空帧层共用。 */
@Composable
fun NoiseHazeLayer(
    state: WeatherSceneState,
    modifier: Modifier = Modifier,
    clock: androidx.compose.runtime.MutableFloatState,
) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val quality = LocalEffectQuality.current
    if (quality.particleScale <= 0f || quality.fps.ambient <= 0) return

    val shader = remember { RuntimeShader(NOISE_HAZE_AGSF) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val tint = SpriteSky.cloudTint(state)

    // 噪声层：顶部不做 DstIn/Offscreen 遮罩（实测在 API33+ 上会把顶部压成纯黑带）。
    // 其强度仅 0.14（晴天）且漂移极慢，对顶部观感影响可忽略；顶部稳定由帧层渐隐
    // 和 kind 滞回保证。
    Canvas(modifier) {
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uTime", clock.floatValue)
        shader.setFloatUniform("uIntensity", hazeIntensityFor(state))
        shader.setColorUniform(
            "uTint",
            android.graphics.Color.argb(
                255,
                (tint.red * 255f).toInt(),
                (tint.green * 255f).toInt(),
                (tint.blue * 255f).toInt(),
            ),
        )
        drawRect(brush = brush)
    }
}
