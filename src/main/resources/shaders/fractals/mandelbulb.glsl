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
uniform float radiolaria;
uniform float radiolariaFactor;
uniform vec3 juliaC;   // (0,0,0) = Mandelbrot mode; otherwise the Julia constant

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;      // Point trap at origin
    float planeX;       // Distance to YZ plane (x=0)
    float planeY;       // Distance to XZ plane (y=0)
    float planeZ;       // Distance to XY plane (z=0)
    int iterations;
};

// ============================================================================
// Mandelbulb Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    // Mandelbrot mode adds the sample position each iteration, which leaves large
    // analytic bulbs whose surface is locally smooth: dive into one and the detail
    // simply is not there. A fixed Julia constant makes every point of the set a
    // boundary point, so structure survives arbitrarily deep zooms.
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
        z += c;

        // Radiolaria mutation (Tom Beddard): Y-axis clamping for skeletal/hollow structures
        if (radiolaria > 0.0) {
            if (z.y > radiolariaFactor) {
                z.y = radiolariaFactor;
            }
        }

        // Track orbit traps for coloring (IQ style)
        // Point trap: distance to origin
        trap.minDist = min(trap.minDist, length(z));
        // Plane traps: distance to coordinate planes
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
        trap.iterations = i + 1;
    }

    float de = 0.5 * log(r) * r / dr;
    // Bounding sphere clamp: the fractal is contained within r < bailout.
    // Only activate far from the fractal (r > 2*bailout) to avoid creating
    // a false surface at the bailout boundary.
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
        z += c;

        // Radiolaria mutation
        if (radiolaria > 0.0) {
            if (z.y > radiolariaFactor) {
                z.y = radiolariaFactor;
            }
        }
    }

    float de = 0.5 * log(r) * r / dr;
    float rPos = length(pos);
    if (rPos > 2.0 * bailout) de = min(de, rPos - bailout);
    return de;
}

// ============================================================================
// Material Color from Orbit Traps (Inigo Quilez style)
// ============================================================================
// Uses 4 orbit traps like IQ's Mandelbulb:
// - Point trap at origin: multiplicative factor (pseudo-AO)
// - 3 plane traps (x=0, y=0, z=0): each mixes a different color
//
// Color sampling is done at a fixed offset from surface (in raytracer.glsl)
// so we can use simple exponential falloff without quality-dependent issues.
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // Exponential falloff for plane traps (IQ style)
    float trapX = exp(-trap.planeX * 3.0);
    float trapY = exp(-trap.planeY * 3.0);
    float trapZ = exp(-trap.planeZ * 3.0);

    // X: Proximity / Structure (Point trap)
    float structural = 1.0 - exp(-trap.minDist * 0.8);

    // Y: Flow / Accumulation - creating a more complex mix
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;

    // Z: Detail / Depth (Iterations)
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, iterNorm);
}
