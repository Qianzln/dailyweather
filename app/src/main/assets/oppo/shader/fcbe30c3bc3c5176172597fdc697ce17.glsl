#version 320 es
precision highp float;

in VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
    vec4 speed;
    vec4 acceleration;
}vs_in;

out vec4 FragColor;

// @Texture(rain)=[1,1,0,0]
uniform sampler2D rain;

// @Vec2(fadeinTime, 0.1) = [0.8, 1.0]
uniform vec2 fadeinTime;

// @Vec2(fadeoutTime, 0.1) = [0.0, 0.5]
uniform vec2 fadeoutTime;

// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;

// @Range(particleAlpha, 0, 1, 0.01)=1
uniform float particleAlpha;

vec2 rotateUV(float angle, vec2 uv) {
    mat2 rotation = mat2(
        cos(angle), -sin(angle), 
        sin(angle), cos(angle));
    return rotation * (uv - 0.5) + 0.5;
}


void main() {
    float aspect = u_resolution.x/u_resolution.y;
    float angleOffset = mix(0.15, 0.45, step(0.7, aspect));
    float angle = atan(vs_in.speed.y, vs_in.speed.x) + angleOffset; // 计算速度方向的角度（弧度）
    vec2 rainUV = rotateUV(angle, gl_PointCoord);

    FragColor = vs_in.outColor;
    vec4 rainAlpha = texture(rain, rainUV);
    FragColor.a *= rainAlpha.b;

    FragColor.a *= rainAlpha.a;
    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));
    FragColor.a *= particleAlpha;
}
