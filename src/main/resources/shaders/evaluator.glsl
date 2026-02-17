layout(location = 0) out vec4 FragColor;

in vec2 uv;

// common.glsl and fractal.glsl are prepended
uniform float zPos;
uniform float boundsHalf;

void main() {
    vec3 pos = vec3(
        (uv.x * 2.0 - 1.0) * boundsHalf,
        (uv.y * 2.0 - 1.0) * boundsHalf,
        zPos
    );

    OrbitTrap trap;
    float d = DE(pos, trap);
    
    // Sanitize distance: avoid NaN/Inf which cause "single point" artifacts in Mesh export
    if (isnan(d) || isinf(d)) d = 1e10;
    
    vec3 factors = getFactors(trap);
    
    // RGB = Factors, A = Distance
    FragColor = vec4(factors, d);
}
