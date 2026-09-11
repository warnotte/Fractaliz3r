#version 430 core

/**
 * Splat pass: one point per photon of the pass. Mode 0 puts it on the pixel the photon
 * pass found for it, carrying the radiance it adds, into the accumulation. Mode 1 puts it
 * on the cell of the emission square it left from, when it met glass or metal, into the
 * cell map the next passes draw their emission from. Photons with nothing to say go
 * outside the clip volume. GL_POINTS, additive blending.
 */

uniform sampler2D photonPixelTex;   // xy: tile UV, zw: emission square UV
uniform sampler2D photonColorTex;   // rgb: radiance, a: flags (1 landed, 2 specular)
uniform int photonSide;
uniform int splatMode;

out vec3 vColor;

void main() {
    ivec2 t = ivec2(gl_VertexID % photonSide, gl_VertexID / photonSide);
    vec4 p = texelFetch(photonPixelTex, t, 0);
    vec4 c = texelFetch(photonColorTex, t, 0);
    int flags = int(c.a + 0.5);
    const vec4 away = vec4(-2.0, -2.0, 2.0, 1.0);
    if (splatMode == 0) {
        vColor = c.rgb;
        gl_Position = (flags & 1) != 0 ? vec4(p.xy * 2.0 - 1.0, 0.0, 1.0) : away;
    } else {
        vColor = vec3(1.0);
        gl_Position = (flags & 2) != 0 ? vec4(p.zw * 2.0 - 1.0, 0.0, 1.0) : away;
    }
}
