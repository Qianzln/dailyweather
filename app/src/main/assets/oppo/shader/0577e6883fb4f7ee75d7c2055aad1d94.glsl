#version 300 es
precision highp float;

// @Vec2(iResolution,0.1)=[1080,2400]
uniform vec2 u_resolution;

// @Texture(NoiseTex)=[1,1,0,0]
uniform sampler2D u_NoiseTex;

// @Range(Alpha, 0.0, 1.0, 0.01)=1
uniform float _Alpha;

// @Range(MaskTopPosition, 0.0, 1.0, 0.01)=0.1
uniform float _MaskTopPosition;

// @Range(MaskBottomPosition, 0.0, 1.0, 0.01)=0.1
uniform float _MaskBottomPosition;

in vec2 v_texCoord;
in vec2 v_StarCoord;
in vec2 v_NoiseCoord;
out vec4 fragColor;

void main() {
    float maskColor = smoothstep(_MaskTopPosition, _MaskBottomPosition, v_texCoord.y);
    vec4 finalColor = vec4(vec3(texture(u_NoiseTex, v_StarCoord).r), 1.0);
    finalColor.rgb *= texture(u_NoiseTex, v_NoiseCoord).g * maskColor;
    finalColor.a *= _Alpha;
    fragColor = finalColor;
}

