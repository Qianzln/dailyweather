#version 300 es

in vec4 a_position;
in vec2 a_texCoord;

out vec2 v_texCoord;
out vec2 v_StarCoord;
out vec2 v_NoiseCoord;

uniform mat4 u_projection;
uniform mat4 u_view;
uniform mat4 u_model;

// @Range(iTime,0,100,0.01)=0.1
uniform float u_time;

// @Vec4(StarST,0.01)=[1,1,0,0]
uniform vec4 _StarTex_ST;

// @Vec2(StarRotationCenter,0.1)=[0,0]
uniform vec2 _StarRotationCenter;

// @Range(StarRotateSpeed, -10.0, 10.0, 0.01)=0.1
uniform float _StarRotateSpeed;

// @Vec4(NoiseTexST,0.01)=[1,1,0,0]
uniform vec4 _NoiseTex_ST;

// @Vec2(NoiseRotationCenter,0.1)=[0,0]
uniform vec2 _NoiseRotationCenter;

// @Range(NoiseRotateSpeed, -10.0, 10.0, 0.01)=0.1
uniform float _NoiseRotateSpeed;

// 旋转矩阵
vec2 Rotate2D(vec2 uv, vec2 centerPoint, float angle) {
    float rad = radians(angle);
    float s = sin(rad);
    float c = cos(rad);

    uv -= centerPoint;
    uv *= mat2(c, -s, s, c);
    uv += centerPoint;

    return uv;
}

void main() {
    gl_Position = u_projection * u_view  * u_model *  a_position;
    v_texCoord = a_texCoord;

    vec2 starUV = vec2(a_texCoord.x * _StarTex_ST.x + _StarTex_ST.z, 
                a_texCoord.y * _StarTex_ST.y + _StarTex_ST.w);
    v_StarCoord = Rotate2D(starUV, _StarRotationCenter, u_time * _StarRotateSpeed);
    
    vec2 noiseUV = vec2(a_texCoord.x * _StarTex_ST.x + _StarTex_ST.z, 
                a_texCoord.y * _StarTex_ST.y + _StarTex_ST.w);
    v_NoiseCoord = Rotate2D(noiseUV, _NoiseRotationCenter, -u_time * _NoiseRotateSpeed);
}

