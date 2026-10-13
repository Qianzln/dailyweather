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

// @Vec3(direcMin,0.1)=[0,0,0]
uniform vec3 direcMin;

// @Vec3(direcMax,0.1)=[0,0,0]
uniform vec3 direcMax;

// @Vec4b(speed, speedRandom, 0.1)=[0.2, 0.5, 1.0, 1.0]
uniform vec4 speed;

// @Vec4b(lifeTime, lifeRandom, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 lifeTime;

// @Vec4b(sizeAndNoise, sizeRandom, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 sizeAndNoise;

// @Vec4(rotationAxis, 0.1)=[1.0, 1.0, 1.0, 0.5]
uniform vec4 rotationAxis;

// @Vec4b(emitterInfo, isLoop, 0.1)=[1.0, 1.0, 1.0, 1.0]
uniform vec4 emitterInfo;

// @Range(speedScale, 0.0, 10.0, 0.01)=1.0
uniform float speedScale;

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

float rand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

float weighted_rand(vec2 co, float weight) {
    float raw = rand(co);
    weight = clamp(weight, 0.0, 1.0);
    
    // 构建偏向性曲线
    float bias = smoothstep(0.0, 1.0, weight * 2.0 - 0.5);
    return mix(raw, 1.0 - raw, bias);
}

float weightedRandom(vec2 uv, float weight) {
    if (weight <= 0.0) return 0.0;
    if (weight >= 1.0) return 1.0;
    float r = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453);
    return pow(r, 1.0 - weight);
}

vec2 degree2direc(float degree) {
    float rad = radians(degree);
    return normalize(vec2(-sin(rad), -cos(rad)));
}

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= uint(particlesIn.length())) return;
    Particle p = particlesIn[id];

    if ((p.lifeTime.x <= 0.0 && emitterInfo.w == 1.0) || p.lifeTime.x >= 99999.0) {
        // 粒子激活才会重置
        vec2 realResolution = u_resolution;
        realResolution.x = mix(1080.0, u_resolution.x, step(10.0, u_resolution.x));
        realResolution.y = mix(2400.0, u_resolution.y, step(10.0, u_resolution.y));
        float resolutionRatio = min(min(float(realResolution.x) / 1080.0, 1.0), 
            min(float(realResolution.y) / 2400.0, 1.0));
        float realActive = resolutionRatio * float(u_Active);
        if (id >= uint(realActive)) {
            p.lifeTime = vec4(0.0);
            return;
        }
        // 生命周期消失了重置粒子
        // 1. 粒子大小随机
        float sizeRand = rand(vec2(fract(u_Time * 0.618), float(id)));
        float curSize = 40.0;
        curSize = mix(sizeAndNoise.y, 
            sizeAndNoise.x + (sizeRand * (sizeAndNoise.y - sizeAndNoise.x)), 
            sizeAndNoise.w);
        curSize *= resolutionRatio;

        // 2. 发射位置控制
        float positionRand1 = rand(vec2(float(id)*10.0, fract(u_Time * float(id))));
        float positionRand2 = rand(vec2(fract(u_Time * float(id)), float(id) * 0.618));
        vec2 initialPosiiton = -emitterSize + vec2(positionRand1, positionRand2) * 2.0 * emitterSize;
        p.position = vec4(initialPosiiton+emitterPosition, 0.0, curSize);
        p.originPos = p.position;

        // 3. 生命周期控制
        float lifeRand = rand(vec2(float(id), fract(u_Time * 0.618)));
        float curLifeTime = 0.0;
        curLifeTime = mix(lifeTime.y,
            lifeTime.x + (lifeRand * max((lifeTime.y - lifeTime.x), 0.0)),      // 生命周期随机
            lifeTime.w
        );
        p.lifeTime = vec4(curLifeTime, curLifeTime, 0.0, 0.0);

        // 4. 随机角度
        float dirRand = rand(vec2(lifeRand, float(id) * 0.333));
        vec3 initialDirec = mix(direcMin, direcMax, dirRand);
        p.speed = vec4(normalize(initialDirec), 0.0);
            
        // 5.速度随机
        float speedRand = rand(vec2(float(id) * 0.327, float(id) * 0.666));
        p.speed.xyz *= mix(speed.x, speed.y, speedRand);

        // 6.透明度随机
        float alphaRand = rand(vec2(speedRand, float(id) * 0.557));
        float curAlpha = mix(alphaRange.y,
            mix(alphaRange.x, alphaRange.y, alphaRand),
            alphaRange.w
        );
        
        float weightRand = weightedRandom(vec2(float(id) * 0.322, fract(u_Time*0.618)), colorWeight);
        p.color = mix(vec4(maxColor.xyz, 1.0),
            vec4(mix(minColor.xyz, maxColor.xyz, weightRand), curAlpha),      // 颜色随机
            minColor.w
        );
        p.acceleration.x = dirRand;

        float depthFactor = rand(vec2(float(id) * 0.789, float(id) * 0.123));
        if (depthFactor < depthRatio.x) {
            // 近景粒子：大尺寸、高速度
            p.speed.xyz *= closeSpeed;
            p.position.w *= closeSize;
            p.color.a *= closeAlpha;
            p.acceleration.z = closeDisturb;
        } else if (depthFactor <depthRatio.y) {
            // 中景粒子：中等参数
            p.speed.xyz *= midSpeed;
            p.position.w *= midSize;
            p.color.a *= midAlpha;
            p.acceleration.z = midDisturb;
        } else {
            // 远景粒子：小尺寸、低速度
            p.speed.xyz *= farSpeed;
            p.position.w *= farSize;
            p.color.a *= farAlpha;
            p.acceleration.z = farDisturb;
        }

    } else {
        p.position.xyz += p.speed.xyz * dt * speedScale;
        p.lifeTime.x -= dt * speedScale;
    }
    
    particlesOut[id] = p;
}