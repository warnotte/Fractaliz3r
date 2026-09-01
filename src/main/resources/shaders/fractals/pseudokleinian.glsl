/**
 * Pseudo-Kleinian Distance Estimator
 *
 * Based on Knighty's formula (Fragmentarium).
 * Box fold + sphere fold with Z-plane distance estimation.
 * Space repetition via mod() creates infinite cave structures.
 *
 * Reference: Shadertoy wtGGDR, Syntopia/Fragmentarium PseudoKleinian.frag
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform vec3 CSize;
uniform float Size;
uniform float DEoffset;
uniform vec3 foldC;

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
// Pseudo-Kleinian DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 p = pos;

    // Space repetition for infinite cave structures (tile XY, not Z)
    p.x = mod(p.x + 3.0, 6.0) - 3.0;
    p.y = mod(p.y + 2.0, 4.0) - 2.0;

    float DEfactor = 1.5;
    vec3 ap = p + 1.0;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        // Fixed-point convergence check
        if (ap == p) break;
        ap = p;

        // Box fold
        p = 2.0 * clamp(p, -CSize, CSize) - p;

        // Sphere fold
        float r2 = dot(p, p);
        float k = max(Size / r2, 1.0);
        p *= k;
        DEfactor *= k;

        // Julia-like translation
        p += foldC;

        // Track orbit traps
        trap.minDist = min(trap.minDist, length(p));
        trap.planeX = min(trap.planeX, abs(p.x));
        trap.planeY = min(trap.planeY, abs(p.y));
        trap.planeZ = min(trap.planeZ, abs(p.z));
        trap.iterations = i + 1;
    }

    // Z-plane distance estimation (Knighty)
    return abs(0.5 * abs(p.z + 0.1) / DEfactor - DEoffset);
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 p = pos;

    p.x = mod(p.x + 3.0, 6.0) - 3.0;
    p.y = mod(p.y + 2.0, 4.0) - 2.0;

    float DEfactor = 1.5;
    vec3 ap = p + 1.0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        if (ap == p) break;
        ap = p;
        p = 2.0 * clamp(p, -CSize, CSize) - p;
        float r2 = dot(p, p);
        float k = max(Size / r2, 1.0);
        p *= k;
        DEfactor *= k;
        p += foldC;
    }

    return abs(0.5 * abs(p.z + 0.1) / DEfactor - DEoffset);
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float trapX = exp(-trap.planeX * 2.0);
    float trapY = exp(-trap.planeY * 2.0);
    float trapZ = exp(-trap.planeZ * 2.0);

    float structural = 1.0 - exp(-trap.minDist * 0.5);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, iterNorm);
}
