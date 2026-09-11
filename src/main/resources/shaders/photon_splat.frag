#version 430 core

in vec3 vColor;
out vec4 FragColor;

// Alpha 0: the accumulation's alpha is the depth sum, and a photon has no depth to add.
void main() { FragColor = vec4(vColor, 0.0); }
