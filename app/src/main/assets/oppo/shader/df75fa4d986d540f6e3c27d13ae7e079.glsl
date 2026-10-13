#version 320 es
precision highp float;

in VS_OUT
{
    vec4 position;
    float pointSize;
    vec4 outColor;
    vec4 lifeTime;
    vec4 speed;
    float randseed;
}vs_in;

out vec4 FragColor;

// @Texture(rain)=[1,1,0,0]
uniform sampler2D rain;

// @Vec2(fadeinTime, 0.1) = [0.8, 1.0]
uniform vec2 fadeinTime;

// @Vec2(fadeoutTime, 0.1) = [0.0, 0.5]
uniform vec2 fadeoutTime;

// @Range(anglePrompt, 0, 3.14, 0.01)=0.0
uniform float anglePrompt;

// @Vec2(u_resolution, 0.1)=[100,100]
uniform vec2 u_resolution;

float calcPromt() {
    float ratio = u_resolution.x / u_resolution.y;
    if (ratio < 0.7) {
        return 0.15;
    }
    return 0.15 - ((ratio - 0.7)/0.7) * 0.15;
}

vec2 rotateUV(float angle, vec2 uv) {
    mat2 rotation = mat2(
        cos(angle), -sin(angle), 
        sin(angle), cos(angle));
    return rotation * (uv - 0.5) + 0.5;
}


void main() {
    vec2 normalSpeed = normalize(vs_in.speed.xy);
    float angle = atan(normalSpeed.x,normalSpeed.y) + calcPromt(); // 计算速度方向的角度（弧度）
    vec2 rainUV = rotateUV(angle, gl_PointCoord);

    FragColor = vs_in.outColor;
    vec4 rainAlpha = texture(rain, rainUV);
    FragColor.a *= rainAlpha.b;
    FragColor.a *= rainAlpha.a;
    FragColor.a *= smoothstep(fadeoutTime.x, fadeoutTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y);               // 淡出
    FragColor.a *= (1.0 - smoothstep(fadeinTime.x, fadeinTime.y, vs_in.lifeTime.x / vs_in.lifeTime.y));
    if (FragColor.r < 0.01 && FragColor.g < 0.01 && FragColor.b < 0.01) {
        discard;
    }
}
