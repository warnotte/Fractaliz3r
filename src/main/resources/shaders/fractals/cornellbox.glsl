/**
 * Cornell Box — Per-Object Materials Scene
 *
 * A classic Cornell Box with colored walls, an area light panel,
 * a glass sphere, and a metal sphere. Designed to showcase
 * path tracing caustics and per-object material assignment.
 *
 * Objects:
 *  0 = Floor (white)       4 = Back wall (white)
 *  1 = Ceiling (white)     5 = Light panel (emissive)
 *  2 = Left wall (red)     6 = Glass sphere
 *  3 = Right wall (green)  7 = Metal sphere
 */

// Per-object material system — must be defined BEFORE raytracer.glsl is compiled
#define HAS_PER_OBJECT_MATERIAL

// ============================================================================
// Fractal-specific uniforms
// ============================================================================

uniform float sceneScale;

// ============================================================================
// Per-Object Material Structure
// ============================================================================

struct ObjectMaterial {
    int type;         // MATERIAL_LAMBERTIAN, MATERIAL_METALLIC, MATERIAL_GLASS
    vec3 albedo;
    float roughness;
    float metalness;
    float ior;
    float emissive;
};

// ============================================================================
// Orbit Trap Structure
// ============================================================================

struct OrbitTrap {
    float minDist;
    float objectId;    // 0-7 per-object ID
    float planeY;
    float planeZ;
    int iterations;
};

// ============================================================================
// SDF Primitives
// ============================================================================

float sdPlaneY(vec3 p, float y) {
    return p.y - y;
}

float sdPlaneNegY(vec3 p, float y) {
    return y - p.y;
}

float sdPlaneX(vec3 p, float x) {
    return p.x - x;
}

float sdPlaneNegX(vec3 p, float x) {
    return x - p.x;
}

float sdPlaneNegZ(vec3 p, float z) {
    return z - p.z;
}

float sdSphere(vec3 p, float r) {
    return length(p) - r;
}

float sdBox(vec3 p, vec3 b) {
    vec3 d = abs(p) - b;
    return min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, 0.0));
}

// ============================================================================
// Union helper (returns vec2(dist, objectId))
// ============================================================================

vec2 opU(vec2 d1, vec2 d2) {
    return (d1.x < d2.x) ? d1 : d2;
}

// ============================================================================
// Scene map
// ============================================================================

vec2 mapScene(vec3 pos) {
    float sc = sceneScale;
    vec3 p = pos / sc;

    // Walls (the box: x in [-1,1], y in [0,2], z in [0,2])
    vec2 res = vec2(sdPlaneY(p, 0.0), 0.0);                    // Floor (ID 0)
    res = opU(res, vec2(sdPlaneNegY(p, 2.0), 1.0));            // Ceiling (ID 1)
    res = opU(res, vec2(sdPlaneX(p, -1.0), 2.0));              // Left wall (ID 2)
    res = opU(res, vec2(sdPlaneNegX(p, 1.0), 3.0));            // Right wall (ID 3)
    res = opU(res, vec2(sdPlaneNegZ(p, 2.0), 4.0));            // Back wall (ID 4)

    // Light panel — thin box at ceiling (ID 5)
    res = opU(res, vec2(sdBox(p - vec3(0.0, 1.99, 1.0), vec3(0.3, 0.01, 0.3)), 5.0));

    // Glass sphere (ID 6)
    res = opU(res, vec2(sdSphere(p - vec3(-0.35, 0.4, 1.2), 0.4), 6.0));

    // Metal sphere (ID 7)
    res = opU(res, vec2(sdSphere(p - vec3(0.35, 0.3, 0.7), 0.3), 7.0));

    // Apply scene scale
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
// Material coloring from orbit traps (used in classic raytracing mode)
// ============================================================================

vec3 getFactors(OrbitTrap trap) {
    float id = trap.objectId;
    float structural = id / 8.0;
    float flow = clamp(trap.planeY * 0.25, 0.0, 1.0);
    float detail = fract(id * 0.618);
    return vec3(structural, flow, detail);
}

// ============================================================================
// Per-Object Material Assignment
// ============================================================================

ObjectMaterial getObjectMaterial(OrbitTrap trap) {
    ObjectMaterial mat;
    // Defaults
    mat.type = MATERIAL_LAMBERTIAN;
    mat.roughness = 1.0;
    mat.metalness = 0.0;
    mat.ior = 1.0;
    mat.emissive = 0.0;

    int id = int(trap.objectId + 0.5);

    if (id == 0 || id == 1 || id == 4) {
        // Floor, Ceiling, Back wall — white diffuse
        mat.albedo = vec3(0.73, 0.73, 0.73);
    } else if (id == 2) {
        // Left wall — red
        mat.albedo = vec3(0.65, 0.05, 0.05);
    } else if (id == 3) {
        // Right wall — green
        mat.albedo = vec3(0.12, 0.45, 0.15);
    } else if (id == 5) {
        // Light panel — emissive white
        mat.albedo = vec3(15.0, 15.0, 15.0);
        mat.emissive = 15.0;
    } else if (id == 6) {
        // Glass sphere
        mat.type = MATERIAL_GLASS;
        mat.albedo = vec3(1.0, 1.0, 1.0);
        mat.roughness = 0.0;
        mat.ior = 1.5;
    } else if (id == 7) {
        // Metal sphere — gold
        mat.type = MATERIAL_METALLIC;
        mat.albedo = vec3(0.95, 0.8, 0.5);
        mat.roughness = 0.05;
        mat.metalness = 0.95;
    }

    return mat;
}
