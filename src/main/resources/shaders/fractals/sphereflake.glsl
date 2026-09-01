/**
 * Sphereflake Distance Estimator
 *
 * Recursive 3D structure using octahedral symmetry folding.
 * Union of base primitives at every recursion level — parent shape plus
 * 6 child shapes on each face, recursively subdivided.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float childScale;
uniform float spacing;
uniform float rotAngleX;
uniform float rotAngleY;
uniform float rotAngleZ;
uniform float offsetY;
uniform float offsetZ;
uniform int basePrimitive;

// ============================================================================
// IFS base primitive selector (0=Sphere, 1=Box, 2=Octahedron, 3=Torus, 4=Rounded Box)
// ============================================================================

float ifsBasePrimitive(vec3 p, int type) {
    if (type == 1) { vec3 d = abs(p) - vec3(1.0); return length(max(d, 0.0)) + min(max(d.x, max(d.y, d.z)), 0.0); }
    if (type == 2) { vec3 ap = abs(p); return (ap.x + ap.y + ap.z - 1.0) * 0.57735027; }
    if (type == 3) { return length(vec2(length(p.xz) - 0.7, p.y)) - 0.3; }
    if (type == 4) { vec3 q = abs(p) - vec3(0.9); return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - 0.1; }
    return length(p) - 1.0;
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
// Sphereflake DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    float totalScale = 1.0;
    float invChild = 1.0 / childScale;
    float offset = (1.0 + childScale) * spacing;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    // Precompute rotation matrices for each axis
    float cax = cos(rotAngleX), sax = sin(rotAngleX);
    float cay = cos(rotAngleY), say = sin(rotAngleY);
    float caz = cos(rotAngleZ), saz = sin(rotAngleZ);

    // Parent shape (level 0)
    float minDE = ifsBasePrimitive(z, basePrimitive);
    int hitLevel = 0;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        // Fold into positive octant (octahedral symmetry)
        z = abs(z);

        // Sort axes so z.x >= z.y >= z.z (identify closest child)
        if (z.x < z.y) z.xy = z.yx;
        if (z.x < z.z) z.xz = z.zx;
        if (z.y < z.z) z.yz = z.zy;

        // Translate to child center along x axis
        z.x -= offset;

        // Asymmetric offsets
        z.y -= offsetY;
        z.z -= offsetZ;

        // Scale into child's local space
        z *= invChild;
        totalScale *= childScale;

        // Inter-level rotations (X, Y, Z axes applied sequentially)
        if (rotAngleX != 0.0) {
            z.yz = mat2(cax, -sax, sax, cax) * z.yz;
        }
        if (rotAngleY != 0.0) {
            z.xz = mat2(cay, say, -say, cay) * z.xz;
        }
        if (rotAngleZ != 0.0) {
            z.xy = mat2(caz, -saz, saz, caz) * z.xy;
        }

        // DE to base primitive at this recursion level (union of all levels)
        float d = ifsBasePrimitive(z, basePrimitive) * totalScale;
        if (d < minDE) {
            minDE = d;
            hitLevel = i + 1;
        }

        // Track orbit traps
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
    }

    trap.minDist = minDE;
    trap.iterations = hitLevel;

    return minDE;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    vec3 z = pos;
    float totalScale = 1.0;
    float invChild = 1.0 / childScale;
    float offset = (1.0 + childScale) * spacing;

    float cax = cos(rotAngleX), sax = sin(rotAngleX);
    float cay = cos(rotAngleY), say = sin(rotAngleY);
    float caz = cos(rotAngleZ), saz = sin(rotAngleZ);

    // Parent shape (level 0)
    float minDE = ifsBasePrimitive(z, basePrimitive);

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        z = abs(z);

        if (z.x < z.y) z.xy = z.yx;
        if (z.x < z.z) z.xz = z.zx;
        if (z.y < z.z) z.yz = z.zy;

        z.x -= offset;
        z.y -= offsetY;
        z.z -= offsetZ;

        z *= invChild;
        totalScale *= childScale;

        if (rotAngleX != 0.0) {
            z.yz = mat2(cax, -sax, sax, cax) * z.yz;
        }
        if (rotAngleY != 0.0) {
            z.xz = mat2(cay, say, -say, cay) * z.xz;
        }
        if (rotAngleZ != 0.0) {
            z.xy = mat2(caz, -saz, saz, caz) * z.xy;
        }

        float d = ifsBasePrimitive(z, basePrimitive) * totalScale;
        minDE = min(minDE, d);
    }

    return minDE;
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float trapX = exp(-trap.planeX * 3.0);
    float trapY = exp(-trap.planeY * 3.0);
    float trapZ = exp(-trap.planeZ * 3.0);

    float structural = 1.0 - exp(-trap.minDist * 20.0);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));

    return vec3(structural, flow, iterNorm);
}
