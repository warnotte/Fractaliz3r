/**
 * Pseudo Kleinian Distance Estimator
 *
 * Based on Knighty's Fragmentarium implementation.
 * Combines box folding, sphere folding, and Julia-style translation
 * to create beautiful organic and crystalline structures.
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

uniform int maxIterations;
uniform float size;        // Sphere fold size
uniform vec3 cSize;        // Box fold bounds
uniform vec3 juliaC;       // Julia constant
uniform float deOffset;    // DE offset for surface adjustment
uniform float zOffset;     // Z offset for final DE

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float avgDist;
    float sphereHits;
    int iterations;
};

// ============================================================================
// Pseudo Kleinian Distance Estimator (full version with orbit traps)
//
// Algorithm:
// 1. Box fold: clamp and reflect
// 2. Sphere fold: conditional inversion
// 3. Julia translation: add constant
// 4. DE from final z coordinate
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 p = pos;
    float DEfactor = 1.0;

    trap.minDist = 1e10;
    trap.avgDist = 0.0;
    trap.sphereHits = 0.0;
    trap.iterations = 0;

    vec3 ap = p + vec3(1.0); // Initialize different from p

    for (int i = 0; i < maxIterations; i++) {
        // Check for convergence (optimization)
        if (distance(ap, p) < 0.000001) break;
        ap = p;

        // Box fold: p = 2 * clamp(p, -cSize, cSize) - p
        p = 2.0 * clamp(p, -cSize, cSize) - p;

        // Track orbit for coloring
        float dist = length(p);
        trap.minDist = min(trap.minDist, dist);
        trap.avgDist += dist;

        // Sphere fold
        float r2 = dot(p, p);
        float k = max(size / r2, 1.0);
        p *= k;
        DEfactor *= k;

        if (k > 1.0) {
            trap.sphereHits += 1.0;
        }

        // Julia translation
        p += juliaC;

        trap.iterations = i + 1;

        // Escape check
        if (dot(p, p) > 10000.0) break;
    }

    trap.avgDist /= float(max(trap.iterations, 1));

    // Distance estimator: based on z-component
    // DE = |0.5 * |p.z - zOffset| / DEfactor - deOffset|
    float de = abs(0.5 * abs(p.z - zOffset) / DEfactor - deOffset);

    return de;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 p = pos;
    float DEfactor = 1.0;
    vec3 ap = p + vec3(1.0);

    for (int i = 0; i < maxIterations; i++) {
        if (distance(ap, p) < 0.000001) break;
        ap = p;

        // Box fold
        p = 2.0 * clamp(p, -cSize, cSize) - p;

        // Sphere fold
        float r2 = dot(p, p);
        float k = max(size / r2, 1.0);
        p *= k;
        DEfactor *= k;

        // Julia translation
        p += juliaC;

        if (dot(p, p) > 10000.0) break;
    }

    return abs(0.5 * abs(p.z - zOffset) / DEfactor - deOffset);
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // X: Structure (Inversion Density - Sphere hits)
    float structural = smoothstep(0.1, 0.6, trap.sphereHits / float(max(trap.iterations, 1)));

    // Y: Flow (Proximity / Shine)
    float flow = 1.0 - exp(-trap.minDist * 5.0);

    // Z: Detail (Iterations)
    float detail = float(trap.iterations) / float(max(maxIterations, 1));

    return vec3(structural, flow, detail);
}
