#version 320 es
precision highp float;

in VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
    vec2 randseed;
}vs_in;

out vec4 FragColor;

// @Vec2(fadeinTime, 0.1) = [0.8, 1.0]
uniform vec2 fadeinTime;

// @Vec2(fadeoutTime, 0.1) = [0.0, 0.5]
uniform vec2 fadeoutTime;

vec2 rotateUV(float angle, vec2 uv) {
    mat2 rotation = mat2(
        cos(angle), -sin(angle), 
        sin(angle), cos(angle));
    return rotation * (uv - 0.5) + 0.5;
}

void main() {
    FragColor = vs_in.outColor;

    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));

    // 计算当前片段在点范围内的坐标（归一化为[-1,1]）
    vec2 coord = gl_PointCoord * 2.0 - 1.0;
    // 计算到中心的距离
    float distance = length(coord);
    // 裁剪超出半径的像素
    if (distance > 1.0) {
        discard;
    }
    float alpha = smoothstep(1.0, 0.9, distance);
    FragColor.a *= alpha;
    if (FragColor.r < 0.01 && FragColor.g < 0.01 && FragColor.b < 0.01) {
        discard;
    }
}
