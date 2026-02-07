/**
 * Test Scene — SDF Primitives Showcase
 *
 * A non-fractal scene composed of Inigo Quilez SDF primitives (MIT license)
 * arranged on a ground plane, designed to test all rendering effects:
 * reflections, emission, SSS, materials, lighting, shadows, AO.
 *
 * SDF primitives: https://iquilezles.org/articles/distfunctions
 * The MIT License — Copyright (c) 2013 Inigo Quilez
 */

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float sceneScale;

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float objectId;    // 0-8 per-object ID for coloring
    float planeY;      // height for gradient
    float planeZ;      // unused padding (kept for compatibility)
    int iterations;    // always 1 (non-fractal)
};

// ============================================================================
// SDF Primitives (Inigo Quilez, MIT License)
// ============================================================================

float sdPlane(vec3 p) {
    return p.y;
}

float sdSphere(vec3 p, float s) {
    return length(p) - s;
}

float sdBox(vec3 p, vec3 b) {
    vec3 d = abs(p) - b;
    return min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, 0.0));
}

float sdTorus(vec3 p, vec2 t) {
    return length(vec2(length(p.xz) - t.x, p.y)) - t.y;
}

float sdCapsule(vec3 p, vec3 a, vec3 b, float r) {
    vec3 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h) - r;
}

float sdOctahedron(vec3 p, float s) {
    p = abs(p);
    float m = p.x + p.y + p.z - s;
    vec3 q;
         if (3.0 * p.x < m) q = p.xyz;
    else if (3.0 * p.y < m) q = p.yzx;
    else if (3.0 * p.z < m) q = p.zxy;
    else return m * 0.57735027;
    float k = clamp(0.5 * (q.z - q.y + s), 0.0, s);
    return length(vec3(q.x, q.y - s + k, q.z - k));
}

float sdRoundCone(vec3 p, float r1, float r2, float h) {
    vec2 q = vec2(length(p.xz), p.y);
    float b = (r1 - r2) / h;
    float a = sqrt(1.0 - b * b);
    float k = dot(q, vec2(-b, a));
    if (k < 0.0) return length(q) - r1;
    if (k > a * h) return length(q - vec2(0.0, h)) - r2;
    return dot(q, vec2(a, b)) - r1;
}

float sdPyramid(vec3 p, float h) {
    float m2 = h * h + 0.25;
    p.xz = abs(p.xz);
    p.xz = (p.z > p.x) ? p.zx : p.xz;
    p.xz -= 0.5;
    vec3 q = vec3(p.z, h * p.y - 0.5 * p.x, h * p.x + 0.5 * p.y);
    float s = max(-q.x, 0.0);
    float t = clamp((q.y - 0.5 * p.z) / (m2 + 0.25), 0.0, 1.0);
    float aa = m2 * (q.x + s) * (q.x + s) + q.y * q.y;
    float bb = m2 * (q.x + 0.5 * t) * (q.x + 0.5 * t) + (q.y - m2 * t) * (q.y - m2 * t);
    float d2 = min(q.y, -q.x * m2 - q.y * 0.5) > 0.0 ? 0.0 : min(aa, bb);
    return sqrt((d2 + q.z * q.z) / m2) * sign(max(q.z, -p.y));
}

// ============================================================================
// Union helper (returns vec2(dist, objectId))
// ============================================================================

vec2 opU(vec2 d1, vec2 d2) {
    return (d1.x < d2.x) ? d1 : d2;
}

// ============================================================================
// Scene map — returns vec2(distance, objectId)
// ============================================================================

vec2 mapScene(vec3 pos) {
    float sc = sceneScale;
    vec3 p = pos / sc;

    // Ground plane (ID = 0)
    vec2 res = vec2(sdPlane(p), 0.0);

    // Row 1 (z = 0): Sphere, Box, Torus, Capsule
    res = opU(res, vec2(sdSphere(p - vec3(-1.5, 0.35, 0.0), 0.35), 1.0));
    res = opU(res, vec2(sdBox(p - vec3(-0.5, 0.3, 0.0), vec3(0.3, 0.3, 0.3)), 2.0));
    res = opU(res, vec2(sdTorus((p - vec3(0.5, 0.3, 0.0)).xzy, vec2(0.25, 0.08)), 3.0));
    res = opU(res, vec2(sdCapsule(p, vec3(1.2, 0.1, 0.0), vec3(1.8, 0.5, 0.0), 0.1), 4.0));

    // Row 2 (z = 1.2): Octahedron, Round Cone, Pyramid
    res = opU(res, vec2(sdOctahedron(p - vec3(-1.5, 0.35, 1.2), 0.35), 5.0));
    res = opU(res, vec2(sdRoundCone(p - vec3(-0.5, 0.0, 1.2), 0.2, 0.08, 0.5), 6.0));
    res = opU(res, vec2(sdPyramid(p - vec3(0.5, 0.0, 1.2), 0.6), 7.0));

    // Apply scene scale to final distance
    res.x *= sc;
    return res;
}

// ============================================================================
// DE (full version with orbit traps)
// ============================================================================

float DE(vec3 pos, out OrbitTrap trap) {
    vec2 hit = mapScene(pos);

    trap.minDist = hit.x;
    trap.objectId = hit.y;
    trap.planeY = pos.y;
    trap.planeZ = 0.0;
    trap.iterations = 1;

    return hit.x;
}

// ============================================================================
// DE_simple (for shadows, AO, normals)
// ============================================================================

float DE_simple(vec3 pos) {
    return mapScene(pos).x;
}

// ============================================================================
// Material coloring from orbit traps
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    // Use objectId to distribute colors across the palette
    float idNorm = trap.objectId / 8.0;

    // Height-based gradient for depth variation
    float heightFactor = clamp(trap.planeY * 0.5 + 0.5, 0.0, 1.0);

    // Ground plane gets a distinct low-key color
    if (trap.objectId < 0.5) {
        return vec3(0.1, 0.05, 0.5);
    }

    // Structural: per-object hue shift
    float structural = fract(idNorm * 1.618);

    // Flow: height gradient creates smooth variation
    float flow = heightFactor;

    // Detail: give each object a distinct detail level
    float detail = fract(idNorm * 3.14);

    return vec3(structural, flow, detail);
}
