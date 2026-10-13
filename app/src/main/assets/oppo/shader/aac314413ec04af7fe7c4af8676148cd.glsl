#version 300 es
precision highp float;

in vec2 v_texCoord;
out vec4 fragColor;

// @Range(AnimValue,0.0,1,0.001)=1
uniform float _AnimValue;

// @Range(AnimScaleValue,0.0,1,0.001)=1
uniform float _AnimScaleValue;

// @Range(AnimMoveValue,0.0,1,0.001)=1
uniform float _AnimMoveValue;

void main() {
    fragColor = vec4(0.);
}

