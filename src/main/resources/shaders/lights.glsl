/**
 * The light list: every light the shaders may sample, in one table built by
 * LightList.java. Compiled in only for the scenes that use it (an emissive primitive to
 * sample directly, or the photon pass from every light); without HAS_EMITTERS or BIDIR
 * this file is empty and the program is what it was.
 *
 * Emitters are the node graph's emissive primitives (a MaterialNode with a solid colour
 * and an emission over a sphere or a box, through standard transforms and unions only):
 * their world shape is analytic, so a point on them can be drawn with a known density.
 * An emissive fractal cannot be sampled and stays what it was, found by chance.
 */
#if defined(HAS_EMITTERS) || defined(BIDIR)

struct LightData {
    float type;                 // LIGHT_*
    float px, py, pz;           // centre
    float dx, dy, dz;           // direction (sun, beam, spot)
    float r, g, b;              // colour
    float intensity;            // radiance of an emitter, with the colour
    float qx, qy, qz, qw;       // orientation of a box emitter
    float sx, sy, sz;           // box half extents, or the sphere's radius in sx
    float power;                // for choosing among lights: luminance x area x pi for an emitter
    float matId;                // the MaterialNode an emitter is, to weight a path that hits it
    float area;                 // world surface area of an emitter
    float pad0, pad1, pad2;     // 24 floats, the stride LightList.java writes
};

layout(std430, binding = 7) readonly buffer LightBuffer { LightData lights[]; };
uniform int lightCount;
uniform float emitterPower;     // the sum of power over the emitters
uniform int emitterDebug;       // EmitterProbe: 1 = the direct draw alone, 2 = hits alone, unweighted

const int LIGHT_SUN = 0;
const int LIGHT_BEAM = 1;
const int LIGHT_POINT = 2;
const int LIGHT_SPOT = 3;
const int LIGHT_EMITTER_SPHERE = 4;
const int LIGHT_EMITTER_BOX = 5;

bool isEmitter(int i) { return lights[i].type >= 4.0; }

vec3 emitterRadiance(int i) { return vec3(lights[i].r, lights[i].g, lights[i].b) * lights[i].intensity; }

float emitterPdfArea(int i) { return 1.0 / max(lights[i].area, 1e-8); }

/** The probability of drawing emitter i in pickEmitter. */
float emitterPickPdf(int i) { return lights[i].power / max(emitterPower, 1e-8); }

/** One emitter, drawn with probability proportional to its power. */
int pickEmitter(inout uint seed, out float pickPdf) {
    float u = random(seed) * emitterPower;
    float acc = 0.0;
    int last = -1;
    for (int i = 0; i < lightCount; i++) {
        if (!isEmitter(i)) continue;
        last = i;
        acc += lights[i].power;
        if (u < acc) break;
    }
    pickPdf = last >= 0 ? emitterPickPdf(last) : 1.0;
    return last;
}

/** A point on emitter i, uniform over its surface (density 1 / area), and its outward normal. */
void sampleEmitterPoint(int i, inout uint seed, out vec3 p, out vec3 n) {
    LightData L = lights[i];
    vec3 c = vec3(L.px, L.py, L.pz);
    if (int(L.type) == LIGHT_EMITTER_SPHERE) {
        float z = 1.0 - 2.0 * random(seed);
        float phi = TAU * random(seed);
        float rxy = sqrt(max(0.0, 1.0 - z * z));
        n = vec3(rxy * cos(phi), rxy * sin(phi), z);
        p = c + n * L.sx;
        return;
    }
    vec3 h = vec3(L.sx, L.sy, L.sz);
    float ax = h.y * h.z, ay = h.x * h.z, az = h.x * h.y;      // the three face areas, up to a factor
    float u = random(seed) * (ax + ay + az);
    float s = random(seed) < 0.5 ? 1.0 : -1.0;
    vec2 uv = vec2(random(seed), random(seed)) * 2.0 - 1.0;
    vec3 ln, lp;
    if (u < ax)           { ln = vec3(s, 0.0, 0.0); lp = vec3(s * h.x, uv.x * h.y, uv.y * h.z); }
    else if (u < ax + ay) { ln = vec3(0.0, s, 0.0); lp = vec3(uv.x * h.x, s * h.y, uv.y * h.z); }
    else                  { ln = vec3(0.0, 0.0, s); lp = vec3(uv.x * h.x, uv.y * h.y, s * h.z); }
    vec4 q = vec4(L.qx, L.qy, L.qz, L.qw);
    p = c + rotateByQuaternion(lp, q);
    n = rotateByQuaternion(ln, q);
}

/** The light entry of a material, -1 when that emitter is not in the list. */
int lightOfMaterial(int matId) {
    for (int i = 0; i < lightCount; i++) if (int(lights[i].matId) == matId) return i;
    return -1;
}

#ifdef BIDIR
// ============================================================================
// Light tracing: what the photon pass draws from and what the path tracer needs to
// know about it (its densities), so that both weight a path the same way.
// ============================================================================

uniform float lightPowerTotal;   // the sum of power over the lights that send photons
uniform float causticRadius;     // half the side of the sun's emission square
uniform vec3 causticCenter;      // the square is centred over this point, facing the sun
// The square as a grid of cells: those whose photons met glass or metal, listed by index
// and as a map, one snapshot (see GLSLEngine.refreshCellMap)
uniform sampler2D causticCellList;
uniform sampler2D causticCellActive;
uniform int causticActiveCells;
uniform int photonSide;          // the photon pass traces photonSide * photonSide photons per sample
const int CAUSTIC_GRID = 64;
const float CAUSTIC_EXPLORE = 0.25;   // the share of sun photons drawn over the whole square regardless
const float LT_LAND = 0.5;            // at a matte or metal vertex, the photon's chance of landing there

// The weights compare one camera path per pixel per sample against photonSide^2 photons
// per sample spread over the whole image: a strategy's density counts as many times as it
// is drawn, so the photon pass's densities carry the number of photons.
float ltCount() { return float(photonSide * photonSide); }

float lightPickPdf(int i) { return lights[i].power / max(lightPowerTotal, 1e-8); }

/** One light, drawn with probability proportional to its power; -1 when none sends photons. */
int pickLight(inout uint seed, out float pdf) {
    float u = random(seed) * lightPowerTotal;
    float acc = 0.0;
    int last = -1;
    for (int i = 0; i < lightCount; i++) {
        if (lights[i].power <= 0.0) continue;
        last = i;
        acc += lights[i].power;
        if (u < acc) break;
    }
    pdf = last >= 0 ? lightPickPdf(last) : 1.0;
    return last;
}

int lightOfType(int t) {
    for (int i = 0; i < lightCount; i++) if (int(lights[i].type) == t) return i;
    return -1;
}

void sunFrame(out vec3 L, out vec3 u, out vec3 v) {
    L = normalize(lightDir);
    u = normalize(cross(abs(L.y) < 0.999 ? vec3(0, 1, 0) : vec3(1, 0, 0), L));
    v = cross(L, u);
}

vec3 sunSquarePoint(vec2 sq, vec3 L, vec3 u, vec3 v) {
    float side = 2.0 * causticRadius;
    return causticCenter + L * (2.0 * causticRadius + 2.0) + u * (side * (sq.x - 0.5)) + v * (side * (sq.y - 0.5));
}

/** Where x projects on the square along the sun's direction, in [0,1]^2 when it is over it. */
vec2 sunSquareUV(vec3 x, vec3 L, vec3 u, vec3 v) {
    vec3 q = x - (causticCenter + L * (2.0 * causticRadius + 2.0));
    q -= L * dot(q, L);
    return vec2(dot(q, u), dot(q, v)) / (2.0 * causticRadius) + 0.5;
}

/** The density of the sun's draw at a point of the square: uniform, or the mixture of a
 *  quarter uniform and three quarters over the active cells. Zero off the square. */
float sunSquareDensity(vec2 sq) {
    if (any(lessThan(sq, vec2(0.0))) || any(greaterThanEqual(sq, vec2(1.0)))) return 0.0;
    float side = 2.0 * causticRadius;
    float density = 1.0 / (side * side);
    if (causticActiveCells > 0) {
        float inList = texelFetch(causticCellActive, ivec2(sq * float(CAUSTIC_GRID)), 0).r;
        float cellArea = (side / float(CAUSTIC_GRID)) * (side / float(CAUSTIC_GRID));
        density = CAUSTIC_EXPLORE / (side * side) + (1.0 - CAUSTIC_EXPLORE) * inList / (float(causticActiveCells) * cellArea);
    }
    return density;
}

/** A point of the square by that mixture; returns its density. */
float sunSquareDraw(inout uint seed, out vec2 sq) {
    if (causticActiveCells > 0 && random(seed) >= CAUSTIC_EXPLORE) {
        int i = min(int(random(seed) * float(causticActiveCells)), causticActiveCells - 1);
        int k = int(texelFetch(causticCellList, ivec2(i, 0), 0).r + 0.5);
        sq = (vec2(k % CAUSTIC_GRID, k / CAUSTIC_GRID) + vec2(random(seed), random(seed))) / float(CAUSTIC_GRID);
    } else {
        sq = vec2(random(seed), random(seed));
    }
    return sunSquareDensity(sq);
}

/** The GGX sampling density of direction b at a vertex of normal n seen from direction a
 *  (both away from the vertex); symmetric in a and b. */
float ggxPdf(vec3 n, vec3 a, vec3 b, float a2) {
    vec3 h = normalize(a + b);
    float NdotH = max(dot(n, h), 0.001);
    float AdotH = max(dot(a, h), 0.001);
    float D = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
    return D * NdotH / (4.0 * AdotH);
}
#endif

#endif
