#version 320 es
precision highp float;

in VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
    float oSpeed;
}vs_in;

out vec4 FragColor;

uniform float texAlpha;
uniform sampler2D lastTex;

// @Texture(starTex)=[1,1,0,0]
uniform sampler2D starTex;

// @Vec2(fadeinTime, 0.1) = [0.8, 1.0]
uniform vec2 fadeinTime;

// @Vec2(fadeoutTime, 0.1) = [0.0, 0.5]
uniform vec2 fadeoutTime;

// @Range(interanlSize, 0.0, 0.5, 0.1)=0.1
uniform float interanlSize;

// @Range(glowAlpha, 0.0, 1.0, 0.1)=0.2
uniform float glowAlpha;

// @Range(glowRadius, 0.1, 0.5, 0.1)=0.4
uniform float glowRadius;

vec2 rotateUV(float angle, vec2 uv) {
    mat2 rotation = mat2(
        cos(angle), -sin(angle), 
        sin(angle), cos(angle));
    return rotation * (uv - 0.5) + 0.5;
}

void main() {
    FragColor = vs_in.outColor;

    float radius = glowRadius;
    float dist = distance(gl_PointCoord, vec2(0.5));
    float smoothWidth = 0.05; // 平滑过渡的宽度，可根据效果调整
    float fadeFactor1 = smoothstep(interanlSize - smoothWidth, interanlSize, dist);
    float factor1 = mix(1.0, 0.5, fadeFactor1) * (1.0 - step(interanlSize, dist));

    float fadeFactor2 = smoothstep(interanlSize, radius, dist);
    float factor2 = mix(glowAlpha, 0.0, fadeFactor2);
    float os = vs_in.oSpeed;
    float glowFade = smoothstep(0.1, 0.0, os);
    FragColor.a *= (factor1 + factor2 * glowFade);

    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));
    FragColor.a *= smoothstep(-0.2, .0, vs_in.position.y);;
    if (FragColor.r < 0.01 && FragColor.g < 0.01 && FragColor.b < 0.01) {
        discard;
    }
}
