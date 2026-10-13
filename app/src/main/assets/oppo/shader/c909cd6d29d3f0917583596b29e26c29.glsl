#version 300 es
precision highp float;

// @Color(_Color0,0.01)=[0.686, 0.647, 0.941, 1.0]
uniform vec4 _Color0;
// @Color(_Color1,0.01)=[0.686, 0.647, 0.941, 1.0]
uniform vec4 _Color1;
// @Color(_Color2,0.01)=[0.686, 0.647, 0.941, 1.0]
uniform vec4 _Color2;
// @Color(_Color3,0.01)=[0.686, 0.647, 0.941, 1.0]
uniform vec4 _Color3;
// @Color(_Color4,0.01)=[0.686, 0.647, 0.941, 1.0]
uniform vec4 _Color4;

// @Range(RangeStart,0.0,1,0.001)=1
uniform float _RangeStart;  // 渐变起始位置（默认0）
// @Range(RangeEnd,0.0,1,0.001)=0
uniform float _RangeEnd;    // 渐变结束位置（默认1）
// @Range(Segment1,0.0,1,0.001)=0.25
uniform float _Segment1;   // 第一段结束位置
// @Range(Segment2,0.0,1,0.001)=0.5
uniform float _Segment2;    // 第二段结束位置
// @Range(Segment3,0.0,1,0.001)=0.75
uniform float _Segment3;   // 第三段结束位置

// @Texture(NoiseTex)=[1,1,0,0]
uniform sampler2D u_NoiseTex;
// @Range(NoiseScale,0.0,10,0.001)=4
uniform float u_NoiseScale;
// @Range(NoiseStrength,0,1,0.01)=0.01
uniform float u_NoiseStrength;

// @Range(Alpha,0.0,1,0.001)=1
uniform float u_Alpha;

in vec2 v_texCoord;
out vec4 fragColor;

void main() {
    // 将纹理坐标y值映射到自定义范围[1](@ref)
    float x = clamp((v_texCoord.y - _RangeStart) / (_RangeEnd - _RangeStart), 0.0, 1.0);
    
    vec4 color = mix(_Color3, _Color4, clamp((x-_Segment3)/(1.0-_Segment3),0.0,1.0));
    if(x < _Segment3) color = mix(_Color2, _Color3, (x-_Segment2)/(_Segment3-_Segment2));
    if(x < _Segment2) color = mix(_Color1, _Color2, (x-_Segment1)/(_Segment2-_Segment1));
    if(x < _Segment1) color = mix(_Color0, _Color1, x/_Segment1);
    
    // 噪声处理
    mediump float noise = texture(u_NoiseTex, v_texCoord * u_NoiseScale).r;
    color.rgb += (noise - 0.5) * u_NoiseStrength;

    fragColor = color;
    fragColor.a *= u_Alpha;
}

