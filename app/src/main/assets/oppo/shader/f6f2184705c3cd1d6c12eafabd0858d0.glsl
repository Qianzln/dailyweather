#version 300 es
in vec4 a_position;
in vec2 a_texCoord;

out vec2 v_texCoord;
out vec3 v_WorldPosition;
out vec3 v_normal;

uniform mat4 u_projection;
uniform mat4 u_view;
uniform mat4 u_model;

void main() {
    gl_Position = u_projection * u_view  * u_model *  a_position;
    v_texCoord = a_texCoord;
    v_WorldPosition = (u_model * a_position).xyz;
}

