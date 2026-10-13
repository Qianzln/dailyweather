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

// @Label(风力)

uniform float dt;

// @Range(windDirec, 0, 360.0, 0.1)=0.0
uniform float windDirec;

// @Range(windForce, 0.0, 10.0, 0.1)=1.0
uniform float windForce;

void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= uint(particlesIn.length())) return;
    Particle p = particlesIn[id];

    float mathAngle = radians(windDirec);
    vec2 windVector = vec2(-sin(mathAngle), -cos(mathAngle));
    p.speed.xyz += vec3(normalize(windVector) * windForce, 0.0) * dt;

    particlesOut[id] = p;  // 写入输出缓冲
}