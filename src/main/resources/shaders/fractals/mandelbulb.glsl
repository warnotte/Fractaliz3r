/**
 * Mandelbulb Distance Estimator
 *
 * Only fractal-specific code:
 * - OrbitTrap struct
 * - DE() with orbit traps
 * - DE_simple() for shadows/AO
 * - getColor() for material
 *
 * Generic raytracing handled by raytracer.glsl
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float power;
uniform int maxIterations;
uniform float bailout;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float avgDist;
    float lastDist;
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
    trap.lastDist = 0.0;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);

        if (r > bailout) break;

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
        trap.lastDist = dist;
        trap.iterations = i + 1;
    }

    trap.avgDist /= float(max(trap.iterations, 1));

    return 0.5 * log(r) * r / dr;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    for (int i = 0; i < maxIterations; i++) {
        r = length(z);
        if (r > bailout) break;

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
// Material Color from Orbit Traps
// ============================================================================

vec3 getColor(OrbitTrap trap) {
    float t1 = trap.minDist * 2.0;
    float t2 = trap.avgDist * 0.5;
    float t3 = trap.lastDist * 0.3;
    float t4 = float(trap.iterations) * 0.1;

    float combined = t1 * 0.3 + t2 * 0.3 + t3 * 0.2 + t4 * 0.2;
    combined += power * 0.05;  // Vary with power parameter

    return fractalPalette(combined * 0.5);
}
