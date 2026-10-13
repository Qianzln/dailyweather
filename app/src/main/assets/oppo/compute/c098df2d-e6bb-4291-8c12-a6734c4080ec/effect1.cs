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

// @Label(扰动)

uniform float dt;

uniform float u_Time;

// @Vec3(windForce, 0.1)=[0.3, -0.1, 0.0]  // 风力向量（x:水平风力，y:上升力，z:保留）
uniform vec3 windForce;

// @Range(turbulenceIntensity, 0.0, 1.0, 0.01)=0.15  // 湍流强度
uniform float turbulenceIntensity;

// @Range(turbulenceFrequency, 0.1, 5.0, 0.1)=1.8    // 湍流频率
uniform float turbulenceFrequency;

vec3 calcTurbulence(uint id, float time) {
    vec2 seed = vec2(float(id) * 0.217, fract(time * 0.317));
    float noiseX = sin(time * turbulenceFrequency + seed.x * 10.0);
    float noiseY = cos(time * turbulenceFrequency * 1.3 + seed.y * 7.0);
    return vec3(noiseX, noiseY, 0) * turbulenceIntensity;
}

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= uint(particlesIn.length())) return;
    Particle p = particlesIn[id];
    
    vec3 turbulence = calcTurbulence(id, u_Time);
    vec3 totalForce = windForce + turbulence;
    p.speed.xyz += totalForce * dt * 0.5;
    
    p.position.xy += turbulence.xy * dt * 0.3;
    
    particlesOut[id] = p;  // 写入输出缓冲
}