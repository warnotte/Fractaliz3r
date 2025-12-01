#version 430 core

/**
 * Fullscreen quad vertex shader
 * Passes through position and UV coordinates
 */

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;

out vec2 fragCoord;  // [-1, 1] normalized device coordinates
out vec2 uv;         // [0, 1] texture coordinates

void main() {
    fragCoord = aPos;
    uv = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
