#version 430 core

/**
 * Fullscreen quad vertex shader
 * Simply passes through positions and UVs
 */

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;

out vec2 fragCoord;
out vec2 uv;

void main() {
    fragCoord = aPos;
    uv = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
