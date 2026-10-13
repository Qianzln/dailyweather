#version 320 es
precision highp float;

layout (location = 0) in vec4 position;         // xyz=位置, w=大小
layout (location = 1) in vec4 acceleration;     // xyz: 加速度, w: 预留
layout (location = 2) in vec4 speed;            // xyz: 移动速度 w: 随机种子
layout (location = 3) in vec4 color;            // RGBA颜色
layout (location = 4) in vec4 lifeTime;         // x: 当前生命值, y: 整个生命周期, z: 一直存在, w: color alpha random

out VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
    vec4 speed;
    vec4 acceleration;
}vs_out;

void main() {
    gl_Position = vec4(vec3(position.xyz), 1.0);
    gl_PointSize = position.w;

    vs_out.position = gl_Position;
    vs_out.pointSize = gl_PointSize;
    vs_out.outColor = color;
    vs_out.lifeTime = lifeTime;
    vs_out.speed = speed;
    vs_out.acceleration = acceleration;
}