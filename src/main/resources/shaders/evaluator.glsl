layout(location = 0) out vec4 FragColor;

in vec2 uv;

// common.glsl and fractal.glsl are prepended
uniform float zPos;
uniform float boundsHalf;
uniform int gridResolution;

void main() {
    // Use integer pixel coordinates for exact grid alignment with Marching Cubes.
    // Pixel i maps to grid position i/(gridResolution-1), matching the CPU formula:
    //   pos = -boundsHalf + i * (2*boundsHalf / resolution)
    ivec2 coord = ivec2(gl_FragCoord.xy);
    vec3 pos = vec3(
        (float(coord.x) / float(gridResolution - 1) * 2.0 - 1.0) * boundsHalf,
        (float(coord.y) / float(gridResolution - 1) * 2.0 - 1.0) * boundsHalf,
        zPos
    );

    OrbitTrap trap;
    float d = DE(pos, trap);

    // Sanitize distance: avoid NaN/Inf which cause "single point" artifacts in Mesh export
    if (isnan(d) || isinf(d)) d = 1e10;

    vec3 factors = getFactors(trap);
    vec3 color = clamp(applyMaterial(factors), 0.0, 1.0);

    // RGB = Vertex color (LDR clamped), A = Distance
    FragColor = vec4(color, d);
}
