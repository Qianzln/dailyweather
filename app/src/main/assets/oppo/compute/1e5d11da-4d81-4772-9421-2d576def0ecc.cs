#version 320 es
precision highp float;

layout(local_size_x = 256, local_size_y = 1) in;

// 修改为结构体形式匹配C++定义
struct Particle {
    vec4 position; // xyz: 粒子位置, w: 粒子大小
    vec4 acceleration; // xyz: 加速度, w: 预留
    vec4 speed; // xyz: 移动速度 w: 随机种子
    vec4 color; // rgba
    vec4 lifeTime; // x: 当前生命值, y: 整个生命周期, z: 一直存在, w: color alpha random
    vec4 originPos;// 保留初始位置
};

// 粒子双缓冲SSBO定义
layout(std430, binding=0) buffer InputBufferParticles {
    Particle particlesIn[];
};
layout(std430, binding=1) buffer OutputBufferParticles {
    Particle particlesOut[];
};

uniform float dt;
uniform float u_Time;
uniform int u_Active;

// @Range(activeParticeNumber, 0, 500, 1)=50
uniform float activeParticeNumber;

// @Vec2(emitterPosition, 0.1) = [0.0, 0.0]
uniform vec2 emitterPosition;

// @Vec2(emitterSize, 0.1) = [0.5, 0.1]
uniform vec2 emitterSize;

// @Color4b(minColor, randomColor, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 minColor;

// @Color(maxColor, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 maxColor;

// @Range(colorWeight, 0.0, 1.0, 0.1)=0.5
uniform float colorWeight;

// @Vec4b(alphaRange, alphaRandom, 0.01)=[0, 1.0, 0.0, 1.0]
uniform vec4 alphaRange;

// @Vec3(direc,0.1)=[0,0,0]
uniform vec3 direc;

// @Vec4b(offsetAngle, angleRandom, 0.1)=[-10, 10.0, 0.0, 1.0]
uniform vec4 offsetAngle;

// @Vec4b(speed, speedRandom, 0.1)=[0.2, 0.5, 1.0, 1.0]
uniform vec4 speed;

// @Vec4b(lifeTime, lifeRandom, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 lifeTime;

// @Range(elapsedTime, 0.0, 1.0, 0.01)=0.0
uniform float elapsedTime;

// @Vec4b(sizeAndNoise, sizeRandom, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 sizeAndNoise;

// @Vec4(rotationAxis, 0.1)=[1.0, 1.0, 1.0, 0.5]
uniform vec4 rotationAxis;

// @Vec4b(emitterInfo, isLoop, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 emitterInfo;

// @Label(星星参数)

// @Vec2(uCenter, 0.1) = [0.0, 0.0]
uniform vec2 uCenter;

// @Range(trailCount, 0.0, 100.0, 1)=5
uniform float trailCount;

// @Range(starMinSpeed, 0.0, 1.0, 0.01)=0.01
uniform float starMinSpeed;

// @Range(orbitSpeed, 0.0, 1.0, 0.01)=0
uniform float orbitSpeed;

// @Range(orbitSpeedPrompt, 0.0, 1.0, 0.01)=1.0
uniform float orbitSpeedPrompt;

// @Range(exponent, 0.0, 2.0, 0.01)=1.0
uniform float exponent;

// @Range(trailInSpeed, 0.0, 4.0, 0.01)=2.0
uniform float trailInSpeed;

// @Range(trailOutSpeed, 0.0, 4.0, 0.01)=2.0
uniform float trailOutSpeed;

// @Vec4b(trailLen, trailLenRandom, 0.1)=[0.3, 0.5, 1.0, 1.0]
uniform vec4 trailLen;

// @Label(粒子分层)
// @Vec2(depthRatio, slider,0.01)=[0.2, 0.66]
uniform vec4 depthRatio;

// @Label(近景粒子参数)
// @Range(closeSpeed, 0.0, 2.0, 0.1)=1.5
uniform float closeSpeed;
// @Range(closeSize, 0.0, 2.0, 0.1)=1.8
uniform float closeSize;
// @Range(closeAlpha, 0.0, 2.0, 0.1)=1.0
uniform float closeAlpha;
// @Range(closeDisturb, 0.0, 2.0, 0.1)=1.3
uniform float closeDisturb;

// @Label(中景粒子参数)
// @Range(midSpeed, 0.0, 2.0, 0.1)=1.0
uniform float midSpeed;
// @Range(midSize, 0.0, 2.0, 0.1)=1.0
uniform float midSize;
// @Range(midAlpha, 0.0, 2.0, 0.1)=1.0
uniform float midAlpha;
// @Range(midDisturb, 0.0, 2.0, 0.1)=1.0
uniform float midDisturb;

// @Label(远景粒子参数)
// @Range(farSpeed, 0.0, 2.0, 0.1)=0.8
uniform float farSpeed;
// @Range(farSize, 0.0, 2.0, 0.1)=0.5
uniform float farSize;
// @Range(farAlpha, 0.0, 2.0, 0.1)=0.7
uniform float farAlpha;
// @Range(farDisturb, 0.0, 2.0, 0.1)=0.7
uniform float farDisturb;

// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;


float PI = 3.141592653589793;

float rand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

float getAngle(vec2 offset) {
    return atan(offset.y, offset.x);
}

bool isValueNaN(float val) {
    return (val != val);
}

bool isVectorValid(vec3 v) {
    return !(isValueNaN(v.x) || isValueNaN(v.y) || isValueNaN(v.z));
}

float safeDivision(float numerator, float denominator, float fallback) {
    if (abs(denominator) < 1e-5) { // 使用一个极小的阈值判断是否接近零
        return fallback;
    }
    return numerator / denominator;
}

void main() {
    uint id = gl_GlobalInvocationID.x;
    uint len = uint(particlesIn.length());
    if (id >= len) return;
    Particle p = particlesIn[id];

    vec2 safeRes = u_resolution;
    if (safeRes.x < 1.0) safeRes.x = 1080.0;
    if (safeRes.y < 1.0) safeRes.y = 2400.0;
    
    vec2 ratio = vec2(1.0, safeDivision(safeRes.y, safeRes.x, 2400.0/1080.0));
    vec2 safeRatio = ratio;
    safeRatio.x = (abs(ratio.x) < 1e-5) ? 1.0 : ratio.x;
    safeRatio.y = (abs(ratio.y) < 1e-5) ? 1.0 : ratio.y;
    
    float adaptionRatio = 1.0;
    if (ratio.y < 1.3) {
        adaptionRatio *= 0.6;
    }

    uint trailIndex = id % uint(trailCount);
    uint trailHeadId = id - trailIndex;

    if (p.lifeTime.x >= 99999.0 || (p.lifeTime.x <= 0.0 && emitterInfo.w == 1.0)) {
        vec2 realResolution = u_resolution;
        realResolution.x = mix(1080.0, u_resolution.x, step(10.0, u_resolution.x));
        realResolution.y = mix(2400.0, u_resolution.y, step(10.0, u_resolution.y));
        float resolutionRatio = min(min(float(realResolution.x) / 1080.0, 1.0), 
            min(float(realResolution.y) / 2400.0, 1.0));
        float realActive = resolutionRatio * float(u_Active);

        // 生命周期消失了重置粒子
        // 粒子大小随机
        float sizeRand = rand(vec2(fract(u_Time * 0.618), float(id)));
        float curSize = 40.0;
        curSize = mix(sizeAndNoise.y, 
            sizeAndNoise.x + (sizeRand * (sizeAndNoise.y - sizeAndNoise.x)), 
            sizeAndNoise.w);
        curSize *= resolutionRatio;

        float positionRand1 = rand(vec2(float(id)*0.03, fract(u_Time * 0.03)));
        float positionRand2 = rand(vec2(fract(u_Time * 0.0123), float(id) * 0.0618));
        vec2 initialPosiiton = -emitterSize + vec2(positionRand1, positionRand2) * 2.0 * emitterSize;
        p.position = vec4(initialPosiiton+emitterPosition, 0.0, curSize);
        p.originPos = p.position;

        // 生命周期随机
        float lifeRand = rand(vec2(float(id), fract(u_Time * 0.618)));
        float curLifeTime = 0.0;
        curLifeTime = mix(lifeTime.y,
            lifeTime.x + (lifeRand * max((lifeTime.y - lifeTime.x), 0.0)),      // 生命周期随机
            lifeTime.w
        );
        p.lifeTime = vec4(curLifeTime, curLifeTime, 0.0, 0.0);

        // 速度随机
        float speedRand = rand(vec2(float(id) * 0.327, float(id) * 0.666));
        p.speed.xyz = vec3(0.0);

        // 透明度随机
        float alphaRand = rand(vec2(speedRand, float(id) * 0.557));
        float curAlpha = mix(alphaRange.y,
            alphaRange.x + alphaRand * (alphaRange.y - alphaRange.x),
            alphaRange.w
        );
        // 颜色随机
        float weightRand = rand(vec2(float(id) * 0.322, fract(u_Time*0.618)));
        p.color = mix(vec4(maxColor.xyz, 1.0),
            vec4(mix(minColor.xyz, maxColor.xyz, weightRand), curAlpha),      // 颜色随机
            minColor.w
        );

        p.originPos.w = mix(trailLen.y,
            trailLen.x + (weightRand * max((trailLen.y - trailLen.x), 0.0)),      // 生命周期随机
            trailLen.w
        );

        float depthFactor = rand(vec2(float(id) * 0.789, float(id) * 0.123));
        if (depthFactor < depthRatio.x) {
            // 近景粒子：大尺寸、高速度
            p.speed.xyz *= closeSpeed;
            p.position.w *= closeSize;
            p.color.a *= closeAlpha;
        } else if (depthFactor <depthRatio.y) {
            // 中景粒子：中等参数
            p.speed.xyz *= midSpeed;
            p.position.w *= midSize;
            p.color.a *= midAlpha;
        } else {
            // 远景粒子：小尺寸、低速度
            p.speed.xyz *= farSpeed;
            p.position.w *= farSize;
            p.color.a *= farAlpha;
        }
        p.color.a = clamp(p.color.a, alphaRange.x, 1.0);
        p.originPos.y = p.color.a;      // 记录原始的透明度
        if (trailIndex != uint(0)) {
            // 拖尾粒子的初始化
            p = particlesOut[trailHeadId];
            p.acceleration.x = float(trailIndex);
            // 延迟时间
            p.acceleration.y = (1.0 - step(0.8, orbitSpeed)) * 0.05 * float(trailIndex);
            p.color.a = 0.0;
            // 拖尾粒子生命周期控制
            p.lifeTime.x = step(0.002, orbitSpeed) * particlesOut[trailHeadId].lifeTime.x;
        } else {
            // 头部粒子的处理
            vec2 headOffset = (p.position.xy - uCenter) * safeRatio;
            float headAngle = getAngle(headOffset);
            float headRadius = length(headOffset);
            p.acceleration.z = headAngle;
            p.acceleration.w = headRadius;
            headRadius = clamp(headRadius, 0.005, 5.0);
            if (headRadius <= 0.01) {
                p.lifeTime = vec4(0.0);
                particlesOut[id] = p;
                return;
            }

            float orbitSpeedFactor = 1.0;
            if (headRadius > 0.8) {
                orbitSpeedFactor = clamp(pow(0.8/headRadius, exponent), 0.1, 1.0);
            }
            if (safeRatio.y < 1.3 && headRadius > 1.5) {
                adaptionRatio *= 0.5;
            }
            if (headRadius > 2.0) {
                orbitSpeedFactor *= 0.8;
            }
            if (p.position.w < sizeAndNoise.x) {
                orbitSpeedFactor *= 0.8;
            }
            p.originPos.x = orbitSpeedFactor;
        }
    } else {
        // 星星中使用 p.acclearation作为traiData  x: 拖尾索引, y: 组内延迟, z: 旋转角度, w: 旋转半径
        Particle headParticle = particlesIn[trailHeadId];
        if (trailIndex == uint(0)) {
            p.acceleration.z += clamp(orbitSpeed * orbitSpeedPrompt, starMinSpeed, 1.0) * dt;
            p.position.xy = uCenter + p.acceleration.w * vec2(cos(p.acceleration.z), sin(p.acceleration.z)) / safeRatio;
        } else {
            // 计算延迟因子 (0-1)
            float delayFactor = float(trailIndex) / float(trailCount);
            float realTrailLength = p.originPos.w * orbitSpeed * adaptionRatio;
            
            // 如果头部粒子还有生命周期且当前拖尾粒子应该激活
            if (headParticle.lifeTime.x > 0.0 && headParticle.lifeTime.y - headParticle.lifeTime.x > p.acceleration.y) {
                // 更新拖尾粒子位置 - 稍微滞后于头部
                float angleOffset = -realTrailLength * delayFactor * p.originPos.x; // 拖尾粒子角度偏移
                // float angleOffset = -0.1 * delayFactor * orbitSpeed;
                float singleFrameAngle = clamp(orbitSpeed, starMinSpeed, 1.0) * dt;
                p.acceleration.z = headParticle.acceleration.z + (singleFrameAngle + angleOffset);
                p.position.xy = uCenter + p.acceleration.w * vec2(cos(p.acceleration.z), sin(p.acceleration.z)) / safeRatio;
                // 设置颜色透明度基于拖尾位置
                p.color.a = 0.8 * (1.0 - delayFactor);
            } else if (p.lifeTime.x > 0.0) {
                // 头部已停止，拖尾粒子依次停止
                float stopDelay = realTrailLength * float(trailCount - float(trailIndex));
                if (headParticle.lifeTime.y - headParticle.lifeTime.x > stopDelay) {
                    // 该拖尾粒子应该停止
                    p.lifeTime.x = max(0.0, p.lifeTime.x - dt * trailOutSpeed); // 更快地消失
                    p.color.a = max(0.0, p.color.a - dt * trailOutSpeed); // 淡出
                }
            }
            p.color.a = p.color.a * orbitSpeed;
        }
        p.lifeTime.x -= dt;
        p.speed.w = orbitSpeed;
    }
    if (!isVectorValid(p.position.xyz)) {
        p.lifeTime = vec4(0.0);
    }
    particlesOut[id] = p;
}