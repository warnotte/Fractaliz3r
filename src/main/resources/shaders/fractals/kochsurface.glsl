/**
 * Koch Quadratic Surface (Type 1) Distance Estimator
 *
 * A surface fractal: a flat base plane with boxes stacked in a cross
 * pattern (center + 4 edges of a 3x3 grid), applied recursively.
 * Each iteration subdivides the cross cells and stacks smaller boxes on top.
 * The surface grows upward — corners stay flat, crosses keep growing.
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform int maxIterations;
uniform float scale;
uniform int basePrimitive;

// ============================================================================
// Box SDF
// ============================================================================

float sdBox3(vec3 p, vec3 b) {
    vec3 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

// ============================================================================
// Cell shape selector (matches BasePrimitive ordinals:
//   0=Sphere, 1=Box, 2=Octahedron, 3=Torus, 4=Rounded Box)
// All shapes are fitted within a cell of given half-size.
// ============================================================================

float cellShape(vec3 p, float hs, int type) {
    if (type == 0) return length(p) - hs;
    if (type == 2) { vec3 ap = abs(p); return (ap.x + ap.y + ap.z - hs) * 0.57735027; }
    if (type == 3) { float r = hs * 0.7; float t = hs * 0.3; return length(vec2(length(p.xz) - r, p.y)) - t; }
    if (type == 4) { float r = hs * 0.15; vec3 q = abs(p) - vec3(hs - r); return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r; }
    return sdBox3(p, vec3(hs));
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
// Core: check if a cell at a given world position is valid (all ancestors
// are in the cross pattern). Returns true if boxes should exist here.
// ============================================================================

bool isValidKochCell(vec2 worldXZ, int level, int iScale) {
    float fScale = float(iScale);
    float center = floor(fScale * 0.5);
    vec2 uv = worldXZ + 0.5;

    for (int j = 0; j <= level; j++) {
        uv *= fScale;
        vec2 idx = clamp(floor(uv), 0.0, fScale - 1.0);
        uv = fract(uv);

        if (idx.x != center && idx.y != center) return false;
    }
    return true;
}

// ============================================================================
// Koch Surface DE — union of boxes at each recursion level.
// At each level, checks a 3x3 neighborhood of cells for robustness.
// ============================================================================

float kochDE(vec3 p, out int hitLevel) {
    int iScale = max(int(floor(scale + 0.5)), 3); // round to nearest int >= 3
    float fScale = float(iScale);
    hitLevel = 0;

    // Thin base plane (always present)
    float d = sdBox3(p - vec3(0.0, -0.005, 0.0), vec3(0.5, 0.005, 0.5));

    float currentH = 0.0;
    float cellSize = 1.0 / fScale;
    float levelMul = fScale;

    for (int i = 0; i < maxIterations; i++) {
        float halfCS = cellSize * 0.5;

        // Find the cell this point falls in
        vec2 baseIdx = floor((p.xz + 0.5) * levelMul);

        // Check a 3x3 neighborhood to ensure correct DE near boundaries
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                vec2 neighborIdx = baseIdx + vec2(float(dx), float(dz));
                vec2 cellCenter = (neighborIdx + 0.5) / levelMul - 0.5;

                // Skip cells outside the fractal bounds
                if (abs(cellCenter.x) > 0.5 + halfCS || abs(cellCenter.y) > 0.5 + halfCS) continue;

                // Check hierarchical validity (all ancestors must be cross cells)
                if (!isValidKochCell(cellCenter, i, iScale)) continue;

                float boxY = currentH + halfCS;
                float boxD = cellShape(p - vec3(cellCenter.x, boxY, cellCenter.y),
                                       halfCS, basePrimitive);
                if (boxD < d) {
                    d = boxD;
                    hitLevel = i + 1;
                }
            }
        }

        currentH += cellSize;
        cellSize /= fScale;
        levelMul *= fScale;
    }

    return d;
}

// ============================================================================
// Full DE with orbit traps
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    int hitLevel;
    float d = kochDE(pos, hitLevel);

    // Compute orbit traps from hierarchical position
    int iScale = max(int(floor(scale + 0.5)), 3);
    float fScale = float(iScale);
    float center = floor(fScale * 0.5);
    vec2 uv = pos.xz + 0.5;
    float currentH = 0.0;
    float cellSize = 1.0 / fScale;

    for (int i = 0; i < hitLevel; i++) {
        uv *= fScale;
        vec2 idx = clamp(floor(uv), 0.0, fScale - 1.0);
        uv = fract(uv);

        trap.planeX = min(trap.planeX, abs(uv.x - 0.5));
        trap.planeZ = min(trap.planeZ, abs(uv.y - 0.5));
        trap.planeY = min(trap.planeY, abs(pos.y - (currentH + cellSize * 0.5)));

        currentH += cellSize;
        cellSize /= fScale;
    }

    trap.minDist = d;
    trap.iterations = hitLevel;

    return d;
}

// ============================================================================
// Simple DE (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    int hitLevel;
    return kochDE(pos, hitLevel);
}

// ============================================================================
// Material Color from Orbit Traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float trapX = exp(-trap.planeX * 8.0);
    float trapY = exp(-trap.planeY * 8.0);
    float trapZ = exp(-trap.planeZ * 8.0);

    float structural = 1.0 - exp(-trap.minDist * 20.0);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations, 1));

    return vec3(structural, flow, iterNorm);
}
