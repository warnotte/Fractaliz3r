/**
 * Apollonian Gasket Distance Estimator
 *
 * Tetrahedral folds combined with sphere inversion.
 * The sphere inversion creates characteristic sphere-packing geometry,
 * distinct from Sierpinski's sharp tetrahedra.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform float foldRadius;
uniform int basePrimitive;

// ============================================================================
// IFS base primitive selector (0=Sphere, 1=Box, 2=Octahedron, 3=Torus, 4=Rounded Box)
// ============================================================================

float ifsBasePrimitive(vec3 p, int type) {
    if (type == 1) { vec3 d = abs(p) - vec3(1.0); return length(max(d, 0.0)) + min(max(d.x, max(d.y, d.z)), 0.0); }
    if (type == 2) { vec3 ap = abs(p); return (ap.x + ap.y + ap.z - 1.0) * 0.57735027; }
    if (type == 3) { return length(vec2(length(p.xz) - 0.7, p.y)) - 0.3; }
    if (type == 4) { vec3 q = abs(p) - vec3(0.9); return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - 0.1; }
    return length(p);
}

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
// Apollonian Gasket DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    float s = 1.0;
    vec3 offset = vec3(1.0);
    float fr2 = foldRadius * foldRadius;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        // Tetrahedral symmetry folds
        if (z.x + z.y < 0.0) z.xy = -z.yx;
        if (z.x + z.z < 0.0) z.xz = -z.zx;
        if (z.y + z.z < 0.0) z.yz = -z.zy;

        // Scale and translate
        z = z * scale - offset * (scale - 1.0);
        s *= scale;

        // Sphere inversion (what makes this Apollonian)
        float r2 = dot(z, z);
        if (r2 < fr2) {
            float k = fr2 / r2;
            z *= k;
            s *= k;
        }

        // Track orbit traps
        trap.minDist = min(trap.minDist, ifsBasePrimitive(z, basePrimitive));
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
        trap.iterations = i + 1;
    }

    return (ifsBasePrimitive(z, basePrimitive) - 2.0) / s;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float s = 1.0;
    vec3 offset = vec3(1.0);
    float fr2 = foldRadius * foldRadius;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        if (z.x + z.y < 0.0) z.xy = -z.yx;
        if (z.x + z.z < 0.0) z.xz = -z.zx;
        if (z.y + z.z < 0.0) z.yz = -z.zy;

        z = z * scale - offset * (scale - 1.0);
        s *= scale;

        float r2 = dot(z, z);
        if (r2 < fr2) {
            float k = fr2 / r2;
            z *= k;
            s *= k;
        }
    }

    return (ifsBasePrimitive(z, basePrimitive) - 2.0) / s;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float trapX = exp(-trap.planeX * 3.0);
    float trapY = exp(-trap.planeY * 3.0);
    float trapZ = exp(-trap.planeZ * 3.0);

    float structural = 1.0 - exp(-trap.minDist * 0.6);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, iterNorm);
}
