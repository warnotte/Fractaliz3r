/**
 * Mandelbulb Distance Estimator
 *
 * This file ONLY defines the fractal-specific code:
 * - DE() function with orbit traps
 * - DE_simple() for shadows/AO
 * - getColor() for material coloring
 *
 * The raytracer.glsl handles all the generic rendering.
 * Uniforms are defined in common.glsl and accessible here.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float power;
uniform int maxIterations;

// ============================================================================
// Orbit trap data (for coloring)
// ============================================================================

struct OrbitTrap {
    float minDist;
    float avgDist;
    int iterations;
};

// ============================================================================
// Mandelbulb Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    trap.minDist = 1e10;
    trap.avgDist = 0.0;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);

        if (r > 2.0) break;

        // Convert to polar coordinates
        float theta = acos(z.z / r);
        float phi = atan(z.y, z.x);

        // Update derivative
        dr = pow(r, power - 1.0) * power * dr + 1.0;

        // Scale and rotate the point
        float zr = pow(r, power);
        theta = theta * power;
        phi = phi * power;

        // Convert back to cartesian
        z = zr * vec3(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        );
        z += pos;

        // Track orbit traps for coloring
        float dist = length(z);
        trap.minDist = min(trap.minDist, dist);
        trap.avgDist += dist;
        trap.iterations = i + 1;
    }

    trap.avgDist /= float(trap.iterations);

    return 0.5 * log(r) * r / dr;
}

// ============================================================================
// Simple DE (for shadows, AO, normals - no orbit traps needed)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);
        if (r > 2.0) break;

        float theta = acos(z.z / r);
        float phi = atan(z.y, z.x);
        dr = pow(r, power - 1.0) * power * dr + 1.0;

        float zr = pow(r, power);
        theta = theta * power;
        phi = phi * power;

        z = zr * vec3(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        );
        z += pos;
    }

    return 0.5 * log(r) * r / dr;
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

vec3 getColor(OrbitTrap trap) {
    float t = trap.minDist * 2.0 + trap.avgDist * 0.5 + float(trap.iterations) * 0.1;
    return fractalPalette(t * 0.5);
}
