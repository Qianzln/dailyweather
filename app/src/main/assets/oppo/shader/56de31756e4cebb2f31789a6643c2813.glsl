#version 300 es
precision highp float;
// @Texture(Light0)=[1,1,0,0]
uniform sampler2D u_texLight0;
// @Texture(Flash)=[1,1,0,0]
uniform sampler2D u_texFlash;
// @Vec2(u_lightResolution,0.1)=[100,100]
uniform vec2 u_lightResolution;
// @Vec2(u_flashResolution,0.1)=[100,100]
uniform vec2 u_flashResolution;
// @Range(lightStrength,0,1,0.01)=1
uniform float u_lightStrength;
// @Range(flashStrength,0,1,0.01)=1
uniform float u_flashStrength;
// @Range(flashIndex,0,2,1)=0	
uniform int u_flashIndex;
// @Range(alpha,0,1,0.01)=1
uniform float u_alpha;
// @Range(dissolveValue,0,1,0.01)=1
uniform float u_DissolveValue;
// @Vec2(u_resolution,0.1)=[100,100]
uniform vec2 u_resolution;
// @Range(time,0,100,0.01)=0.1
uniform float u_time;

in vec2 v_texCoord;
out vec4 fragColor;

float blendAdd(float base, float blend) {
    return min(base+blend, 1.0);
}
vec3 blendAdd(vec3 base, vec3 blend) {
    return min(base+blend, vec3(1.0));
}
vec3 blendAdd(vec3 base, vec3 blend, float opacity) {
    return (blendAdd(base, blend) * opacity + base * (1.0 - opacity));
}

void main() {
    // vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    vec2 uv = v_texCoord;
    float screenAspect = u_resolution.x / u_resolution.y;
    float lightAspect = u_lightResolution.x / u_lightResolution.y;
    float flashAspect = u_flashResolution.x / u_flashResolution.y;

    // flash
    float flashAlpha;

    // if ((1.0 - uv.y) * flashAspect / screenAspect > 1.0){
    //     discard;
    // }

    vec2 uvFlash = vec2(uv.x, 1.0 - (1.0 - uv.y) * flashAspect / screenAspect);
    if (u_flashIndex == 0){
        flashAlpha = texture(u_texFlash, uvFlash).a;
    } else if (u_flashIndex == 1){
        flashAlpha = texture(u_texFlash, uvFlash).r;
    } else {
        flashAlpha = texture(u_texFlash, uvFlash).b;
    }

    
    flashAlpha *= u_flashStrength;
    // light
    // 0~1, 1~1, 1~0
    vec2 uvLight = vec2(uv.x, 1.0 - (1.0 - uv.y) * lightAspect); // / screenAspect) ;
    vec4 lightColor = texture(u_texLight0, uvLight);
    if(lightColor.a - 0.01 <= u_DissolveValue) {
        lightColor.a = 0.0;
    }
    float lightStrength = smoothstep(0.0, 0.444445, u_lightStrength) - smoothstep(0.777778, 1.0, u_lightStrength);
    vec3 finalColor = blendAdd(lightColor.rgb * lightStrength * lightColor.a, vec3(flashAlpha), flashAlpha);

    fragColor = vec4(finalColor, u_alpha);
    // fragColor = lightColor;
}

