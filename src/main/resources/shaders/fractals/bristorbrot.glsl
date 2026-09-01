/**
 * Bristorbrot Distance Estimator
 *
 * Component-wise 3D Mandelbrot (NOT spherical coords like Mandelbulb).
 * Produces elongated, asymmetric bulb shapes distinct from Mandelbulb.
 *
 * Supports Julia mode: when juliaC != (0,0,0), uses fixed constant
 * instead of orbit position, producing Julia set variants.
 *
 * Iteration:
 *   z_new.x = z.x*z.x - z.y*z.y - z.z*z.z
 *   z_new.y = 2*z.x*z.y
 *   z_new.z = -2*z.x*z.z
 *   z = z_new + c  (c = pos for Mandelbrot, c = juliaC for Julia)
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float bailout;
uniform vec3 juliaC;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float planeX;
    float planeY;
    float planeZ;
    int iterations;
};

// ============================================================================
// Bristorbrot DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    // Julia mode: start at pos, add juliaC each iteration
    // Mandelbrot mode (juliaC==0): start at pos, add pos each iteration
    bool isJulia = dot(juliaC, juliaC) > 0.0001;
    vec3 c = isJulia ? juliaC : pos;
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        r = length(z);
        if (r > bailout) break;

        dr = 2.0 * r * dr + 1.0;

        // Bristorbrot formula (component-wise squaring)
        vec3 zNew;
        zNew.x = z.x * z.x - z.y * z.y - z.z * z.z;
        zNew.y = 2.0 * z.x * z.y;
        zNew.z = -2.0 * z.x * z.z;
        z = zNew + c;

        // Track orbit traps
        trap.minDist = min(trap.minDist, length(z));
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
        trap.iterations = i + 1;
    }

    float de = 0.5 * log(r) * r / dr;
    float rPos = length(pos);
    if (rPos > 2.0 * bailout) de = min(de, rPos - bailout);
    return de;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    bool isJulia = dot(juliaC, juliaC) > 0.0001;
    vec3 c = isJulia ? juliaC : pos;
    vec3 z = pos;
    float dr = 1.0;
    float r = 0.0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        r = length(z);
        if (r > bailout) break;

        dr = 2.0 * r * dr + 1.0;

        vec3 zNew;
        zNew.x = z.x * z.x - z.y * z.y - z.z * z.z;
        zNew.y = 2.0 * z.x * z.y;
        zNew.z = -2.0 * z.x * z.z;
        z = zNew + c;
    }

    float de = 0.5 * log(r) * r / dr;
    float rPos = length(pos);
    if (rPos > 2.0 * bailout) de = min(de, rPos - bailout);
    return de;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float trapX = exp(-trap.planeX * 3.0);
    float trapY = exp(-trap.planeY * 3.0);
    float trapZ = exp(-trap.planeZ * 3.0);

    float structural = 1.0 - exp(-trap.minDist * 0.8);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, iterNorm);
}
