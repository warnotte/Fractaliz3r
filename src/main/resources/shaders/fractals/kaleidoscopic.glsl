/**
 * Kaleidoscopic IFS Distance Estimator
 *
 * Based on the classic Sierpinski tetrahedron folding algorithm
 * Reference: Syntopia blog - Distance Estimated 3D Fractals (III): Folding Space
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform float ifsOffset;  // Classic KIFS uses scalar offset
uniform float foldAngleX;
uniform float foldAngleY;
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
    float sumDist;
    float avgFold;
    int iterations;
};

// ============================================================================
// Kaleidoscopic IFS Distance Estimator (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    trap.minDist = 1e10;
    trap.sumDist = 0.0;
    trap.avgFold = 0.0;
    trap.iterations = 0;

    vec3 z = pos;
    float foldSum = 0.0;

    int n;
    for (n = 0; n < maxIterations + gExtraIterations; n++) {
        // Classic KIFS folding - creates tetrahedral/kaleidoscopic symmetry
        // These are conditional reflections across planes

        // Fold 1: plane with normal (1, 1, 0)
        if (z.x + z.y < 0.0) {
            z.xy = -z.yx;
            foldSum += 1.0;
        }

        // Fold 2: plane with normal (1, 0, 1)
        if (z.x + z.z < 0.0) {
            z.xz = -z.zx;
            foldSum += 1.0;
        }

        // Fold 3: plane with normal (0, 1, 1)
        if (z.y + z.z < 0.0) {
            z.yz = -z.zy;
            foldSum += 1.0;
        }

        // Optional: apply small rotations for variety
        if (abs(foldAngleX) > 0.0001) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            z = vec3(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
        }
        if (abs(foldAngleY) > 0.0001) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            z = vec3(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
        }

        // Scale and translate - the key IFS transformation
        z = z * scale - ifsOffset * (scale - 1.0);

        // Track orbit traps for coloring
        float dist = ifsBasePrimitive(z, basePrimitive);
        trap.minDist = min(trap.minDist, dist);
        trap.sumDist += dist;
    }

    trap.iterations = n;
    trap.avgFold = foldSum / float(n);

    // Distance estimation formula for KIFS
    return ifsBasePrimitive(z, basePrimitive) * pow(scale, -float(n));
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;

    int n;
    for (n = 0; n < maxIterations + gExtraIterations; n++) {
        // Fold 1
        if (z.x + z.y < 0.0) z.xy = -z.yx;
        // Fold 2
        if (z.x + z.z < 0.0) z.xz = -z.zx;
        // Fold 3
        if (z.y + z.z < 0.0) z.yz = -z.zy;

        // Optional rotations
        if (abs(foldAngleX) > 0.0001) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            z = vec3(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
        }
        if (abs(foldAngleY) > 0.0001) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            z = vec3(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
        }

        // Scale and translate
        z = z * scale - ifsOffset * (scale - 1.0);
    }

    return ifsBasePrimitive(z, basePrimitive) * pow(scale, -float(n));
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // X: Structure (Proximity)
    float structural = 1.0 - exp(-trap.minDist * 4.0);

    // Y: Flow (Sum dist variation)
    float flow = sin(trap.sumDist * 0.2) * 0.5 + 0.5;

    // Z: Detail (Iterations)
    float detail = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, detail);
}
