#version 300 es
precision highp float;

// @Texture(MainTex)=[1,1,0,0]
uniform sampler2D u_texture;

in vec2 v_texCoord;
out vec4 fragColor;

// @Range(Alpha,0.0,1,0.001)=1
uniform float u_Alpha;

// @Color(Color,0.01)=[1, 1, 1, 1]
uniform vec4 u_Color;

void main() {
    vec4 finalColor = texture(u_texture, v_texCoord);
    finalColor.rgb *= u_Color.rgb;
    fragColor = finalColor * u_Alpha;
}

