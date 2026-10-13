#version 300 es
precision highp float;

in vec2 v_texCoord;
out vec4 fragColor;

// @Range(_AnimValue,0.0,1,0.001)=1
uniform float _AnimValue;

void main() {
    fragColor = vec4(0.);
}

