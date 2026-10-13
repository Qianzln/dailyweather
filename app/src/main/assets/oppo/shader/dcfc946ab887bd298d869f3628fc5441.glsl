#version 300 es
precision highp float;

#ifndef AMBIENT_LIGHT 
#define AMBIENT_LIGHT 1
#endif


#ifndef DIR_LIGHT 
#define DIR_LIGHT 1
#endif


#ifndef POINT_LIGHT 
#define POINT_LIGHT 1
#endif

#ifndef MAX_DIR_LIGHTS
#define MAX_DIR_LIGHTS 10  
#endif

#ifndef MAX_POINT_LIGHTS
#define MAX_POINT_LIGHTS 10 
#endif

#ifndef AMBIENT_LIGHT
#define AMBIENT_LIGHT 1     // 默认启用环境光
#endif

struct AmbientLight {
    vec3 color;
    float intensity;
};
uniform AmbientLight u_ambientLight;
    

// 着色器中添加安全默认值
vec3 getAmbientLight() {
    return u_ambientLight.color * u_ambientLight.intensity;
}

struct PointLight {
    int enable;
    vec3 position;
    vec3 color;
    float range;
    float intensity;
    vec3 attenuation;
};

struct DirectionalLight {
    int enable;
    vec3 direction;
    vec3 color;
    float intensity;
};

uniform PointLight u_pointLights[MAX_POINT_LIGHTS];
uniform DirectionalLight u_directionalLights[MAX_DIR_LIGHTS];
uniform int u_numPointLights;
uniform int u_numDirLights;

// ================== 光照计算函数 ==================
vec3 calculateDirectionalLight(DirectionalLight light, vec3 normal) {
    vec3 lightDir = normalize(-light.direction);
    float diff = max(dot(normal, lightDir), 0.0);
    return light.color * (diff * light.intensity);
}

vec3 calculatePointLight(PointLight light, vec3 normal, vec3 fragPos) {
    vec3 lightDir = normalize(light.position - fragPos);
    float distance = length(light.position - fragPos);
    float attenuation = 1.0 / (light.attenuation.x + 
                              light.attenuation.y * distance + 
                              light.attenuation.z * (distance * distance));
    
    float diff = max(dot(normal, lightDir), 0.0);
    return light.color * (diff * light.intensity) * attenuation;
}

vec3 calculateLighting(vec3 normal, vec3 fragPos) {
    vec3 result = vec3(0.0);
    
    // 方向光计算（编译时展开循环）
    for(int i = 0; i < MAX_DIR_LIGHTS; ++i) {
        if(i >= u_numDirLights) break;
        if(u_directionalLights[i].enable==0) continue;
        result += calculateDirectionalLight(u_directionalLights[i], normal);
    }
    
    // 点光源计算（编译时展开循环）
    for(int i = 0; i < MAX_POINT_LIGHTS; ++i) {
        if(i >= u_numPointLights) break;
        if(u_pointLights[i].enable==0) continue;
        result += calculatePointLight(u_pointLights[i], normal, fragPos);
    }
    
    // 环境光处理
    #if AMBIENT_LIGHT
    result += u_ambientLight.color * u_ambientLight.intensity;
    #else
    result += vec3(0.05); // 最低限度环境光
    #endif
    
    return result;
}


// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;

// @Texture(NormalTex)=[1,1,0,0]
uniform sampler2D u_NormalTex;

// @Range(AlphaValue, 0.0, 1.0, 0.01)=1.0
uniform float _AlphaValue;

// @Range(DirectionLightSmooth, 0.0, 10.0, 0.01)=1.0
uniform float u_DirectionLightSmooth;
// @Range(PointLightSmooth, 0.0, 10.0, 0.01)=1.0
uniform float u_PointLightSmooth;

// @Range(GradientAngle, 0.0, 360.0, 0.01)=30
uniform float _GradientAngle;
// @Color(GradientColorA, 0.01)=[1,1,1,1]
uniform vec4 _GradientColorA;
// @Color(GradientColorB, 0.01)=[1,1,1,1]
uniform vec4 _GradientColorB;
// @Color(GradientColorC, 0.01)=[1,1,1,1]
uniform vec4 _GradientColorC;
// @Range(GradientRangeAB, 0.0, 1.0, 0.01)=0.66
uniform float _GradientRangeAB;
// @Range(GradientRangeBC, 0.0, 1.0, 0.01)=0.33
uniform float _GradientRangeBC;


// @Range(ContrastRange, 0.0, 1.0, 0.01)=0.5
uniform float _ContrastRange;
// @Range(ContrastFadeWidth, 0.0, 1.0, 0.01)=0.5
uniform float _ContrastFadeWidth;
// @Range(HightLights, -1.0, 1.0, 0.01)=0.0
uniform float _HightLights;
// @Range(Shadows, -1.0, 1.0, 0.01)=0.0
uniform float _Shadows;


// @Range(gammaValue, 0.1, 5.0, 0.1)=2.2
uniform float _Gamma;

// @Texture(NoiseTex)=[1,1,0,0]
uniform sampler2D u_NoiseTex;


// @Range(NoiseAngle, 0.0, 360.0, 0.01)=0.0
uniform float u_NoiseAngle;
// @Range(DisplaceThresholdMax, 0.0, 1.0, 0.01)=1.0
uniform float u_DisplaceThresholdMax;
// @Range(DisplaceThresholdMin, 0.0, 1.0, 0.01)=1.0
uniform float u_DisplaceThresholdMin;
// @Range(DisplaceThreshold, 0.0, 1.0, 0.01)=0.0
uniform float u_DisplaceThreshold;
// @Range(NoiseStrengthOut, 0.0, 1.0, 0.01)=0.0
uniform float _NoiseStrengthOut;    // 扰动强度 (0.0-1.0)
// @Range(NoiseStrengthIn, 0.0, 1.0, 0.01)=0.0
uniform float _NoiseStrengthIn;    // 扰动强度 (0.0-1.0)
// @Vec2(NoiseTiling,0.1)=[1,1]
uniform vec2 _NoiseTiling;      // 平铺系数 (建议2-4)
// @Range(NoiseSpeedX, 0.0, 1.0, 0.01)=0.0
uniform float _NoiseSpeedX;      // X轴移动速度 (0.01-0.1)
// @Range(NoiseSpeedY, 0.0, 1.0, 0.01)=0.0
uniform float _NoiseSpeedY;      // Y轴移动速度 (0.01-0.1)

uniform float _NoiseEggFactor;

// @Vec4(DissipateTiling,0.01)=[1,1,0,0]
uniform vec4 u_DissipateTiling;      // 平铺系数 (建议2-4)
// @Vec2(DirectionCenter,0.01)=[0.5,0.5]
uniform vec2 u_DirectionCenter;
// @Range(Dissipate, 0.0, 1.0, 0.01)=0.0
uniform float u_Dissipate;
// @Range(DissipateFactor, 0.0, 1.0, 0.01)=1.0
uniform float u_DissipateFactor;
// @Vec2(DissipateDirection,0.01)=[1,0]
uniform vec2 u_DissipateDirection;
// @Range(DissipateStrength, 0.0, 1.0, 0.01)=0.0
uniform float u_DissipateStrength;
// @Range(DissipateEdgeWidth, 0.0, 1.0, 0.01)=0.0
uniform float u_DissipateEdgeWidth;
// @Range(DissipateFadeOut, 0.0, 1.0, 0.01)=0.15
uniform float u_DissipateFadeOut;


in vec3 v_WorldPosition;
in vec2 v_texCoord;
out vec4 fragColor;

vec3 applyGammaCorrection(vec3 color, float gamma) {
    return pow(color, vec3(1.0/gamma)); // Gamma矫正核心算法[1,2](@ref)
}

//环境光
vec3 ambientLight(float ambientStrength, vec4 lightColor) {
    return ambientStrength * lightColor.rgb;
}

//方向光
vec3 directionalLight(DirectionalLight light, vec3 normal) {
    vec3 direction = light.direction;
    direction.x = -direction.x;
    direction.y = -direction.y;
    vec3 lightDir = normalize(direction);
    float diff = max(dot(normalize(normal), normalize(lightDir)), 0.0);
    float smoothDiff = smoothstep(0.0, u_DirectionLightSmooth, diff);
    return light.intensity * smoothDiff * light.color;
}

//点光源
vec3 pointLight(PointLight light, vec3 normal, vec3 fragPos) {
    vec3 position = light.position;
    vec3 lightDir = normalize(position - fragPos);
    float dis = length(position - fragPos);
    // vec3 lightAttenuation = light.attenuation;
    // float attenuation = 1.0 / (lightAttenuation.x + lightAttenuation.y * dis + lightAttenuation.z * (dis * dis));
    
    float distanceSqr = dot(dis, dis); // 距离平方
    float range = light.range / 200.; 
    float rangeSqr = range * range;       // 范围平方
    float attenuation = pow(max(1. - pow(distanceSqr / rangeSqr, 2.), 0.), 2.);

    float diff = max(dot(normal, lightDir), 0.0);
    float smoothDiff = smoothstep(0.0, u_PointLightSmooth, diff);
    return light.color * (smoothDiff * light.intensity) * attenuation;
}

//扰动
float getDynamicNoise() {
    if (_NoiseStrengthIn > 0. || _NoiseStrengthOut > 0.) {
        // 步骤1: 中心化坐标并旋转
        float rad = radians(u_NoiseAngle);
        float v_cos = cos(rad);
        float v_sin = sin(rad);
        float x = v_texCoord.x - 0.5;
        float y = v_texCoord.y - 0.5;
        float nx = (v_cos * x - v_sin * y);
        float ny = (v_sin * x + v_cos * y);
        vec2 rotatedCoord = vec2(nx + 0.5, ny + 0.5); // 还原坐标系

        // 步骤2: 简化噪声采样
        float maskValue = texture(u_NormalTex, v_texCoord).a;
        vec2 noiseCoord = vec2(rotatedCoord.s + u_time * _NoiseSpeedX * 0.1 + _NoiseEggFactor * _NoiseSpeedX * 0.1, rotatedCoord.t + u_time * _NoiseSpeedY * 0.1 + _NoiseEggFactor * _NoiseSpeedY * 0.1);
        float displace = texture(u_NoiseTex, noiseCoord * _NoiseTiling).r;
        float displace_k = smoothstep(u_DisplaceThresholdMin - u_DisplaceThreshold, 
                                        u_DisplaceThresholdMax + u_DisplaceThreshold, 
                                        displace) * (1. - maskValue) * _NoiseStrengthOut; // 使用单通道
        displace_k += displace * _NoiseStrengthIn * maskValue;
        return displace_k;
    }
    return 0.0;
}

//消散
float getDissipate() {
    if (u_Dissipate < 0.001) return 1.0;
    
    // 采样噪声纹理
    vec2 dissipateUV = vec2(
        v_texCoord.x * u_DissipateTiling.x + u_DissipateTiling.z * u_time,
        v_texCoord.y * u_DissipateTiling.y + u_DissipateTiling.w * u_time
    );
    float dissipateTex = texture(u_NoiseTex, dissipateUV).g;
    
    // 计算方向性因素
    float directionFactorDirection = dot(v_texCoord, u_DissipateDirection);
    float distanceToCenter = length(v_texCoord - u_DirectionCenter);
    float directionFactor = 1.0 - distanceToCenter * 2.0;
    
    // 基础阈值
    float baseThreshold = u_Dissipate * u_DissipateFactor;
    
    // 方向性影响 - 当u_Dissipate较小时减弱其影响
    float directionEffect = directionFactor * directionFactorDirection * u_DissipateStrength;
    
    // 调整阈值，考虑方向性影响
    float adjustedThreshold = baseThreshold - directionEffect;
    
    // 动态边缘宽度 - u_Dissipate越小，边缘越宽
    float edgeSoftness = 1.0 - smoothstep(0.0, 0.2, u_Dissipate);
    float dynamicEdgeWidth = u_DissipateEdgeWidth * (1.0 + 2.0 * edgeSoftness);
    
    // 计算alpha因子
    float alphaFactor = smoothstep(
        adjustedThreshold - dynamicEdgeWidth,
        adjustedThreshold + dynamicEdgeWidth,
        dissipateTex
    );
    
    // 当u_Dissipate接近0时，平滑过渡到1
    float fadeOut = smoothstep(0.0, u_DissipateFadeOut, u_Dissipate);
    return mix(1.0, alphaFactor, fadeOut);
}


// 对比度
vec3 adjustContrast(vec3 color, vec3 mixColor) {
    const vec3 kRec709Luma = vec3(0.2126, 0.7152, 0.0722);
    float brightness = dot(color, kRec709Luma);
    
    // 1. 用平滑过渡替代硬边界
    float transitionWidth = _ContrastFadeWidth; // 过渡带宽度
    float isAbove = smoothstep(
        _ContrastRange - transitionWidth,
        _ContrastRange + transitionWidth,
        brightness
    );

    // 2. 分别计算亮区/暗区增强
    vec3 highlights = mix(mixColor, color, _HightLights + 1.0);
    vec3 shadows = mix(mixColor, color, _Shadows + 1.0);
    
    // 3. 平滑混合两区域结果[1,8](@ref)
    vec3 adjusted = mix(shadows, highlights, isAbove);
    
    // 4. 与原色混合避免过度修改
    return mix(color, adjusted, 0.5); // 添加全局强度控制
}

vec3 multiLinearGradient(vec2 uv, vec3 colorA, vec3 colorB, vec3 colorC, float angle, float rangeAB, float rangeBC) {
    // 1. 角度转弧度并计算旋转矩阵
    float rad = radians(angle);
    float sinRot = sin(rad);
    float cosRot = cos(rad);
    
    // 2. 旋转UV坐标（绕中心点0.5）
    uv -= 0.5;
    vec2 rotatedUV;
    rotatedUV.x = uv.x * cosRot - uv.y * sinRot;
    rotatedUV.y = uv.x * sinRot + uv.y * cosRot;
    rotatedUV += 0.5;
    
    // 3. 计算渐变位置（旋转后的X轴）
    float t = rotatedUV.x;
    
    // A→B的过渡：smoothstep实现平滑边界[4,5](@ref)
    float fadeAB = smoothstep(0.0, rangeAB, t);
    // B→C的过渡
    float fadeBC = smoothstep(rangeBC, 1.0, t);
    
    // 混合三段颜色：
    vec3 colorMid = mix(colorA, colorB, fadeAB);         // A→B渐变
    return mix(colorMid, colorC, fadeBC * fadeAB);     // B→C渐变（叠加A→B）
}

// ACES Filmic 色调映射函数
vec4 ACESFilmic(vec4 col) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    vec3 result = (col.rgb * (a * col.rgb + b)) / (col.rgb * (c * col.rgb + d) + e);
    return vec4(clamp(result, 0.0, 1.0), col.a);
}

vec3 getDirectionalColor(vec3 normal, int point) {
    if (u_directionalLights[point].enable == 0) {
        return vec3(0.);
    }
    if (u_directionalLights[point].intensity <= 0.0) {
        return vec3(0.);
    }
    return directionalLight(u_directionalLights[point], normal);
}

void main() {
    if (_AlphaValue <= 0.0) {
        discard;
    }
    
    float dynamicNoise = getDynamicNoise();
    float dissipateValue = getDissipate();
    if (dissipateValue <= 0.0) {
        discard;
    }

    vec4 nomalColor = texture(u_NormalTex, vec2(v_texCoord.x + dynamicNoise, v_texCoord.y - dynamicNoise));
    float finalAlpha = nomalColor.a * _AlphaValue * dissipateValue;
    if (finalAlpha <= 0.0) {
        discard;
    }

    vec3 normal = normalize(nomalColor.rgb * 2.0 - 1.0); // 归一化

    vec3 directionalColor = vec3(0.);
    for(int i = 0; i < u_numDirLights; ++i) {
        if (u_directionalLights[i].enable == 0) {
            continue;
        }
        if (u_directionalLights[i].intensity <= 0.0) {
            continue;
        }
        directionalColor += directionalLight(u_directionalLights[i], normal);
    }

    vec3 finalDirectionalColor = directionalColor;

    vec3 pointLightColor = vec3(0.);
    for(int i = 0; i < u_numPointLights; ++i) {
        if (u_pointLights[i].enable == 0) {
            continue;
        }
        if (u_pointLights[i].intensity <= 0.0) {
            continue;
        }
        pointLightColor += pointLight(u_pointLights[i], normal, v_WorldPosition);
    }

    vec3 gradientColor = multiLinearGradient(v_texCoord, 
        _GradientColorA.rgb, _GradientColorB.rgb, _GradientColorC.rgb, _GradientAngle, _GradientRangeAB, _GradientRangeBC);

    vec3 finalGradientColor = gradientColor;

    fragColor = vec4(pointLightColor + finalDirectionalColor + finalGradientColor, finalAlpha);
    // 对比度
    fragColor.rgb = adjustContrast(fragColor.rgb, gradientColor);
    // 灰度系数矫正
    fragColor.rgb = applyGammaCorrection(fragColor.rgb, _Gamma);
    // aces
    fragColor.rgb = applyGammaCorrection(fragColor.rgb, 0.6);
    fragColor = ACESFilmic(fragColor);
}

