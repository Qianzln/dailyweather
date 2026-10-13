#version 300 es
precision highp float;

// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;

// @Texture(NoiseTex)=[1,1,0,0]
uniform sampler2D u_NoiseTex;

// @Range(AlphaProgress, 0.0, 1.0, 0.01)=1.0
uniform float _AlphaProgress;

// @Range(Alpha, 0.0, 1.0, 0.01)=1.0
uniform float _Alpha;

// @Color(WindLayerColor, 0.01)=[1,1,1,1]
uniform vec4 _WindLayerColor;
// @Vec2(Aspect0,0.001)=[1,1]
uniform vec2 _Aspect0;
// @Vec2(WindSpeed0,0.001)=[0.0,0.0]
uniform vec2 _WindSpeed0;
// @Vec2(Aspect1,0.001)=[1,1]
uniform vec2 _Aspect1;
// @Vec2(WindSpeed1,0.001)=[0.0,0.0]
uniform vec2 _WindSpeed1;

// @Vec2(Center,0.01)=[0.5,0.5]
uniform vec2 u_Center;
// @Range(Range, -2.0, 2.0, 0.01)=0.0
uniform float u_Range;
// @Range(Angle, -1.0, 1.0, 0.01)=0.0
uniform float u_Angle;
// @Range(AngleSpeed, -10.0, 10.0, 0.001)=0.0
uniform float u_AngleSpeed;


// @Range(MaskTopPosition,0,1,0.01)=0.0
uniform float _MaskTopPosition;
// @Range(MaskBottomPosition,0,1,0.01)=0.0
uniform float _MaskBottomPosition;

// @Range(EggProgress,0,1,0.01)=0.0
uniform float eggProgress;
// @Range(SpeedScale,0,10,0.01)=1.0
uniform float speedScale;

in vec2 v_texCoord;
out vec4 fragColor;

float getMaskColor() {
    float maskColor = smoothstep(_MaskTopPosition, _MaskBottomPosition, v_texCoord.y);
    return maskColor;
}

mat2 rotate(float a) {
    float s = sin(a);
    float c = cos(a);
    return mat2(c,-s,s,c);
}

vec2 twirl(vec2 uv, vec2 center, float range, float angle) {
    float d = distance(uv, center);
    uv -= center;
    // d = clamp(-angle/range * d + angle,0.,angle); // 线性方程
    d = smoothstep(0., range, range - d) * angle;
    uv *= rotate(d);
    uv += center;
    return uv;
}

//预乘混合
vec4 blendPremultiplied(vec4 srcColor, vec4 dstColor) {
    return srcColor + dstColor * (1.0 - srcColor.a);
}

//正常混合模式
vec4 blendNormal(vec4 srcColor, vec4 dstColor) {
    return srcColor * srcColor.a + dstColor * (1.0 - srcColor.a);
}

vec4 getWindColor() {
    float maskColor = getMaskColor();
    vec2 uv = (v_texCoord + twirl(v_texCoord, u_Center, u_Range, sin(u_time * u_AngleSpeed) * u_Angle));
    vec4 noiseTexLayer1 = texture(u_NoiseTex, (uv + _WindSpeed0 * u_time) * _Aspect0);
    vec4 noiseTexLayer2 = texture(u_NoiseTex, (uv + _WindSpeed1 * u_time) * _Aspect1);


    vec4 color0 = vec4(vec3(1.), noiseTexLayer1.r);
    vec4 color1 = vec4(vec3(1.), noiseTexLayer2.g);

    return color0 + color1;
}

vec4 getWindColorEgg() {
    float maskColor = getMaskColor();
    vec2 uv = (v_texCoord + twirl(v_texCoord, u_Center, u_Range, sin(u_time * u_AngleSpeed) * u_Angle));
    vec4 noiseTexLayer1 = texture(u_NoiseTex, (uv + _WindSpeed0 * u_time * speedScale) * _Aspect0);
    vec4 noiseTexLayer2 = texture(u_NoiseTex, (uv + _WindSpeed1 * u_time * speedScale) * _Aspect1);


    vec4 color0 = vec4(vec3(1.), noiseTexLayer1.r);
    vec4 color1 = vec4(vec3(1.), noiseTexLayer2.g);

    return color0 + color1;
}

void main() {
    float maskColor = getMaskColor();
    vec2 uv = (v_texCoord + twirl(v_texCoord, u_Center, u_Range, sin(u_time * u_AngleSpeed) * u_Angle));
    vec4 noiseTexLayer1 = texture(u_NoiseTex, (uv + _WindSpeed0 * u_time * speedScale) * _Aspect0);
    vec4 noiseTexLayer2 = texture(u_NoiseTex, (uv + _WindSpeed1 * u_time * speedScale) * _Aspect1);


    vec4 color0 = vec4(vec3(1.), noiseTexLayer1.r);
    vec4 color1 = vec4(vec3(1.), noiseTexLayer2.g);

    vec4 finalColor = vec4(0.);
    if(eggProgress <= 0.0) {
        finalColor = getWindColor();
    } else if(eggProgress >= 1.0) {
        finalColor = getWindColorEgg();
    } else {
        finalColor = mix(getWindColor(), getWindColorEgg(), eggProgress);
    }
    finalColor *= _WindLayerColor;

    fragColor = finalColor;
    fragColor.a *= maskColor;
    fragColor.a *= _Alpha * _AlphaProgress;
}

