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

#endif
