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

// @Texture(snow)=[1,1,0,0]
uniform sampler2D snow;

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
    FragColor.rgba = vs_in.outColor;
    float angle = vs_in.randseed.x * 360.0;
    vec2 snowUV = rotateUV(angle, gl_PointCoord);
    vec4 snowAlpha = texture(snow, snowUV);

    // FragColor.a *= snowAlpha.b;
    FragColor.a *= mix(
        mix(snowAlpha.g, snowAlpha.b, step(0.3333, vs_in.randseed.y)), 
        snowAlpha.r,            // 圆形雪花
        step(0.6667, vs_in.randseed.y));

    FragColor.a *= snowAlpha.a;
    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));
    if (FragColor.r < 0.01 && FragColor.g < 0.01 && FragColor.b < 0.01) {
        discard;
    }
}
