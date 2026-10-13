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

float rand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

uniform float dt;

uniform float u_Time;

// @Range(turbulenceStrengthX, 0.0, 5.0, 0.01)=0.2
uniform float turbulenceStrengthX;               // 扰动强度参数

// @Range(turbulenceStrengthY, 0.0, 5.0, 0.01)=0.2
uniform float turbulenceStrengthY;               // 扰动强度参数


void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= uint(particlesIn.length())) return;
    Particle p = particlesIn[id];
    
    // 时间因子控制扰动频率
    vec2 noiseSeed = vec2(float(id), u_Time * 0.1); 
    // [-1,1]范围扰动
    float windNoiseX = rand(noiseSeed) * 2.0 - 1.0; 
    // 避免X/Z方向噪声重复
    float windNoiseY = rand(noiseSeed + 0.618) * 2.0 - 1.0; 
    // 水平面扰动
    vec3 windDirection = normalize(vec3(windNoiseX, windNoiseY, 0.0)); 
    vec3 perturbation = windDirection * vec3(turbulenceStrengthX, turbulenceStrengthY, 0.0) * p.acceleration.z *dt;
    p.speed.xyz += perturbation;
    
    particlesOut[id] = p;  // 写入输出缓冲
}