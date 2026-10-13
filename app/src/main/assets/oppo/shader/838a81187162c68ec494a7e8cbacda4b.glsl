#version 300 es
precision highp float;

// @Texture(cloud)=[1,1,0,0]
uniform sampler2D u_cloud;

// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;

// @Range(Alpha,0,1,0.01)=1
uniform float _Alpha;
// @Color(EnvironmentColor, 0.01)=[1,1,1,1]
uniform vec4 _EnvironmentColor;
// @Vec2(Aspect0,0.001)=[1,1]
uniform vec2 _Aspect0;
// @Vec2(_Speed0,0.001)=[0,0]
uniform vec2 _Speed0;
// @Vec2(Aspect1,0.001)=[1,1]
uniform vec2 _Aspect1;
// @Vec2(_Speed1,0.001)=[0,0]
uniform vec2 _Speed1;
// @Vec2(Aspect2,0.001)=[1,1]
uniform vec2 _Aspect2;
// @Vec2(_Speed2,0.001)=[0,0]
uniform vec2 _Speed2;


// @Range(MaskTopPosition,0,1,0.01)=0.0
uniform float _MaskTopPosition;
// @Range(MaskBottomPosition,0,1,0.01)=0.0
uniform float _MaskBottomPosition;

// @Range(EggProgress,0,1,0.01)=0
uniform float eggProgress;

// @Range(SpeedScale,0,10,0.01)=1
uniform float speedScale;

in vec2 v_texCoord;
out vec4 fragColor;

float getMaskColor() {
    float maskColor = smoothstep(_MaskTopPosition, _MaskBottomPosition, v_texCoord.y);
    return maskColor;
}

float getAlpha() {
    vec2 st = v_texCoord;

    vec2 st0 = st;
    st0 *= _Aspect0;
    // st0.x -= u_time * 0.3;
    st0 -= u_time * _Speed0;
    float alpha0 = texture(u_cloud, st0).r;

    vec2 st1 = st;
    st1 *= _Aspect1;
    // st1 *= 0.8;
    // st1.x -= u_time * 0.36;
    st1 -= u_time * _Speed1;
    float alpha1 = texture(u_cloud, st1).g;

    vec2 st2 = st;
    st2 *= _Aspect2;
    // st2 *= 0.6;
    // st2.x -= u_time * 0.52;
    st2 -= u_time * _Speed2;
    float alpha2 = texture(u_cloud, st2).b;

    float alpha = alpha1 + (1.0 - alpha1) * alpha0;
    alpha = alpha2 + (1.0 - alpha2) * alpha;
    // alpha *= smoothstep(0.0, 0.5, v_texCoord.y);
    alpha *= getMaskColor();

    return alpha;
}


float getAlphaEgg() {
    vec2 st = v_texCoord;

    vec2 st0 = st;
    st0 *= _Aspect0;
    // st0.x -= u_time * 0.3;
    st0 -= u_time * _Speed0 * speedScale;
    float alpha0 = texture(u_cloud, st0).r;

    vec2 st1 = st;
    st1 *= _Aspect1;
    // st1 *= 0.8;
    // st1.x -= u_time * 0.36;
    st1 -= u_time * _Speed1 * speedScale;
    float alpha1 = texture(u_cloud, st1).g;

    vec2 st2 = st;
    st2 *= _Aspect2;
    // st2 *= 0.6;
    // st2.x -= u_time * 0.52;
    st2 -= u_time * _Speed2 * speedScale;
    float alpha2 = texture(u_cloud, st2).b;

    float alpha = alpha1 + (1.0 - alpha1) * alpha0;
    alpha = alpha2 + (1.0 - alpha2) * alpha;
    // alpha *= smoothstep(0.0, 0.5, v_texCoord.y);
    alpha *= getMaskColor();

    return alpha;
}

void main() {
    float targetAlpha = 1.0;
    if(eggProgress <= 0.0) {
        targetAlpha = getAlpha();
    } else if(eggProgress >= 1.0) {
        targetAlpha = getAlphaEgg();
    } else {
        targetAlpha = mix(getAlpha(), getAlphaEgg(), eggProgress);
    }
    fragColor = vec4(_EnvironmentColor.rgb, targetAlpha);
    fragColor.a *= _Alpha;
}


