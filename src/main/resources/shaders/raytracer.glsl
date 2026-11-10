/**
 * Generic Raytracer for Fractal Rendering
 *
 * This file is FRACTAL-AGNOSTIC. It requires the fractal to define:
 * - float DE(vec3 pos, out OrbitTrap trap)  - Distance estimator with orbit traps
 * - float DE_simple(vec3 pos)               - Simple DE for shadows/AO/normals
 * - vec3 getFactors(OrbitTrap trap)         - Geometric factors for material system
 * - OrbitTrap struct                        - Fractal-specific orbit data
 *
 * Because GLSL has global uniforms, this works seamlessly!
 */

in vec2 fragCoord;
in vec2 uv;

#ifndef PHOTON_PASS
out vec4 FragColor;
#endif

// Adaptive Sampling variance image (R=sumLum, G=sumSqLum, B=count)
layout(rgba32f, binding = 5) uniform image2D varianceImage;

// ============================================================================
// Ray Hit Result
// ============================================================================

struct RayHit {
    bool hit;
    vec3 pos;
    float dist;
    float minDist;  // For glow effect
    OrbitTrap trap;
    int steps;
    int matType;
};

// ============================================================================
// Boolean Operations (CSG)
// ============================================================================

#ifdef BOOLEAN_OPS
uniform int boolOp;       // 1=Union, 2=Intersect, 3=Subtract
uniform vec3 boolOffset;  // Translation of secondary fractal
uniform float boolScale;  // Scale of secondary fractal
uniform float boolBlend;  // Smooth blend radius (0 = sharp)
uniform vec3 boolRotation; // Euler rotation XYZ (radians) of secondary fractal
uniform float nestThreshold;   // Nesting: shell thickness around primary surface
uniform float nestRepeatScale; // Nesting: repetition density of secondary
uniform float nestRotation;    // Nesting: rotation angle (radians) around (1,1,1) axis
uniform float nestMix;         // Nesting: 0 = pure primary, 1 = full nesting

// Rodrigues rotation around arbitrary axis
vec3 nestRotate(vec3 p, vec3 axis, float angle) {
    if (angle == 0.0) return p;
    float c = cos(angle);
    float s = sin(angle);
    float d = dot(axis, p);
    return p * c + cross(axis, p) * s + axis * d * (1.0 - c);
}

// Per-cell random rotation: unique axis + angle derived from cell ID
vec3 nestCellRotate(vec3 cellPos, vec3 cellId, float maxAngle) {
    if (maxAngle == 0.0) return cellPos;
    float h1 = fract(sin(dot(cellId, vec3(12.9898, 78.233, 45.164))) * 43758.5453);
    float h2 = fract(sin(dot(cellId, vec3(63.7264, 10.873, 28.457))) * 43758.5453);
    float h3 = fract(sin(dot(cellId, vec3(36.1456, 93.284, 71.329))) * 43758.5453);
    vec3 rAxis = normalize(vec3(h1, h2, h3) * 2.0 - 1.0);
    float rAngle = h1 * maxAngle;
    return nestRotate(cellPos, rAxis, rAngle);
}

float smin_bool(float a, float b, float k) {
    if (k <= 0.0) return min(a, b);
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

float smax_bool(float a, float b, float k) {
    if (k <= 0.0) return max(a, b);
    float h = max(k - abs(a - b), 0.0) / k;
    return max(a, b) + h * h * k * 0.25;
}

float boolCombine(float d1, float d2) {
    if (boolOp == 1) return smin_bool(d1, d2, boolBlend);       // Union
    if (boolOp == 2) return smax_bool(d1, d2, boolBlend);       // Intersect
    if (boolOp == 3) return smax_bool(d1, -d2, boolBlend);      // Subtract
    if (boolOp == 5) return mix(d1, d2, clamp(boolBlend, 0.0, 1.0)); // Morph
    return d1;
}

float nestEvalCell(vec3 pos, float d1) {
    // Domain warp: break regular grid with noise displacement
    vec3 warpedPos = pos * nestRepeatScale;
    float warpAmt = 0.35;
    vec3 warpOff = vec3(
        fbmLow(pos * 2.0 + vec3(7.3, 0.0, 0.0)),
        fbmLow(pos * 2.0 + vec3(0.0, 11.7, 0.0)),
        fbmLow(pos * 2.0 + vec3(0.0, 0.0, 5.1))
    );
    warpedPos = warpedPos + warpOff * warpAmt * nestRepeatScale;

    vec3 cellId = floor(warpedPos);
    vec3 cellPos = fract(warpedPos) * 2.0 - 1.0;

    // Fade-out at cell edges: distance from center in [-1,1] cube
    float edgeDist = max(max(abs(cellPos.x), abs(cellPos.y)), abs(cellPos.z));
    float fade = 1.0 - smoothstep(0.6, 1.0, edgeDist);

    cellPos = nestCellRotate(cellPos, cellId, nestRotation);
    cellPos = cellPos / boolScale;
    float d_inner = b_DE_simple(cellPos) * boolScale / nestRepeatScale;

    // Blend: primary surface proximity + cell edge fade + global mix
    float t = smoothstep(nestThreshold, nestThreshold * 0.1, d1) * fade * nestMix;
    return mix(d1, d_inner, t);
}

vec3 boolRotateSecondary(vec3 p) {
    if (boolRotation.x != 0.0) p = nestRotate(p, vec3(1.0, 0.0, 0.0), boolRotation.x);
    if (boolRotation.y != 0.0) p = nestRotate(p, vec3(0.0, 1.0, 0.0), boolRotation.y);
    if (boolRotation.z != 0.0) p = nestRotate(p, vec3(0.0, 0.0, 1.0), boolRotation.z);
    return p;
}

float boolDE(vec3 pos, out OrbitTrap trap) {
    float d1 = DE(pos, trap);
    if (boolOp == 4) {
        if (d1 > nestThreshold) return d1;
        return nestEvalCell(pos, d1);
    }
    vec3 p2 = boolRotateSecondary((pos - boolOffset) / boolScale);
    float d2 = b_DE_simple(p2) * boolScale;
    return boolCombine(d1, d2);
}

float boolDE_simple(vec3 pos) {
    float d1 = DE_simple(pos);
    if (boolOp == 4) {
        if (d1 > nestThreshold) return d1;
        return nestEvalCell(pos, d1);
    }
    vec3 p2 = boolRotateSecondary((pos - boolOffset) / boolScale);
    float d2 = b_DE_simple(p2) * boolScale;
    return boolCombine(d1, d2);
}

// Mix coloring factors between primary and secondary for morph mode
vec3 morphFactors(vec3 pos, vec3 primaryFactors) {
    if (boolOp != 5) return primaryFactors;
    vec3 p2 = boolRotateSecondary((pos - boolOffset) / boolScale);
    b_OrbitTrap b_trap;
    b_DE(p2, b_trap);
    vec3 secondaryFactors = b_getFactors(b_trap);
    return mix(primaryFactors, secondaryFactors, clamp(boolBlend, 0.0, 1.0));
}
#endif

// ============================================================================
// Environment Distance Functions (Ocean & Floor)
// ============================================================================

float getOceanDE(vec3 pos) {
    if (oceanEnabled == 0) return 1e10;
    // Layered wave noise using deterministic oceanTime
    vec3 p = pos * oceanWaveScale;
    float timeMod = oceanTime * oceanSpeed;
    float wave = noise(p + vec3(timeMod, 0.0, timeMod * 0.5)) * 0.5;
    wave += noise(p * 2.1 + vec3(-timeMod * 0.7, 0.0, timeMod)) * 0.25;
    wave += noise(p * 4.3 + vec3(timeMod * 0.2, 0.0, -timeMod * 0.8)) * 0.125;
    return pos.y - (oceanHeight + wave * oceanWaveHeight);
}

const int MAT_FRACTAL = 0;
const int MAT_OCEAN = 1;

float sceneDE(vec3 pos, out OrbitTrap trap, out int matType) {
    matType = MAT_FRACTAL;
#ifdef BOOLEAN_OPS
    float d = boolDE(pos, trap);
#else
    float d = DE(pos, trap);
#endif

    // Apply displacements only to fractal
    if (erosionEnabled != 0 && d < erosionMaxDisplacement() + 0.1) d += getErosionDisplacement(pos);
    if (crystalEnabled != 0 && d < crystalMaxDisplacement() + 0.1) d += getCrystalDisplacement(pos);
    if (mossEnabled != 0 && d < mossMaxDisplacement() + 0.1) d += getMossDisplacement(pos);
    
    float dOcean = getOceanDE(pos);
    if (dOcean < d) {
        d = dOcean;
        matType = MAT_OCEAN;
    }
    
    return d;
}

float sceneDE_simple(vec3 pos) {
#ifdef BOOLEAN_OPS
    float d = boolDE_simple(pos);
#else
    float d = DE_simple(pos);
#endif
    if (erosionEnabled != 0 && d < erosionMaxDisplacement() + 0.1) d += getErosionDisplacementLight(pos);
    if (crystalEnabled != 0 && d < crystalMaxDisplacement() + 0.1) d += getCrystalDisplacementLight(pos);
    if (mossEnabled != 0 && d < mossMaxDisplacement() + 0.1) d += getMossDisplacementLight(pos);

    d = min(d, getOceanDE(pos));
    return d;
}

// ============================================================================
// Deep precision
// ============================================================================
// A float position near |pos| ~ 1 is quantised to ~1e-7 world units. Once the camera is
// closer to the surface than ~1e-4 the pixel footprint is below that, so every point the
// march evaluates, and the four points the normal is taken from, round to the same handful
// of values: blocky surfaces, normals that flicker, MIN_EPSILON spanning tens of pixels.
// The fix is to form the point in double and run the estimator in double: DE_deep, which
// DeepSource.java derives from the scene's own DE_simple and appends to the scene when deep
// precision is on (any scene; nothing here knows the fractal). The primary rays and their
// normals take this path; secondary rays (shadows, AO, bounces) leave from a float hit
// point a bias above the surface and stay in float.
//
// Compiled in only when the scene carries the double entry (HAS_DEEP_DE): a program
// without it is exactly what it was.
#ifdef HAS_DEEP_DE
uniform int deepPrecision;

// Floor of the hit epsilon in double. MIN_EPSILON is about ten float ulps at |pos| ~ 1, the
// accuracy a float position can have at all; a double position is good to ~1e-16, so the
// floor can drop to what the float distance returned by the estimator still resolves.
const float DEEP_MIN_EPSILON = 1.0e-15;

// The last hit found by the deep march, so calcNormal can take its differences in double
// when it is asked about that very point.
dvec3 gDeepHitPos = dvec3(0.0LF);
bool  gDeepHitValid = false;

float sceneDE_deep(dvec3 posD) {
    float d = DE_deep(posD);
    vec3 pos = vec3(posD);
    if (erosionEnabled != 0 && d < erosionMaxDisplacement() + 0.1) d += getErosionDisplacementLight(pos);
    if (crystalEnabled != 0 && d < crystalMaxDisplacement() + 0.1) d += getCrystalDisplacementLight(pos);
    if (mossEnabled != 0 && d < mossMaxDisplacement() + 0.1) d += getMossDisplacementLight(pos);
    d = min(d, getOceanDE(pos));
    return d;
}

/** Whether this ray may be marched in double: deep precision is on and the ray leaves from
 *  the camera (the one origin held in double). */
bool deepRay(Ray ray) {
    return deepPrecision != 0 && all(equal(ray.origin, gPrimaryOrigin));
}

// Whether a camera ray the float march ended at distance t, having come within minD of a
// surface on the way, needs the double one. Either where it lands a pixel spans fewer than
// ~100 float steps of the position (the image there would be blocks), or on the way it came
// within 4 float steps of a surface, where the float estimate is noise and the march may
// have stepped through a wall (a dive below 1e-7 did, DiveProbe). One float step is
// ~1.2e-7 of |camPos|. The float march goes first and the double one only redoes the rays
// that need it, so a scene with deep precision on costs what it did until the dive gets
// there. Decided per ray, not from the estimate at the camera alone: that is a lower bound,
// far too low near a Mandelbox (90 float steps where nothing is nearer than 0.17), and as
// the only test it sent whole views into the double march for nothing (PrecisionLab).
float gSimpleMinDE = 1e10;   // the least estimate rayMarchSimple met, for rayMarchSimpleCamera
bool deepNeeded(float t, float minD) {
    float ulp = 1.19e-7 * max(length(camPos), 1e-3);
    return pixelScaleAt(t) < 100.0 * ulp || minD < 4.0 * ulp;
}
#endif

// ============================================================================
// Normal Calculation (Tetrahedron Method with adaptive epsilon)
// ============================================================================

vec3 calcNormal(vec3 pos) {
    const vec3 k1 = vec3( 1.0, -1.0, -1.0);
    const vec3 k2 = vec3(-1.0, -1.0,  1.0);
    const vec3 k3 = vec3(-1.0,  1.0, -1.0);
    const vec3 k4 = vec3( 1.0,  1.0,  1.0);

    float distToCamera = length(pos - camPos);

    float e;
    if (pixelRadius > 0.0) {
        // Cone tracing: gradient epsilon scales with pixel footprint at this distance
        e = max(MIN_EPSILON, pixelRadius * distToCamera * 0.5);
    } else {
        float qualityEpsilon = baseEpsilon / max(1.0, qualityMultiplier);
        e = max(qualityEpsilon, distToCamera * 0.00005);
    }

#ifdef HAS_DEEP_DE
    // Asked about the point the deep march just found: take the differences in double,
    // with a step the footprint sets rather than the float floor.
    if (deepPrecision != 0 && gDeepHitValid && all(equal(pos, vec3(gDeepHitPos)))) {
        float distD = float(length(gDeepHitPos - camPosD));
        float ed = (pixelRadius > 0.0) ? max(DEEP_MIN_EPSILON, pixelRadius * distD * 0.5) : e;
        double eD = double(ed);
        return normalize(
            k1 * sceneDE_deep(gDeepHitPos + dvec3(k1) * eD) +
            k2 * sceneDE_deep(gDeepHitPos + dvec3(k2) * eD) +
            k3 * sceneDE_deep(gDeepHitPos + dvec3(k3) * eD) +
            k4 * sceneDE_deep(gDeepHitPos + dvec3(k4) * eD)
        );
    }
#endif

    return normalize(
        k1 * sceneDE_simple(pos + k1 * e) +
        k2 * sceneDE_simple(pos + k2 * e) +
        k3 * sceneDE_simple(pos + k3 * e) +
        k4 * sceneDE_simple(pos + k4 * e)
    );
}

// ============================================================================
// Soft Shadows
// ============================================================================

float calcShadow(vec3 ro, vec3 rd, float mint, float maxt) {
    float res = 1.0;
    float t = mint;

    int steps = int(float(shadowSteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    for (int i = 0; i < steps && t < maxt; i++) {
        vec3 shadowPos = ro + rd * t;
        float h = sceneDE_simple(shadowPos);

        float epsilon;
        if (pixelRadius > 0.0) {
            epsilon = max(MIN_EPSILON, pixelRadius * t);
        } else {
            epsilon = computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);
        }

        if (h < epsilon) return 0.0;

        res = min(res, shadowSoftness * h / t);
        
        // Don't clamp min step too aggressively, allows catching fine details
        // but ensure we progress at least epsilon
        t += max(h * fudgeFactor, epsilon * 2.0);
    }

    return clamp(res, 0.0, 1.0);
}

#ifdef HAS_DEEP_DE
// The sun's shadow from a point the deep march found, in double. A shadow ray from the float
// hit point starts up to a float step (~1e-7 of |pos|) from where the surface is; at a deep
// zoom that is the size of the view, so the ray starts under the surface and the scene goes
// dark (PrecisionLab: 36 % of the KIFS lit at 1e-2, 7 % at 1e-6). Near the surface the march
// runs in double; once it is clear of everything by 100 float steps (the float estimate then
// within 1 %), the float march is as good and cheaper, and takes over. 1000 steps gave the
// same shadows (99.9 % of pixels within 0.1) for 5-15 % more time.
float calcShadowDeep(dvec3 ro, vec3 rd, float mint, float maxt) {
    float res = 1.0;
    float t = mint;
    int steps = int(float(shadowSteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;
    float ulp = 1.19e-7 * max(length(camPos), 1e-3);
    dvec3 rdD = dvec3(rd);

    int i = 0;
    for (; i < steps && t < maxt; i++) {
        float h = sceneDE_deep(ro + rdD * double(t));
        float epsilon = (pixelRadius > 0.0) ? max(DEEP_MIN_EPSILON, pixelRadius * t)
                                            : computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);
        if (h < epsilon) return 0.0;
        res = min(res, shadowSoftness * h / t);
        t = t + max(h * fudgeFactor, epsilon * 2.0);
        if (h > 100.0 * ulp) break;         // clear of everything: float from here on
    }
    vec3 rof = vec3(ro);
    for (; i < steps && t < maxt; i++) {
        float h = sceneDE_simple(rof + rd * t);
        float epsilon = (pixelRadius > 0.0) ? max(MIN_EPSILON, pixelRadius * t)
                                            : computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);
        if (h < epsilon) return 0.0;
        res = min(res, shadowSoftness * h / t);
        t = t + max(h * fudgeFactor, epsilon * 2.0);
    }
    return clamp(res, 0.0, 1.0);
}
#endif

/** The sun's shadow at a hit point, the ray leaving a bias above it along the normal: from
 *  the deep march's own point, in double, when pos is that point (its float rounding). */
float hitShadow(vec3 pos, vec3 normal, float bias, vec3 light, float maxt) {
#ifdef HAS_DEEP_DE
    if (deepPrecision != 0 && gDeepHitValid && all(equal(pos, vec3(gDeepHitPos)))) {
        float b = surfaceBias(float(length(gDeepHitPos - camPosD)));
        return calcShadowDeep(gDeepHitPos + dvec3(normal) * double(b), light, b, maxt);
    }
#endif
    return calcShadow(pos + normal * bias, light, bias, maxt);
}

// ============================================================================
// Ambient Occlusion
// ============================================================================

// Probe radii follow the view instead of being pinned to world units. The
// original 0.01..0.13 range is right for the default framing but spans many
// screen-heights during a deep dive, which saturates the occlusion sum into a
// flat tint. Capping the outer radius to a fraction of the visible extent keeps
// the probes inside the structure actually on screen; the sum is length-scaled,
// so it is renormalised to the reference radius to keep AO strength unchanged.
const float AO_REF_RADIUS = 0.13;

#ifdef HAS_DEEP_DE
// Ambient occlusion around a point the deep march found, the probes placed in double and the
// radii taken from the distance to the camera in double (the float one is noise at depth).
float calcAODeep(dvec3 pos, vec3 normal, float distToCamera) {
    float ao = 0.0;
    float scale = 1.0;
    int steps = int(float(aoSteps) * qualityMultiplier);
    float maxR = min(AO_REF_RADIUS, 0.12 * viewScaleAt(distToCamera));
    float minR = maxR * (0.01 / AO_REF_RADIUS);
    dvec3 n = dvec3(normal);
    for (int i = 0; i < steps; i++) {
        float hr = minR + (maxR - minR) * float(i + 1) / float(steps);
        float dd = sceneDE_deep(pos + n * double(hr));
        ao = ao + (hr - dd) * scale;
        scale = scale * 0.6;
    }
    ao = ao * (AO_REF_RADIUS / max(maxR, 1e-30));
    return clamp(1.0 - aoIntensity * ao, 0.0, 1.0);
}
#endif

float calcAO(vec3 pos, vec3 normal) {
#ifdef HAS_DEEP_DE
    if (deepPrecision != 0 && gDeepHitValid && all(equal(pos, vec3(gDeepHitPos)))) {
        return calcAODeep(gDeepHitPos, normal, float(length(gDeepHitPos - camPosD)));
    }
#endif
    float ao = 0.0;
    float scale = 1.0;

    int steps = int(float(aoSteps) * qualityMultiplier);

    float maxR = min(AO_REF_RADIUS, 0.12 * viewScaleAt(length(pos - camPos)));
    float minR = maxR * (0.01 / AO_REF_RADIUS);

    for (int i = 0; i < steps; i++) {
        float hr = minR + (maxR - minR) * float(i + 1) / float(steps);
        vec3 aoPos = pos + normal * hr;
        float dd = sceneDE_simple(aoPos);
        ao += (hr - dd) * scale;
        scale *= 0.6;
    }

    ao *= AO_REF_RADIUS / max(maxR, 1e-9);

    return clamp(1.0 - aoIntensity * ao, 0.0, 1.0);
}

// ============================================================================
// Subsurface Scattering Approximation
// ============================================================================

// Returns per-channel subsurface transmittance. Red light scatters deeper than
// blue in organic media, so each channel uses a different absorption coefficient
// (centred on the previous scalar 8.0 to preserve overall intensity). The result
// is a subtle warm tint in thin, back-lit regions.
vec3 calcSSS(vec3 pos, vec3 normal, vec3 lightDir) {
    float thickness = 0.0;
    float step = sssRadius / 5.0;
    for (int i = 1; i <= 5; i++) {
        float expectedDist = float(i) * step;
        vec3 sssPos = pos - normal * expectedDist;
        float actualDist = sceneDE_simple(sssPos);
        thickness += max(0.0, expectedDist - actualDist);
    }
    // Wrap lighting: light coming from behind illuminates thin areas
    float wrap = max(0.0, (dot(-normal, lightDir) + 0.5) / 1.5);
    vec3 absorption = vec3(6.0, 8.0, 11.2); // R penetrates deepest, B shallowest
    return wrap * exp(-thickness * absorption);
}

// ============================================================================
// Ray Marching
// ============================================================================

// Surface refinement: bisects the last step interval to find precise surface
// Uses two strategies depending on whether the DE is a true SDF (goes negative
// inside) or a fractal DE (always non-negative). Without this distinction,
// fractal DEs never satisfy d < 0, so the binary search does nothing.
vec3 refineSurface(vec3 ro, vec3 rd, float hitDist, float lastStep) {
    float lo = hitDist - lastStep;
    float hi = hitDist;

    // Detect true SDF vs fractal DE: check sign at the hit point
    float dHi = sceneDE_simple(ro + rd * hi);

    if (dHi < 0.0) {
        // True SDF: binary search for zero crossing
        for (int i = 0; i < refinementSteps; i++) {
            float mid = (lo + hi) * 0.5;
            float d = sceneDE_simple(ro + rd * mid);
            if (d < 0.0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
    } else {
        // Non-negative DE (fractal): ternary search to minimize DE
        // The DE is ~V-shaped along the ray (decreases toward surface, increases past it).
        // Ternary search converges to the minimum, i.e. the true surface.
        for (int i = 0; i < refinementSteps; i++) {
            float m1 = lo + (hi - lo) / 3.0;
            float m2 = hi - (hi - lo) / 3.0;
            float d1 = sceneDE_simple(ro + rd * m1);
            float d2 = sceneDE_simple(ro + rd * m2);
            if (d1 < d2) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
    }

    return ro + rd * ((lo + hi) * 0.5);
}

// The scene's estimate at the first point of the last primary march, the camera: what the
// probe reports as the camera's clearance (main). Kept here rather than asked of the scene
// once more because every call site of the estimator is another copy of it to compile.
float gMarchStartDE = -1.0;

#ifdef HAS_DEEP_DE
// ---------------------------------------------------------------------------
// Deep-precision march: the same algorithm as rayMarch / rayMarchSimple with the point
// formed in double and the estimator entered in double. t itself stays a double too; the
// epsilon floor is DEEP_MIN_EPSILON instead of MIN_EPSILON. No compound assignment on a
// double here: NVIDIA's compiler dies on some of them (C9999 "assign+", the Polyhedral IFS).
// ---------------------------------------------------------------------------

/** refineSurface in double: ternary search on the (non-negative) DE along the ray. */
double refineSurfaceDeep(dvec3 ro, dvec3 rd, double hitDist, double lastStep) {
    double lo = hitDist - lastStep;
    double hi = hitDist;
    float dHi = sceneDE_deep(ro + rd * hi);
    if (dHi < 0.0) {
        for (int i = 0; i < refinementSteps; i++) {
            double mid = (lo + hi) * 0.5LF;
            float d = sceneDE_deep(ro + rd * mid);
            if (d < 0.0) hi = mid; else lo = mid;
        }
    } else {
        for (int i = 0; i < refinementSteps; i++) {
            double m1 = lo + (hi - lo) / 3.0LF;
            double m2 = hi - (hi - lo) / 3.0LF;
            float d1 = sceneDE_deep(ro + rd * m1);
            float d2 = sceneDE_deep(ro + rd * m2);
            if (d1 < d2) hi = m2; else lo = m1;
        }
    }
    return (lo + hi) * 0.5LF;
}

float deepEpsilon(float t, float qualityEpsilon) {
    if (pixelRadius > 0.0) return max(DEEP_MIN_EPSILON, pixelRadius * t);
    return computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);
}

/** The deep march with orbit traps: traps and matType are taken from the float DE at the
 *  final point (colouring tolerates the rounding; the geometry does not). */
RayHit rayMarchDeep(Ray ray) {
    RayHit result;
    result.hit = false;
    result.dist = 0.0;
    result.minDist = 1e10;
    result.steps = 0;

    dvec3 ro = camPosD + dvec3(gLensOffset);
    dvec3 rd = dvec3(ray.direction);
    double t = 0.0LF;

    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    float prevD = 1e10;
    float omega = 1.0 + stepRelaxation;
    double lastStep = 0.0LF;

    dvec3 p = ro;
    for (int i = 0; i < effectiveMaxSteps; i++) {
        p = ro + rd * t;
        float d = sceneDE_deep(p);
        if (i == 0) gMarchStartDE = d;
        result.minDist = min(result.minDist, d);
        result.steps = i + 1;

        if (d < deepEpsilon(float(t), qualityEpsilon)) {
            result.hit = true;
            break;
        }

        float baseStep = computeStep(d, qualityMultiplier, STEP_FACTOR) * fudgeFactor;
        float step;
        if (stepRelaxation > 0.0) {
            float candidateStep = baseStep * omega;
            if (prevD + d < candidateStep) {
                t = t - lastStep;
                step = baseStep;
                omega = 1.0;
            } else {
                step = candidateStep;
            }
        } else {
            step = baseStep;
        }

        prevD = d;
        lastStep = double(step);
        t = t + lastStep;

        if (t > double(MAX_DISTANCE)) break;
    }

    if (result.hit && refinementSteps > 0 && lastStep > 0.0LF) {
        t = refineSurfaceDeep(ro, rd, t, lastStep);
        p = ro + rd * t;
    }

    if (result.hit && pixelRadius > 0.0) {
        for (int i = 0; i < 6; i++) {
            float d = sceneDE_deep(p);
            if (d < DEEP_MIN_EPSILON) break;
            p = p + rd * double(d * STEP_FACTOR);
        }
    }

    result.pos = vec3(p);
    result.dist = float(length(p - ro));
    if (result.hit) {
        gDeepHitPos = p;
        gDeepHitValid = true;
        sceneDE(result.pos, result.trap, result.matType);
    }
    return result;
}

/** The deep march without traps (the path tracer's camera ray). */
bool rayMarchSimpleDeep(Ray ray, out vec3 hitPos, out float hitDist, out int matType) {
    dvec3 ro = camPosD + dvec3(gLensOffset);
    dvec3 rd = dvec3(ray.direction);
    double t = 0.0LF;

    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;
    OrbitTrap dummyTrap;
    matType = MAT_FRACTAL;

    float prevD = 1e10;
    float omega = 1.0 + stepRelaxation;
    double lastStep = 0.0LF;

    for (int i = 0; i < effectiveMaxSteps; i++) {
        dvec3 p = ro + rd * t;
        float d = sceneDE_deep(p);

        if (d < deepEpsilon(float(t), qualityEpsilon)) {
            if (refinementSteps > 0 && lastStep > 0.0LF) {
                t = refineSurfaceDeep(ro, rd, t, lastStep);
                p = ro + rd * t;
            }
            if (pixelRadius > 0.0) {
                for (int j = 0; j < 6; j++) {
                    float ds = sceneDE_deep(p);
                    if (ds < DEEP_MIN_EPSILON) break;
                    p = p + rd * double(ds * STEP_FACTOR);
                }
            }
            hitPos = vec3(p);
            hitDist = float(length(p - ro));
            gDeepHitPos = p;
            gDeepHitValid = true;
            // The material is decided by the float scene DE at the point, as rayMarchSimple does.
            sceneDE(hitPos, dummyTrap, matType);
            return true;
        }

        float baseStep = d * STEP_FACTOR * fudgeFactor;
        float step;
        if (stepRelaxation > 0.0) {
            float candidateStep = baseStep * omega;
            if (prevD + d < candidateStep) {
                t = t - lastStep;
                step = baseStep;
                omega = 1.0;
            } else {
                step = candidateStep;
            }
        } else {
            step = baseStep;
        }
        prevD = d;
        lastStep = double(step);
        t = t + lastStep;

        if (t > double(MAX_DISTANCE)) break;
    }
    hitDist = float(t);
    return false;
}
#endif

RayHit rayMarch(Ray ray) {
    RayHit result;
    result.hit = false;
    result.dist = 0.0;
    result.minDist = 1e10;
    result.steps = 0;

    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    float prevD = 1e10;
    float omega = 1.0 + stepRelaxation;
    float lastStep = 0.0;

    for (int i = 0; i < effectiveMaxSteps; i++) {
        result.pos = ray.origin + ray.direction * result.dist;

        float d = sceneDE(result.pos, result.trap, result.matType);
        if (i == 0) gMarchStartDE = d;
        result.minDist = min(result.minDist, d);
        result.steps = i + 1;

        // Epsilon: cone tracing (pixel-aware) or legacy adaptive
        float epsilon;
        if (pixelRadius > 0.0) {
            epsilon = max(MIN_EPSILON, pixelRadius * result.dist);
        } else {
            epsilon = computeAdaptiveEpsilon(result.dist, qualityEpsilon, qualityMultiplier);
        }

        if (d < epsilon) {
            result.hit = true;
            break;
        }

        float baseStep = computeStep(d, qualityMultiplier, STEP_FACTOR) * fudgeFactor;

        // Step relaxation (Keinert 2014): take larger steps, backstep on overshoot
        float step;
        if (stepRelaxation > 0.0) {
            float candidateStep = baseStep * omega;
            if (prevD + d < candidateStep) {
                // Overshoot detected: backstep and reset to conservative stepping
                result.dist -= lastStep;
                step = baseStep;
                omega = 1.0;
            } else {
                step = candidateStep;
            }
        } else {
            step = baseStep;
        }

        prevD = d;
        lastStep = step;
        result.dist += step;

        if (result.dist > MAX_DISTANCE) break;
    }

    // Surface refinement via binary/ternary search
    if (result.hit && refinementSteps > 0 && lastStep > 0.0) {
        result.pos = refineSurface(ray.origin, ray.direction, result.dist, lastStep);
        result.dist = length(result.pos - ray.origin);
    }

    // Cone tracing converges to true surface: the cone epsilon is large
    // (pixelRadius * dist), so the march stops far from the surface.
    // A few sphere-tracing steps close the gap for accurate orbit traps.
    // Each step reduces distance by ~10x (STEP_FACTOR=0.9), so 6 steps
    // go from DE≈0.003 to DE≈3e-9.
    if (result.hit && pixelRadius > 0.0) {
        for (int i = 0; i < 6; i++) {
            float d = sceneDE_simple(result.pos);
            if (d < MIN_EPSILON) break;
            result.pos += ray.direction * d * STEP_FACTOR;
        }
        result.dist = length(result.pos - ray.origin);
    }

    // Re-evaluate orbit traps at final position
    if (result.hit && (refinementSteps > 0 || pixelRadius > 0.0)) {
        sceneDE(result.pos, result.trap, result.matType);
    }

#ifdef HAS_DEEP_DE
    if (deepRay(ray) && deepNeeded(result.dist, result.minDist)) return rayMarchDeep(ray);
#endif
    return result;
}

// ============================================================================
// Volumetric Fog Helper
// ============================================================================

#ifdef EXTRA_BEAM
// defined below: the beam's frame and the marcher its glow in the fog needs
vec3 getExtraLightAxisWS();
vec3 getExtraLightPositionWS();
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist, out int matType);

// The beam's path: from its source along its axis to the first surface, and on past every
// perfect mirror it meets (a metal of near-zero roughness reflects a beam into a beam), up
// to BEAM_SEGMENTS segments, one march each per pixel, lazily. Each segment knows where it
// starts, its direction and length, the surface that ends it, what the mirrors before it
// let through, and the beam's length before it (the fog's toll on the beam itself). The
// beam ends on the surface it hits, not on a disc across the axis: a fibre at offset off
// from the axis ends where it meets the plane of that surface through the axis's hit, at
// t = len - dot(off, n) / dot(axis, n) (cut as a disc, a beam into a slanted face stopped
// short on one side and went into the glass on the other). Past a glass, a rough metal or
// a matte surface the beam is no longer a beam: that light is the photon pass's, which for
// that reason lets its beam photons scatter in the fog only past such a vertex.
const int BEAM_SEGMENTS = 4;
int gSegCount = -1;
vec3 gSegO[BEAM_SEGMENTS], gSegD[BEAM_SEGMENTS], gSegN[BEAM_SEGMENTS], gSegPow[BEAM_SEGMENTS];
float gSegLen[BEAM_SEGMENTS], gSegStart[BEAM_SEGMENTS];
int gBeamHitMat = -1;              // the material type ending the last segment (MATERIAL_GLASS, ...), -1 for none

// The material at a surface point: the node's when the graph gives it one, else the global.
void materialAt(vec3 p, out int type, out float roughnessOut, out vec3 albedoOut) {
    OrbitTrap trap;
    DE(p, trap);
    type = materialType; roughnessOut = roughness; albedoOut = vec3(1.0);
#ifdef HAS_MATERIALS
    if (trap.matId >= 0) {
        MaterialData mat = materials[trap.matId];
        if (int(mat.type) >= 0) type = int(mat.type);
        if (mat.roughness >= 0.0) roughnessOut = mat.roughness;
        if (int(mat.colorMode) == 1) albedoOut = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
    }
#endif
}

void beamPath() {
    if (gSegCount >= 0) return;
    gSegCount = 0;
    vec3 o = getExtraLightPositionWS();
    vec3 d = getExtraLightAxisWS();
    vec3 pw = vec3(1.0);
    float start = 0.0;
    float range = max(extraLightRange, 0.0001);
    for (int i = 0; i < BEAM_SEGMENTS; i++) {
        Ray along;
        along.origin = o;
        along.direction = d;
        vec3 fp; float fd; int fm;
        bool hit = rayMarchSimple(along, fp, fd, fm) && fd < range - start;
        gSegO[i] = o; gSegD[i] = d; gSegPow[i] = pw; gSegStart[i] = start;
        gSegLen[i] = hit ? fd : range - start;
        gSegN[i] = hit ? calcNormal(fp) : vec3(0.0);
        gSegCount = i + 1;
        if (!hit || fm == MAT_OCEAN) { gBeamHitMat = -1; return; }
        int type; float rough; vec3 alb;
        materialAt(fp, type, rough, alb);
        gBeamHitMat = type;
        if (type != MATERIAL_METALLIC || rough > MIRROR_ROUGHNESS) return;
        // a mirror: the beam goes on, reflected, with what the metal keeps (F0 = its colour)
        vec3 n = gSegN[i];
        if (dot(d, n) > 0.0) n = -n;
        pw *= fresnelSchlickVec(max(dot(-d, n), 0.0), alb);
        start += fd;
        o = fp + n * 0.005;
        d = normalize(reflect(d, n));
    }
}

// A cylinder of the beam's radius from o along axis, over [0, len], ending on the surface
// of normal n (zero for none), lit with the beam's soft edge, seen along [0, segLen] of a
// ray through a medium of density sigma: the phase-weighted chord integral over eight
// steps with the medium's extinction from the ray's origin and along the beam from its
// source (before + t). The caller supplies the light, the medium's albedo and sigma.
float cylinderGlow(vec3 rayO, vec3 rayD, float segLen, vec3 o, vec3 axis, float len, vec3 n, float before, float sigma) {
    float radius = max(extraLightAreaRadius, 1e-4);
    vec3 w = rayO - o;
    vec3 wPerp = w - axis * dot(w, axis);
    vec3 dPerp = rayD - axis * dot(rayD, axis);
    float qa = dot(dPerp, dPerp), qb = 2.0 * dot(wPerp, dPerp), qc = dot(wPerp, wPerp) - radius * radius;
    if (qa <= 1e-8) return 0.0;
    float disc = qb * qb - 4.0 * qa * qc;
    if (disc <= 0.0) return 0.0;
    float sq = sqrt(disc);
    float d0 = max((-qb - sq) / (2.0 * qa), 0.0);
    float d1 = min((-qb + sq) / (2.0 * qa), segLen);
    // along the axis the point at d is at t(d) = t0 + d * ta: keep 0 < t < the end, which
    // for a slanted end surface lies up to a radius beyond len on one side
    float t0 = dot(w, axis), ta = dot(rayD, axis);
    float dn = dot(axis, n);
    float slant = abs(dn) > 1e-3 ? radius * length(n - axis * dn) / abs(dn) : 0.0;
    float tEnd = len + min(slant, 4.0 * radius);
    if (abs(ta) > 1e-6) {
        float e0 = (0.0 - t0) / ta, e1 = (tEnd - t0) / ta;
        d0 = max(d0, min(e0, e1));
        d1 = min(d1, max(e0, e1));
    } else if (t0 <= 0.0 || t0 >= tEnd) {
        d1 = d0;
    }
    if (d1 <= d0) return 0.0;
    float edge = clamp(extraLightConeSoftness, 0.0, 1.0);
    float phase = phaseHG(dot(rayD, -axis), fogScattering);
    const int GLOW_STEPS = 8;
    float ds = (d1 - d0) / float(GLOW_STEPS);
    float acc = 0.0;
    for (int i = 0; i < GLOW_STEPS; i++) {
        float d = d0 + (float(i) + 0.5) * ds;
        vec3 rel = rayO + rayD * d - o;
        float t = dot(rel, axis);
        vec3 off = rel - axis * t;
        float r = length(off);
        float tCut = abs(dn) > 1e-3 ? len - dot(off, n) / dn : len;   // this fibre's end
        if (t > tCut) continue;
        float att = edge < 0.001 ? 1.0 : 1.0 - smoothstep(radius * (1.0 - edge), radius, r);
        acc += att * exp(-(d + before + t) * sigma) * ds;
    }
    return phase * acc;
}

// The beam's light scattered by the fog along [0, segLen] of a ray, every segment of its
// path. On the camera ray it is the fog march's term; on a segment after a bounce it is
// what a face of glass or a mirror reflects or refracts of the beam, and what lights a
// matte surface near it (pathTrace).
vec3 beamInScatter(Ray ray, float segLen) {
    beamPath();
    vec3 sum = vec3(0.0);
    for (int i = 0; i < gSegCount; i++)
        sum += gSegPow[i] * cylinderGlow(ray.origin, ray.direction, segLen, gSegO[i], gSegD[i], gSegLen[i], gSegN[i], gSegStart[i], fogDensity);
    return extraLightColor * extraLightIntensity * fogColor * sum * fogDensity;
}

// The halo: the beam's light the fog scatters out of it, scattered once more toward the
// eye. The glowing beam is a line source: per unit length the fog inside it scatters
// sigma * E * pi r^2 into the phase function. One scatter point is drawn along the camera
// ray (its length weighted by the transmittance), one point on the beam's path by the
// equiangular rule about it (the point source's 1/d^2 drawn exactly), the one march between
// them is the shadow, and the estimate is unbiased; it converges with the samples like the
// rest of the path tracer. Points inside the beam are the single scattering's already, so
// the distance is kept at a radius at least.
vec3 beamHalo(Ray ray, float segLen, inout uint seed) {
    beamPath();
    if (gSegCount <= 0) return vec3(0.0);
    float sigma = fogDensity;
    float D = min(segLen, 60.0);
    float A = 1.0 - exp(-sigma * D);
    if (A <= 1e-5) return vec3(0.0);
    float s = -log(1.0 - random(seed) * A) / sigma;
    vec3 x = ray.origin + ray.direction * s;
    float wS = A / sigma;                                           // T(s) / pdf(s)
    float total = 0.0;
    for (int i = 0; i < gSegCount; i++) total += gSegLen[i];
    if (total <= 0.0) return vec3(0.0);
    float pick = random(seed) * total;
    int seg = 0;
    float before = 0.0;
    for (int i = 0; i < gSegCount; i++) { if (pick < before + gSegLen[i] || i == gSegCount - 1) { seg = i; break; } before += gSegLen[i]; }
    float pSeg = gSegLen[seg] / total;
    vec3 o = gSegO[seg], a = gSegD[seg];
    float L = gSegLen[seg];
    float t0 = dot(x - o, a);
    vec3 foot = o + a * t0;
    float h = max(length(x - foot), 1e-3);
    float thA = atan((0.0 - t0) / h), thB = atan((L - t0) / h);
    if (thB - thA < 1e-5) return vec3(0.0);
    float th = mix(thA, thB, random(seed));
    float t = clamp(t0 + h * tan(th), 0.0, L);
    float pdfT = h / ((thB - thA) * (h * h + (t - t0) * (t - t0)));
    vec3 p = o + a * t;
    vec3 toX = x - p;
    float radius = max(extraLightAreaRadius, 1e-4);
    float d2 = max(dot(toX, toX), radius * radius);
    float d = sqrt(d2);
    toX = normalize(toX);
    Ray sh;
    sh.origin = p;
    sh.direction = toX;
    vec3 hp; float hd; int hm;
    if (rayMarchSimple(sh, hp, hd, hm) && hd < d - 0.01) return vec3(0.0);
    vec3 E = extraLightColor * extraLightIntensity * gSegPow[seg] * exp(-(gSegStart[seg] + t) * sigma);
    vec3 I = sigma * fogColor * E * (PI * radius * radius) * phaseHG(dot(a, toX), fogScattering);   // toward x, per unit length
    vec3 L1 = I * exp(-sigma * d) / d2;                                                              // at x
    vec3 L2 = sigma * fogColor * phaseHG(dot(ray.direction, -toX), fogScattering) * L1;             // toward the eye
    return L2 * wS / (pdfT * pSeg);
}

// The beam inside a hazy glass its path ends in, seen along a path's segment inside that
// glass: the beam's axis refracted at the entry for this path's index (the fan opens inside
// the glass, wavelength by wavelength), its length to the exit, and the chord integral with
// the haze as the medium (its albedo white, the fog's phase). A cylinder along the refracted
// axis is right for a flat face and a sketch for a curved one. What enters is what the fog
// and the mirrors let through and the entry face did not reflect.
vec3 hazeInScatter(vec3 rayO, vec3 rayD, float segLen, float ior) {
    beamPath();
    int last = gSegCount - 1;
    if (last < 0 || gBeamHitMat != MATERIAL_GLASS || dot(gSegN[last], gSegN[last]) < 0.5) return vec3(0.0);
    vec3 axis = gSegD[last];
    vec3 o = gSegO[last] + axis * gSegLen[last];
    vec3 n = gSegN[last];
    if (dot(axis, n) > 0.0) n = -n;
    vec3 gAxis;
    if (!refractRay(axis, n, 1.0 / ior, gAxis)) return vec3(0.0);
    gAxis = normalize(gAxis);
    vec3 power = gSegPow[last] * (1.0 - fresnelDielectric(max(dot(-axis, n), 0.0), ior));
    if (volumetricFogEnabled != 0 && fogDensity > 0.0) power *= exp(-(gSegStart[last] + gSegLen[last]) * fogDensity);
    // the beam's length inside: the glass traversal's march along its axis
    float gLen = 0.02;
    for (int gs = 0; gs < 128; gs++) {
        float d = sceneDE_simple(o + gAxis * gLen);
        if (d > 0.0) { gLen -= d; break; }
        gLen += max(abs(d), 0.002);
        if (gLen > 10.0) break;
    }
    vec3 exitN = calcNormal(o + gAxis * gLen);
    float glow = cylinderGlow(rayO, rayD, segLen, o, gAxis, gLen, exitN, 0.0, glassHaze);
    return extraLightColor * extraLightIntensity * power * glow * glassHaze;
}
#endif

vec3 computeVolumetricFog(Ray ray, float hitDist, vec3 surfaceColor, out float extinction) {
    extinction = 1.0;
    if (volumetricFogEnabled == 0 || fogDensity <= 0.0) return surfaceColor;

    float volAccum = 0.0;
    float stepSize = hitDist / float(fogSteps);
    vec3 light = normalize(lightDir);
    float phase = phaseHG(dot(ray.direction, light), fogScattering);
    
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);
    float offset = random(seed) * stepSize;
    
    for (int i = 0; i < fogSteps; i++) {
        float d = (float(i) + 0.5) * stepSize + offset;
        if (d > hitDist) break;
        
        vec3 p = ray.origin + ray.direction * d;
        float shadowBias = surfaceBias(d);
        float sh = calcShadow(p, light, shadowBias, 10.0);
        volAccum += sh * exp(-d * fogDensity) * stepSize;
    }
    
    extinction = exp(-hitDist * fogDensity);
    vec3 volumetricLight = lightColor * lightIntensity * fogColor * phase * volAccum * fogDensity;

#ifdef EXTRA_BEAM
    // The beam in the fog along the camera ray (beamInScatter), and its halo when asked
    // (beamHalo). Its light through a glass is the photon pass's (photon.glsl), not this.
    if (extraLightType == EXTRA_LIGHT_BEAM && extraLightIntensity > 0.0) {
        volumetricLight += beamInScatter(ray, hitDist);
        if (fogHalo != 0) volumetricLight += beamHalo(ray, hitDist, seed);
    }
#endif

    return surfaceColor * extinction + volumetricLight;
}

// ============================================================================
// Shading
// ============================================================================

// Forward declaration (defined in Path Tracing section below)
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist, out int matType);

vec3 getExtraLightAxisWS() {
    if (extraLightAttachToCamera == 0) {
        // Fixed in the world: the direction as given.
        return dot(extraLightDir, extraLightDir) < 1e-8 ? vec3(0.0, 0.0, 1.0) : normalize(extraLightDir);
    }
    // Dampen lateral direction controls to keep spot steering predictable.
    vec3 localDir = vec3(extraLightDir.x * 0.2, extraLightDir.y * 0.2, extraLightDir.z);
    if (dot(localDir, localDir) < 1e-8) {
        localDir = vec3(0.0, 0.0, 1.0);
    }
    return normalize(rotateByQuaternion(localDir, camQuat));
}

vec3 getExtraLightPositionWS() {
    if (extraLightAttachToCamera == 0) return extraLightPos;   // fixed in the world, in scene units
    // Camera-local offset intentionally scaled down for finer positioning.
    return camPos + rotateByQuaternion(extraLightPos * 0.1, camQuat);
}

float calcExtraLightVisibility(vec3 hitPos, vec3 normal, vec3 lightDirNorm, float maxDistance, float surfaceDist) {
    Ray shadowRay;
    float shadowBias = surfaceBias(surfaceDist);
    shadowRay.origin = hitPos + normal * shadowBias;
    shadowRay.direction = lightDirNorm;
    vec3 shPos; float shDist; int shMat;

    if (!rayMarchSimple(shadowRay, shPos, shDist, shMat)) {
        return 1.0;
    }

    // No self-hit allowance: the origin already sits a bias above the surface, and a hit
    // within two biases was let through, which lit the floor at the foot of a box a beam
    // grazed along (a bright wedge at the corner, as if the beam went through the box).
    // Directional light: any blocker means shadowed.
    if (maxDistance <= 0.0) {
        return 0.0;
    }

    // Local light: blocker only matters if it's between point and light.
    return (shDist >= maxDistance - shadowBias) ? 1.0 : 0.0;
}

vec3 randomUnitSphere(inout uint seed) {
    float z = random(seed) * 2.0 - 1.0;
    float a = random(seed) * TAU;
    float r = sqrt(max(0.0, 1.0 - z * z));
    return vec3(r * cos(a), r * sin(a), z);
}

// Jitter a direction within a cone for stochastic soft shadows in path tracing.
// angularRadius controls the half-angle of the virtual sun disc.
// shadowSoftness 1..64 maps to ~0.05°..~5.5° cone.
vec3 jitterLightDir(vec3 dir, inout uint seed, float softness) {
    float angularRadius = softness * 0.0015;
    if (angularRadius < 0.0002) return dir;

    float r1 = random(seed);
    float r2 = random(seed);
    float cosHalf = cos(angularRadius);
    float cosTheta = 1.0 - r1 * (1.0 - cosHalf);
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    float phi = TAU * r2;

    // Build orthonormal basis around dir
    vec3 w = dir;
    vec3 u = normalize(cross(abs(w.y) < 0.999 ? vec3(0,1,0) : vec3(1,0,0), w));
    vec3 v = cross(w, u);

    return normalize(sinTheta * cos(phi) * u + sinTheta * sin(phi) * v + cosTheta * w);
}

vec3 sampleExtraLightRadiance(vec3 hitPos, vec3 normal, float surfaceDist, inout uint seed, out vec3 lightDirNorm) {
    lightDirNorm = vec3(0.0);

    if (extraLightType == EXTRA_LIGHT_OFF || extraLightIntensity <= 0.0) {
        return vec3(0.0);
    }

    // Directional mode is intentionally disabled for this additional light workflow.
    if (extraLightType == EXTRA_LIGHT_DIRECTIONAL) {
        return vec3(0.0);
    }

    vec3 baseRadiance = extraLightColor * extraLightIntensity;

#ifdef EXTRA_BEAM
    if (extraLightType == EXTRA_LIGHT_BEAM) {
        // A collimated beam: the irradiance is the intensity inside a cylinder of the area
        // radius around the axis, from the position to the range, with the cone softness
        // as the width of its soft edge; no falloff. Lit if nothing sits between the point
        // and the beam's source plane.
        vec3 axis = getExtraLightAxisWS();
        vec3 rel = hitPos - getExtraLightPositionWS();
        float t = dot(rel, axis);
        if (t <= 0.0 || t >= max(extraLightRange, 0.0001)) return vec3(0.0);
        float radius = max(extraLightAreaRadius, 1e-4);
        float r = length(rel - axis * t);
        if (r >= radius) return vec3(0.0);
        float edge = clamp(extraLightConeSoftness, 0.0, 1.0);
        float att = edge < 0.001 ? 1.0 : 1.0 - smoothstep(radius * (1.0 - edge), radius, r);
        if (volumetricFogEnabled != 0 && fogDensity > 0.0) att *= exp(-t * fogDensity);   // the fog on the beam's way here (the photon pass carries the same)
        lightDirNorm = -axis;
        float visibility = calcExtraLightVisibility(hitPos, normal, -axis, t, surfaceDist);
        return baseRadiance * (att * visibility);
    }
#endif

    vec3 lightPos = getExtraLightPositionWS();
    float areaRadius = max(extraLightAreaRadius, 0.0);
    if (areaRadius > 0.0) {
        // Sample point on a spherical emitter to create physically soft penumbra.
        lightPos += randomUnitSphere(seed) * areaRadius;
    }
    vec3 toLight = lightPos - hitPos;
    float distSq = dot(toLight, toLight);
    if (distSq < 1e-8) {
        return vec3(0.0);
    }

    float dist = sqrt(distSq);
    float range = max(extraLightRange, 0.0001);
    if (dist >= range) {
        return vec3(0.0);
    }

    lightDirNorm = toLight / dist;

    // Stronger near-field attenuation: range control is now much tighter.
    float normalizedDist = dist / range;
    float falloff = 1.0 / (1.0 + normalizedDist * normalizedDist * 8.0);
    float rangeAtt = pow(max(1.0 - normalizedDist, 0.0), 3.0);

    float spotAtt = 1.0;
    if (extraLightType == EXTRA_LIGHT_SPOT) {
        vec3 spotDir = getExtraLightAxisWS();
        vec3 lightToHit = -lightDirNorm;
        float cosTheta = dot(lightToHit, spotDir);
        float outerAngle = clamp(extraLightConeAngle, 1.0, 89.0);
        float softness = clamp(extraLightConeSoftness, 0.0, 1.0);
        float innerAngle = mix(outerAngle, 0.0, softness);
        float cosOuter = cos(radians(outerAngle));
        float cosInner = cos(radians(innerAngle));

        if (softness < 0.001) {
            spotAtt = (cosTheta >= cosOuter) ? 1.0 : 0.0;
        } else {
            spotAtt = smoothstep(cosOuter, cosInner, cosTheta);
        }

        if (spotAtt <= 0.0) {
            return vec3(0.0);
        }
    }

    float visibility = calcExtraLightVisibility(hitPos, normal, lightDirNorm, dist, surfaceDist);
    return baseRadiance * (falloff * rangeAtt * spotAtt * visibility);
}

// Simplified shading for reflection bounce (no recursion, no volumetrics)
#ifdef HAS_EMITTERS
// A point on one of the scene's emitters (lights.glsl), seen from hitPos: one emitter
// drawn by power, one point on it, the shadow march has to reach it. pdfArea is the
// density of the draw over the emitter's surface, cosL the emitter's cosine, so a caller
// adds f * Le * NdotL * cosL / (dist^2 * pdfArea), weighted against its own BSDF finding
// the same point (powerHeuristic with the BSDF's area density, bsdfPdf * cosL / dist^2).
struct EmitterSample { vec3 wi; vec3 Le; float dist; float cosL; float pdfArea; int li; bool valid; };

EmitterSample sampleEmitter(vec3 hitPos, vec3 faceNormal, float surfaceDist, inout uint seed) {
    EmitterSample s;
    s.valid = false;
    if (lightCount == 0 || emitterPower <= 0.0) return s;
    float pick;
    int li = pickEmitter(seed, pick);
    if (li < 0) return s;
    vec3 lp, ln;
    sampleEmitterPoint(li, seed, lp, ln);
    vec3 toL = lp - hitPos;
    float d2 = dot(toL, toL);
    float d = sqrt(d2);
    vec3 wi = toL / d;
    float cosL = dot(ln, -wi);
    if (cosL <= 0.0 || dot(faceNormal, wi) <= 0.0) return s;
    Ray sr;
    sr.origin = hitPos + faceNormal * surfaceBias(surfaceDist);
    sr.direction = wi;
    vec3 sp; float sd; int sm;
    // The march stops a cone epsilon short of whatever it meets, further along the ray
    // at grazing incidence: it reached the emitter when it stopped near the point drawn.
    if (rayMarchSimple(sr, sp, sd, sm) && length(sp - lp) > 0.05 + 0.03 * d) return s;
    s.wi = wi; s.dist = d; s.Le = emitterRadiance(li); s.cosL = cosL;
    s.pdfArea = pick * emitterPdfArea(li);
    s.li = li;
    s.valid = true;
    return s;
}
#endif

#ifdef BIDIR
// The photon pass's density of x being the first hit of a photon from the sun or the beam,
// in area measure: the light's pick, the draw over the square or the disk, projected.
float ltDensitySun(vec3 x, vec3 n) {
    int li = lightOfType(LIGHT_SUN);
    if (li < 0) return 0.0;
    vec3 L, u, v;
    sunFrame(L, u, v);
    float c = abs(dot(n, L));
    if (c < 1e-4) return 0.0;
    return lightPickPdf(li) * sunSquareDensity(sunSquareUV(x, L, u, v)) / c;
}

float ltDensityBeam(vec3 n) {
    int li = lightOfType(LIGHT_BEAM);
    if (li < 0) return 0.0;
    LightData L = lights[li];
    float c = abs(dot(n, vec3(L.dx, L.dy, L.dz)));
    if (c < 1e-4) return 0.0;
    float radius = max(L.sx, 1e-4);
    return lightPickPdf(li) / (PI * radius * radius) / c;
}

// ... and from the point or spot light: its pick, its direction's density, the point from it.
float ltDensityExtra(vec3 x, vec3 n, vec3 toLight) {
    int li = lightOfType(extraLightType == EXTRA_LIGHT_SPOT ? LIGHT_SPOT : LIGHT_POINT);
    if (li < 0) return 0.0;
    LightData L = lights[li];
    float c = abs(dot(n, toLight));
    if (c < 1e-4) return 0.0;
    float pdfDir = extraLightType == EXTRA_LIGHT_SPOT ? 1.0 / (TAU * (1.0 - cos(radians(clamp(L.sz, 1.0, 89.0))))) : 1.0 / (4.0 * PI);
    vec3 d = vec3(L.px, L.py, L.pz) - x;
    return lightPickPdf(li) * pdfDir * c / max(dot(d, d), 1e-8);
}
#endif

vec3 shadeSimple(vec3 hitPos, Ray ray, int matType) {
    if (matType == MAT_OCEAN) {
        vec3 normal = calcNormal(hitPos);
        vec3 reflectDir = reflect(ray.direction, normal);
        vec3 envReflect = sampleEnvironment(reflectDir);
        float fr = fresnel(-ray.direction, normal, 5.0);
        return mix(oceanColor, envReflect, fr * 0.8);
    }

    vec3 normal = calcNormal(hitPos);
    vec3 viewDir = -ray.direction;
    vec3 light = normalize(lightDir);

    OrbitTrap trap;
    DE(hitPos, trap);
    vec3 factors = getFactors(trap);
#ifdef BOOLEAN_OPS
    factors = morphFactors(hitPos, factors);
#endif
    factors = remapTrapFactors(factors, hitPos);

    vec3 baseColor = applyMaterial(factors, hitPos, normal, ray.direction);
    float localEmissive = emissiveIntensity;

#ifdef HAS_MATERIALS
    if (trap.matId >= 0) {
        MaterialData mat = materials[trap.matId];
        int mColorMode = int(mat.colorMode);
        if (mColorMode == 1) baseColor = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        else if (mColorMode == 2) baseColor *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        if (mat.emission >= 0.0) localEmissive = mat.emission;
    }
#endif

    float NdotL = max(dot(normal, light), 0.0);
    float shadowBias = surfaceBias(length(hitPos - camPos));
    float shadow = hitShadow(hitPos, normal, shadowBias, light, 15.0);
    float ao = calcAO(hitPos, normal);

    // Moss coloring
    if (mossEnabled != 0) {
        float mf = getMossFactor(hitPos, normal, ao);
        baseColor = mix(baseColor, mossColor, mf);
    }

    vec3 ambient = getAmbientLighting(normal) * baseColor * mix(0.2, 1.0, ao);
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;

    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    vec3 color = ambient + diffuse + specular;

    // Emission on reflected surface
    if (localEmissive > 0.0) {
        float structural = factors.x;
        float depth = factors.z;
        float emFactor = mix(structural, 1.0 - depth, 0.5);
        emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
#ifdef HAS_MATERIALS
        if (trap.matId >= 0) emFactor = 1.0;
#endif
        color += baseColor * localEmissive * emFactor;
    }

    return color;
}

vec3 shadeBackground(Ray ray, float minDist) {
    vec3 bg = sampleEnvironmentWithGlow(ray.direction, minDist);
    float extinction;
    return computeVolumetricFog(ray, 20.0, bg, extinction);
}

vec3 shade(RayHit hit, Ray ray) {
    if (hit.matType == MAT_OCEAN) {
        vec3 normal = calcNormal(hit.pos);
        vec3 viewDir = -ray.direction;
        vec3 reflectDir = reflect(ray.direction, normal);
        vec3 envReflect = sampleEnvironment(reflectDir);
        float fr = fresnel(viewDir, normal, 5.0);
        float shadow = hitShadow(hit.pos, normal, surfaceBias(hit.dist), normalize(lightDir), 15.0);
        return mix(oceanColor, envReflect, fr * 0.8) * shadow;
    }

    vec3 normal = calcNormal(hit.pos);
    vec3 viewDir = -ray.direction;
    vec3 light = normalize(lightDir);

    // Base color and material properties
    vec3 factors = getFactors(hit.trap);
#ifdef BOOLEAN_OPS
    factors = morphFactors(hit.pos, factors);
#endif
    factors = remapTrapFactors(factors, hit.pos);

    vec3 baseColor = applyMaterial(factors, hit.pos, normal, ray.direction);
    int localMatType = materialType;
    float localIor = ior;
    float localMetalness = metalness;
    float localEmissive = emissiveIntensity;
    float safeRoughness = max(roughness, 0.02);

#ifdef HAS_MATERIALS
    if (hit.trap.matId >= 0) {
        MaterialData mat = materials[hit.trap.matId];
        int mType = int(mat.type);
        int mColorMode = int(mat.colorMode);
        if (mColorMode == 1) baseColor = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        else if (mColorMode == 2) baseColor *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        if (mType >= 0) localMatType = mType;
        if (mat.roughness >= 0.0) safeRoughness = max(mat.roughness, 0.02);
        if (mat.metallic >= 0.0) localMetalness = mat.metallic;
        if (mat.ior >= 0.0) localIor = mat.ior;
        if (mat.emission >= 0.0) localEmissive = mat.emission;
    }
#endif

    // Diffuse (Lambert)
    float NdotL = max(dot(normal, light), 0.0);

    // Specular (Blinn-Phong)
    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);

    // Shadow
    float shadowBias = surfaceBias(hit.dist);
    float shadow = hitShadow(hit.pos, normal, shadowBias, light, 15.0);

    // Ambient occlusion
    float ao = calcAO(hit.pos, normal);

    // Moss coloring
    if (mossEnabled != 0) {
        float mf = getMossFactor(hit.pos, normal, ao);
        baseColor = mix(baseColor, mossColor, mf);
    }

    // Combine lighting
    vec3 ambient = getAmbientLighting(normal) * baseColor * mix(0.2, 1.0, ao);
    vec3 diffuse = lightColor * lightIntensity * baseColor * NdotL * shadow;
    vec3 specular = lightColor * spec * specularIntensity * shadow;

    // Material system adjustments
    if (localMatType == MATERIAL_METALLIC) {
        diffuse *= (1.0 - localMetalness);
        ambient *= (1.0 - localMetalness);

        if (useEnvMap != 0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            vec3 envReflect = sampleEnvironment(reflectDir);
            float F0 = mix(0.04, 1.0, localMetalness);
            float fr = fresnelSchlick(max(dot(normal, viewDir), 0.0), F0);
            specular += envReflect * specularIntensity * fr;
        }
    } else if (localMatType == MATERIAL_GLASS) {
        float fr = fresnelDielectric(max(dot(normal, viewDir), 0.0), localIor);
        diffuse *= (1.0 - fr);
        ambient *= (1.0 - fr);

        if (useEnvMap != 0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            specular += sampleEnvironment(reflectDir) * specularIntensity * fr;
        }
        specular += lightColor * pow(1.0 - NdotL, 4.0) * 0.5 * specularIntensity;
    } else {
        if (useEnvMap != 0 && envLightingMix > 0.0) {
            vec3 reflectDir = reflect(ray.direction, normal);
            vec3 envReflect = sampleEnvironment(reflectDir);
            float fresnelFactor = fresnel(viewDir, normal, 5.0);
            specular = mix(specular, envReflect * specularIntensity * fresnelFactor, envLightingMix * 0.5);
        }
    }

    // Fresnel rim lighting
    float rim = fresnel(viewDir, normal, 3.0);
    vec3 rimLight = lightColor * rim * rimIntensity;

    vec3 color = ambient + diffuse + specular + rimLight;

    // Audio-reactive color shift and glow
    if (audioEnabled != 0) {
        // Color shift: mid-frequency energy rotates the hue
        float midEnergy = (audioBands[2] + audioBands[3]) * 0.5;
        float hueShift = midEnergy * audioReactColor;
        vec3 hsv = rgb2hsv(color);
        hsv.x = fract(hsv.x + hueShift * 0.6);
        // Also boost saturation with audio level
        hsv.y = min(1.0, hsv.y + audioLevel * audioReactColor * 0.4);

        // Palette jump: onset-triggered stroboscopic hue shift
        if (audioReactPaletteJump > 0.0) {
            float jumpStrength = audioOnset * audioReactPaletteJump;
            if (jumpStrength > 0.05) {
                // Each onset jumps to a different hue via hash of audioFrameIndex
                float hueTarget = hash1(uint(audioFrameIndex) * 7919u);
                float hueOffset = mix(0.33, 0.67, hueTarget);
                hsv.x = fract(hsv.x + hueOffset * jumpStrength);
                hsv.y = min(1.0, hsv.y + jumpStrength * 0.3);
            }
        }

        color = hsv2rgb(hsv);

        // Treble glow: high-frequency energy adds luminance
        float trebleEnergy = (audioBands[5] + audioBands[6] + audioBands[7]) / 3.0;
        color += baseColor * trebleEnergy * audioReactGlow * 4.0;

        // Beat flash: brief white punch on strong beats
        color += vec3(audioBeat * audioReactGlow * 0.5);
    }

    // ====== EMISSIVE / SELF-ILLUMINATION ======
    float effectiveEmissive = localEmissive;
    // Audio onset pulse adds temporary emissive burst
    if (audioEnabled != 0) {
        effectiveEmissive += audioOnset * audioReactOnset * 3.0;
    }
    if (effectiveEmissive > 0.0) {
        float structural = factors.x;
        float depth = factors.z;
        float emFactor = mix(structural, 1.0 - depth, 0.5);
        emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
#ifdef HAS_MATERIALS
        if (hit.trap.matId >= 0) emFactor = 1.0;
#endif
        color += baseColor * effectiveEmissive * emFactor;
    }

    // ====== SUBSURFACE SCATTERING ======
    if (sssIntensity > 0.0) {
        vec3 sss = calcSSS(hit.pos, normal, light);
        color += baseColor * sssColor * sss * sssIntensity * lightColor * lightIntensity;
    }

    // ====== RAY-MARCHED REFLECTIONS ======
    if (reflectionIntensity > 0.0 && (localMatType == MATERIAL_METALLIC || localMatType == MATERIAL_GLASS)) {
        vec3 reflectDir = reflect(ray.direction, normal);
        float fresnelFactor;
        if (localMatType == MATERIAL_METALLIC) {
            float F0 = mix(0.04, 1.0, localMetalness);
            fresnelFactor = fresnelSchlick(max(dot(normal, viewDir), 0.0), F0);
        } else {
            fresnelFactor = fresnelDielectric(max(dot(normal, viewDir), 0.0), localIor);
        }

        Ray reflectRay;
        reflectRay.origin = hit.pos + normal * 0.005;
        reflectRay.direction = reflectDir;
        vec3 reflHitPos;
        float reflHitDist;
        int reflMat;
        vec3 reflColor;
        if (rayMarchSimple(reflectRay, reflHitPos, reflHitDist, reflMat)) {
            reflColor = shadeSimple(reflHitPos, reflectRay, reflMat);
        } else {
            reflColor = sampleEnvironment(reflectDir);
        }
        color = mix(color, reflColor, reflectionIntensity * fresnelFactor);
    }

    // ====== VOLUMETRIC FOG (God Rays) ======
    float extinction;
    color = computeVolumetricFog(ray, hit.dist, color, extinction);

    // Fallback distance fog (if volumetric is disabled)
    if (volumetricFogEnabled == 0) {
        float fogFactor = 1.0 - exp(-hit.dist * 0.05);
        vec3 fogColorBase = (useEnvMap != 0) ? sampleEnvironment(ray.direction) * 0.3 : ambientColor * 0.5;
        color = mix(color, fogColorBase, fogFactor * 0.3);
    }

    // Audio-reactive fog modulation
    if (audioEnabled != 0) {
        float audioFogAmount = audioLevel * audioReactFog;
        vec3 audioFogColor = (useEnvMap != 0) ? sampleEnvironment(ray.direction) * 0.4 : ambientColor * 0.6;
        float audioFogFactor = 1.0 - exp(-hit.dist * audioFogAmount * 0.3);
        color = mix(color, audioFogColor, audioFogFactor);
    }

    return color;
}

// ============================================================================
// Background / Environment
// ============================================================================
// Note: Environment sampling functions are now in common.glsl:
// - sampleEnvironment(dir) - for path tracing (HDRI or procedural)
// - sampleEnvironmentWithGlow(dir, minDist) - for raytracing with glow effect

// ============================================================================
// Path Tracing
// ============================================================================

// Simple ray march for path tracing (no orbit traps needed)
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist, out int matType) {
    float t = 0.0;
    int effectiveMaxSteps = int(float(maxRaySteps) * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;
    OrbitTrap dummyTrap;

    float prevD = 1e10;
    float omega = 1.0 + stepRelaxation;
    float lastStep = 0.0;
#ifdef HAS_DEEP_DE
    gSimpleMinDE = 1e10;
#endif

    for (int i = 0; i < effectiveMaxSteps; i++) {
        vec3 pos = ray.origin + ray.direction * t;
        float d = sceneDE(pos, dummyTrap, matType);
#ifdef HAS_DEEP_DE
        gSimpleMinDE = min(gSimpleMinDE, d);
#endif

        // Epsilon: cone tracing or legacy adaptive
        float epsilon;
        if (pixelRadius > 0.0) {
            epsilon = max(MIN_EPSILON, pixelRadius * t);
        } else {
            epsilon = computeAdaptiveEpsilon(t, qualityEpsilon, qualityMultiplier);
        }

        if (d < epsilon) {
            // Surface refinement
            if (refinementSteps > 0 && lastStep > 0.0) {
                pos = refineSurface(ray.origin, ray.direction, t, lastStep);
                t = length(pos - ray.origin);
            }
            // Cone tracing: converge to true surface (see rayMarch for rationale)
            if (pixelRadius > 0.0) {
                for (int j = 0; j < 6; j++) {
                    float ds = sceneDE_simple(pos);
                    if (ds < MIN_EPSILON) break;
                    pos += ray.direction * ds * STEP_FACTOR;
                }
                t = length(pos - ray.origin);
            }
            hitPos = pos;
            hitDist = t;
            return true;
        }

        float baseStep = d * STEP_FACTOR * fudgeFactor;

        // Step relaxation
        float step;
        if (stepRelaxation > 0.0) {
            float candidateStep = baseStep * omega;
            if (prevD + d < candidateStep) {
                t -= lastStep;
                step = baseStep;
                omega = 1.0;
            } else {
                step = candidateStep;
            }
        } else {
            step = baseStep;
        }

        prevD = d;
        lastStep = step;
        t += step;

        if (t > MAX_DISTANCE) break;
    }

    hitDist = t;
    return false;
}

// ---------------------------------------------------------------------------
// Rays leaving a surface the path tracer hit: a shadow ray toward a light, the next bounce.
// ---------------------------------------------------------------------------
// The path tracer used to leave a surface 0.005 world units above it, whatever the scale:
// at 1e-3 from the surface that is five times the view, and the indirect light came from
// somewhere unrelated to what is on screen. The offset now follows the view, a few pixel
// footprints (surfaceBias), and is never more than the 0.005 it was: a view from further
// than ~0.4 is unchanged. And a ray leaving a point the deep march found leaves from that
// point in double: leaveSurface registers its origin, and rayMarchSimpleLeaving /
// rayMarchSimpleCamera march it in double while it is near the surface, in float once clear.
// From the float hit point, a shadow ray at depth started under the surface and the image
// darkened as the camera went in (a path-traced Menger at 4e-5: half as bright as at 6e-4).

/** The offset of a ray leaving a surface the path tracer hit, at most what it always was. */
float pathBias(vec3 hitPos) {
    return min(0.005, surfaceBias(length(hitPos - camPos)));
}

#ifdef HAS_DEEP_DE
dvec3 gLeaveD = dvec3(0.0LF);    // the double origin of the last ray registered to leave a deep hit
vec3  gLeaveF = vec3(0.0);       // its float rounding, by which the marcher recognises the ray
bool  gLeaveValid = false;

/** A ray leaving the deep hit, registered by leaveSurface, marched in double while it is near
 *  the surface: true and the hit when it meets a surface there (recorded as the deep hit, so
 *  that surface's normal and rays are double too); otherwise false, with the ray moved to
 *  where it is clear by 100 float steps and how far that is in tOffset, for the float march
 *  to go on from there. */
bool leaveNearDeep(inout Ray ray, out vec3 hitPos, out float hitDist, out int matType, out float tOffset) {
    tOffset = 0.0;
    hitPos = ray.origin;
    hitDist = 0.0;
    matType = MAT_FRACTAL;
    gLeaveValid = false;
    dvec3 ro = gLeaveD;
    dvec3 rd = dvec3(ray.direction);
    float ulp = 1.19e-7 * max(length(camPos), 1e-3);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;
    int steps = int(float(maxRaySteps) * qualityMultiplier);
    double t = 0.0LF;
    for (int i = 0; i < steps; i++) {
        dvec3 q = ro + rd * t;
        float d = sceneDE_deep(q);
        if (d < deepEpsilon(float(t), qualityEpsilon)) {
            hitPos = vec3(q);
            hitDist = float(t);
            gDeepHitPos = q;
            gDeepHitValid = true;
            OrbitTrap dummyTrap;
            sceneDE(hitPos, dummyTrap, matType);
            return true;
        }
        if (d > 100.0 * ulp) break;
        t = t + double(d * STEP_FACTOR * fudgeFactor);
    }
    ray.origin = vec3(ro + rd * t);
    tOffset = float(t);
    return false;
}
#endif

/** The origin of a ray leaving the surface at hitPos on the side n, bias above it (bias as
 *  the float path has it): from the deep march's own point, in double, when hitPos is that
 *  point, the offset then a few pixel footprints at its true distance. */
vec3 leaveSurface(vec3 hitPos, vec3 n, float bias) {
#ifdef HAS_DEEP_DE
    if (deepPrecision != 0 && gDeepHitValid && all(equal(hitPos, vec3(gDeepHitPos)))) {
        float b = surfaceBias(float(length(gDeepHitPos - camPosD)));
        gLeaveD = gDeepHitPos + dvec3(n) * double(b);
        gLeaveF = vec3(gLeaveD);
        gLeaveValid = true;
        return gLeaveF;
    }
#endif
    return hitPos + n * bias;
}

/** rayMarchSimple for a ray leaveSurface may have registered (a shadow ray from a hit). */
bool rayMarchSimpleLeaving(Ray ray, out vec3 hitPos, out float hitDist, out int matType) {
    float tOffset = 0.0;
#ifdef HAS_DEEP_DE
    if (gLeaveValid && all(equal(ray.origin, gLeaveF))) {
        if (leaveNearDeep(ray, hitPos, hitDist, matType, tOffset)) return true;
    }
#endif
    bool hit = rayMarchSimple(ray, hitPos, hitDist, matType);
    hitDist = hitDist + tOffset;
    return hit;
}

/** rayMarchSimple for a ray that may be the camera's (the path tracer's first segment, the
 *  depth under each pixel), in double when it needs it, or the next bounce leaving a deep hit
 *  (leaveSurface), in double near the surface. Shadow rays go through rayMarchSimpleLeaving,
 *  ambient occlusion and the rest through rayMarchSimple itself, so the double marches are
 *  compiled in a few places rather than into every one of them. */
bool rayMarchSimpleCamera(Ray ray, out vec3 hitPos, out float hitDist, out int matType) {
    float tOffset = 0.0;
    bool leaving = false;
#ifdef HAS_DEEP_DE
    if (gLeaveValid && all(equal(ray.origin, gLeaveF))) {
        leaving = true;
        if (leaveNearDeep(ray, hitPos, hitDist, matType, tOffset)) return true;
    }
#endif
    bool hit = rayMarchSimple(ray, hitPos, hitDist, matType);
    hitDist = hitDist + tOffset;
#ifdef HAS_DEEP_DE
    if (!leaving && deepRay(ray) && deepNeeded(hitDist, gSimpleMinDE)) return rayMarchSimpleDeep(ray, hitPos, hitDist, matType);
#endif
    return hit;
}

// Classic path trace (legacy, for A/B comparison - no NEE+MIS, no GGX G term fix)
vec3 pathTraceClassic(Ray ray, inout uint seed) {
    vec3 throughput = vec3(1.0);  // Path throughput (accumulated BRDF)
    vec3 radiance = vec3(0.0);    // Accumulated light
    const float FIREFLY_CLAMP = 8.0; // Limit maximum intensity per bounce
    float lambda = 0.0;                // this path's wavelength, nm, when dispersion is on
    bool dispersed = false;            // tinted by it yet
    if (dispersionEnabled != 0) lambda = 400.0 + 300.0 * random(seed);

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;
        int hitMat;

        if (!rayMarchSimpleCamera(currentRay, hitPos, hitDist, hitMat)) {
            // Ray escaped - add environment light
            float envScale = (bounce == 0) ? 1.0 : indirectMultiplier;
            vec3 envContribution = throughput * sampleEnvironment(currentRay.direction) * envScale;
            radiance += clamp(envContribution, 0.0, FIREFLY_CLAMP);
            break;
        }

        // Handle Ocean/Floor materials
        if (hitMat == MAT_OCEAN) {
            vec3 normal = calcNormal(hitPos);
            vec3 viewDir = -currentRay.direction;
            float fr = fresnelDielectric(max(dot(viewDir, normal), 0.0), 1.33); // Water IOR
            if (random(seed) < fr) {
                currentRay.direction = reflect(currentRay.direction, normal);
                currentRay.origin = leaveSurface(hitPos, normal, pathBias(hitPos));
                throughput *= 0.95;
            } else {
                throughput *= oceanColor;
                currentRay.direction = randomCosineHemisphere(seed, normal);
                currentRay.origin = leaveSurface(hitPos, normal, pathBias(hitPos));
            }
            continue;
        }

        // Calculate normal at hit point
        vec3 normal = calcNormal(hitPos);
        vec3 faceNormal = (dot(currentRay.direction, normal) > 0.0) ? -normal : normal;

        // Get surface color and material properties
        OrbitTrap trap;
        DE(hitPos, trap);

        vec3 albedo;
        int localMatType;
        float localIor, localMetalness, localEmissive;
        float safeRoughness;

        {
            vec3 mf = getFactors(trap);
#ifdef BOOLEAN_OPS
            mf = morphFactors(hitPos, mf);
#endif
            albedo = applyMaterial(remapTrapFactors(mf, hitPos), hitPos, normal, currentRay.direction);
        }
        localMatType = materialType;
        localIor = ior;
        localMetalness = metalness;
        safeRoughness = max(roughness, 0.02);
        localEmissive = emissiveIntensity;

#ifdef HAS_MATERIALS
        if (trap.matId >= 0) {
            MaterialData mat = materials[trap.matId];
            int mType = int(mat.type);
            int mColorMode = int(mat.colorMode);
            if (mColorMode == 1) albedo = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            else if (mColorMode == 2) albedo *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            if (mType >= 0) localMatType = mType;
            if (mat.roughness >= 0.0) safeRoughness = max(mat.roughness, 0.02);
            if (mat.metallic >= 0.0) localMetalness = mat.metallic;
            if (mat.ior >= 0.0) localIor = mat.ior;
            if (mat.emission >= 0.0) localEmissive = mat.emission;
        }
#endif

        // Moss coloring
        if (mossEnabled != 0) {
            float ptAo = calcAO(hitPos, faceNormal);
            float mf = getMossFactor(hitPos, faceNormal, ptAo);
            albedo = mix(albedo, mossColor, mf);
        }

        vec3 viewDir = -currentRay.direction;
        float cosTheta = max(dot(viewDir, faceNormal), 0.0);
        vec3 extraLightDirNorm;
        vec3 extraLightRadiance = sampleExtraLightRadiance(hitPos, faceNormal, hitDist, seed, extraLightDirNorm);
        bool hasExtraLight = dot(extraLightRadiance, extraLightRadiance) > 0.0;

        // Emissive contribution at hit point (additive, unaffected by shadows)
        if (localEmissive > 0.0) {
#ifdef HAS_MATERIALS
            if (trap.matId >= 0) {
                radiance += clamp(throughput * albedo * localEmissive, 0.0, FIREFLY_CLAMP);
                break;
            }
#endif
            vec3 emFactorsRaw = getFactors(trap);
#ifdef BOOLEAN_OPS
            emFactorsRaw = morphFactors(hitPos, emFactorsRaw);
#endif
            vec3 factors = remapTrapFactors(emFactorsRaw, hitPos);
            float structural = factors.x;
            float depth = factors.z;
            float emFactor = mix(structural, 1.0 - depth, 0.5);
            emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
            radiance += clamp(throughput * albedo * localEmissive * emFactor, 0.0, FIREFLY_CLAMP);
        }

        if (localMatType == MATERIAL_GLASS) {
            // GLASS (Dielectric) — full two-surface refraction for SDF
            bool entering = dot(currentRay.direction, normal) < 0.0;
            float fr = fresnelDielectric(cosTheta, localIor);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);

            if (random(seed) < fr) {
                // Reflection
                vec3 reflectDir = reflect(currentRay.direction, faceNormal);
                if (safeRoughness > 0.001) {
                    vec3 H = randomGGX(seed, faceNormal, safeRoughness);
                    reflectDir = reflect(-viewDir, H);
                }
                currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                currentRay.direction = normalize(reflectDir);
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                // Transmission — refract entry, march interior, refract exit
                if (dispersionEnabled != 0) {
                    // this wavelength's index, and the path takes its colour once
                    localIor = dispersedIor(localIor, lambda);
                    if (!dispersed) { throughput *= spectralTint(lambda); dispersed = true; }
                }
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;

                if (!refractRay(currentRay.direction, faceNormal, entryEta, refractedDir)) {
                    // Total internal reflection
                    currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                    currentRay.direction = reflect(currentRay.direction, faceNormal);
                    throughput *= mix(vec3(1.0), albedo, 0.05);
                } else if (entering) {
                    // Entering glass — trace through interior and exit in one step
                    vec3 interiorDir = normalize(refractedDir);

                    // March through glass interior using abs(DE) for stepping
                    float t = 0.02;
                    vec3 exitPos = hitPos;
                    for (int gs = 0; gs < 128; gs++) {
                        exitPos = hitPos + interiorDir * t;
                        float d = sceneDE_simple(exitPos);
                        if (d > 0.0) {
                            // Overshot exit surface — step back to surface
                            exitPos -= interiorDir * d;
                            break;
                        }
                        t += max(abs(d), 0.002);
                        if (t > 10.0) break;
                    }

                    // Compute exit normal and refract out (glass → air)
                    vec3 exitNormal = calcNormal(exitPos);
                    vec3 exitFaceN = (dot(interiorDir, exitNormal) > 0.0) ? -exitNormal : exitNormal;
                    vec3 exitRefracted;

                    if (refractRay(interiorDir, exitFaceN, localIor, exitRefracted)) {
                        currentRay.direction = normalize(exitRefracted);
                    } else {
                        // TIR at exit — let ray pass through (approximation)
                        currentRay.direction = interiorDir;
                    }
                    currentRay.origin = exitPos + exitNormal * pathBias(exitPos);

                    // Chromatic dispersion through glass thickness
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                } else {
                    // Already exiting glass
                    currentRay.origin = leaveSurface(hitPos, -faceNormal, pathBias(hitPos));
                    currentRay.direction = normalize(refractedDir);
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                }
            }
        } else if (localMatType == MATERIAL_METALLIC) {
            // METALLIC
            // Evaluate visibility and the specular lobe along the same jittered
            // sun-disc sample (consistent NEE estimator for soft shadows).
            vec3 lightDirNorm = normalize(lightDir);
            if (dot(faceNormal, lightDirNorm) > 0.0) {
                vec3 sunSampleDir = jitterLightDir(lightDirNorm, seed, shadowSoftness);
                vec3 H_light = normalize(sunSampleDir + viewDir);
                float NdotL = max(dot(faceNormal, sunSampleDir), 0.0);
                if (NdotL > 0.0) {
                    Ray shadowRay;
                    float shadowBias = surfaceBias(hitDist);
                    shadowRay.origin = leaveSurface(hitPos, faceNormal, shadowBias);
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimpleLeaving(shadowRay, shPos, shDist, shMat)) {
                        vec3 F0 = mix(vec3(0.04), albedo, localMetalness);
                        vec3 F = fresnelSchlickVec(max(dot(H_light, viewDir), 0.0), F0);
                        float a = safeRoughness * safeRoughness;
                        float D = a * a / (PI * pow(max(dot(faceNormal, H_light), 0.0) * dot(faceNormal, H_light) * (a * a - 1.0) + 1.0, 2.0));
                        vec3 spec = F * D * NdotL * lightColor * lightIntensity;
                        radiance += clamp(throughput * spec, 0.0, FIREFLY_CLAMP);
                    }
                }
            }

            if (hasExtraLight) {
                float extraNdotL = max(dot(faceNormal, extraLightDirNorm), 0.0);
                if (extraNdotL > 0.0) {
                    vec3 H_extra = normalize(extraLightDirNorm + viewDir);
                    vec3 F0 = mix(vec3(0.04), albedo, localMetalness);
                    vec3 F = fresnelSchlickVec(max(dot(H_extra, viewDir), 0.0), F0);
                    float a = safeRoughness * safeRoughness;
                    float D = a * a / (PI * pow(max(dot(faceNormal, H_extra), 0.0) * dot(faceNormal, H_extra) * (a * a - 1.0) + 1.0, 2.0));
                    vec3 specExtra = F * D * extraNdotL * extraLightRadiance;
                    radiance += clamp(throughput * specExtra, 0.0, FIREFLY_CLAMP);
                }
            }

            // SSS contribution in path tracing (metallic)
            if (sssIntensity > 0.0) {
                vec3 sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
                if (hasExtraLight) {
                    vec3 sssExtra = calcSSS(hitPos, faceNormal, extraLightDirNorm);
                    radiance += clamp(throughput * albedo * sssColor * sssExtra * sssIntensity * extraLightRadiance, 0.0, FIREFLY_CLAMP);
                }
            }
            vec3 H = randomGGX(seed, faceNormal, safeRoughness);
            vec3 reflectDir = reflect(-viewDir, H);
            vec3 F0 = mix(vec3(0.04), albedo, localMetalness);
            throughput *= fresnelSchlickVec(cosTheta, F0);
            currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
            currentRay.direction = normalize(reflectDir);
        } else {
            // LAMBERTIAN
            // Sample + shade the sun disc along one consistent direction.
            vec3 lightDirNorm = normalize(lightDir);
            if (dot(faceNormal, lightDirNorm) > 0.0) {
                vec3 sunSampleDir = jitterLightDir(lightDirNorm, seed, shadowSoftness);
                float NdotL = max(dot(faceNormal, sunSampleDir), 0.0);
                if (NdotL > 0.0) {
                    Ray shadowRay;
                    float shadowBias = surfaceBias(hitDist);
                    shadowRay.origin = leaveSurface(hitPos, faceNormal, shadowBias);
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimpleLeaving(shadowRay, shPos, shDist, shMat)) {
                        radiance += clamp(throughput * albedo * lightColor * lightIntensity * NdotL / PI, 0.0, FIREFLY_CLAMP);
                    }
                }
            }

            if (hasExtraLight) {
                float extraNdotL = max(dot(faceNormal, extraLightDirNorm), 0.0);
                if (extraNdotL > 0.0) {
                    radiance += clamp(throughput * albedo * extraLightRadiance * extraNdotL / PI, 0.0, FIREFLY_CLAMP);
                }
            }

            // SSS contribution in path tracing
            if (sssIntensity > 0.0) {
                vec3 sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
                if (hasExtraLight) {
                    vec3 sssExtra = calcSSS(hitPos, faceNormal, extraLightDirNorm);
                    radiance += clamp(throughput * albedo * sssColor * sssExtra * sssIntensity * extraLightRadiance, 0.0, FIREFLY_CLAMP);
                }
            }
            currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));

            // Stochastic reflection for Lambertian surfaces
            float reflProb = reflectionIntensity * fresnelSchlick(cosTheta, 0.04);
            if (reflectionIntensity > 0.0 && random(seed) < reflProb) {
                // Specular reflection bounce (glossy via GGX)
                vec3 H = randomGGX(seed, faceNormal, max(safeRoughness, 0.3));
                currentRay.direction = normalize(reflect(-viewDir, H));
                throughput *= albedo / reflProb;
            } else {
                // Diffuse bounce
                currentRay.direction = randomCosineHemisphere(seed, faceNormal);
                throughput *= albedo;
            }
        }

        // Russian Roulette
        if (bounce >= 3) {
            float p = max(throughput.x, max(throughput.y, throughput.z));
            if (random(seed) > p) break;
            throughput /= p;
        }
    }

    return clamp(radiance, 0.0, FIREFLY_CLAMP * 2.0);
}

// Path trace with GGX geometry fix + environment NEE + MIS
vec3 pathTrace(Ray ray, inout uint seed) {
    vec3 throughput = vec3(1.0);
    vec3 radiance = vec3(0.0);
    const float FIREFLY_CLAMP = 8.0;
    float lambda = 0.0;                // this path's wavelength, nm, when dispersion is on
    bool dispersed = false;            // tinted by it yet
    if (dispersionEnabled != 0) lambda = 400.0 + 300.0 * random(seed);
    float lastBsdfPdf = 0.0; // Track BSDF pdf for MIS on escape
    bool lastWasSpecular = false; // Glass/mirror bounces have delta pdf
#ifdef BIDIR
    // Weights against the photon pass (photon.glsl): bdQ is its density of the path so far
    // over ours, area measure, its landing and passing coins included, a delta on either
    // side as 1; bdArrivePt our density of the current vertex from the previous one;
    // bdPrevCosOut the previous vertex's cosine along the segment, for the reverse density.
    float bdQ = 0.0;
    float bdArrivePt = 1.0;
    float bdPrevCosOut = 1.0;
    bool bdPrevDelta = false;
    bool bdLive = false;            // false once the photon pass cannot make this path
    vec3 bdForward = rotateByQuaternion(vec3(0, 0, 1), camQuat);
    float bdCosIn = 1.0, bdD2 = 1.0;
    // The photon pass lands only past a glass, mirror or metal vertex and flips its coin
    // only past one: bdMatte counts the matte and metal vertices after the first, bdCoins
    // how many of them lie before the last such vertex (-1: none yet, the path is not its).
    int bdMatte = 0, bdCoins = -1;
    // The gather: at the first matte or metal vertex reached through nothing but glass or
    // mirrors, the photons around it are what light it from behind glass; from there on a
    // BSDF ray reaching an emitter through glass is the gather's path, not this one's.
    bool bdOnlyDelta = true, bdGathered = false, bdDeltaSince = false;
#endif

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;
        int hitMat;

        bool segmentHit = rayMarchSimpleCamera(currentRay, hitPos, hitDist, hitMat);
#ifdef EXTRA_BEAM
        // The beam's glow in the fog along this segment. On the camera segment it is the
        // fog march's, after the path (computeVolumetricFog); after a bounce it is what a
        // face of glass reflects or refracts of the beam, and what lights a matte surface
        // near it. Neither the photon pass nor the fog march makes these paths. The
        // segment itself is not attenuated, as no bounce segment is.
        if (bounce > 0 && volumetricFogEnabled != 0 && fogDensity > 0.0 && extraLightType == EXTRA_LIGHT_BEAM && extraLightIntensity > 0.0)
            radiance += clamp(throughput * beamInScatter(currentRay, segmentHit ? hitDist : MAX_DISTANCE), 0.0, FIREFLY_CLAMP);
#endif
        if (!segmentHit) {
            // Ray escaped - add environment light with MIS weight
            float envScale = (bounce == 0) ? 1.0 : indirectMultiplier;
            vec3 envColor = sampleEnvironment(currentRay.direction) * envScale;

            if (neeEnabled != 0 && useEnvMap != 0 && bounce > 0 && envMapWidth > 0 && !lastWasSpecular) {
                // Apply MIS weight: BSDF sampling vs env NEE
                float envPdf = environmentPDF(currentRay.direction);
                float misW = powerHeuristic(lastBsdfPdf, envPdf);
                radiance += clamp(throughput * envColor * misW, 0.0, FIREFLY_CLAMP);
            } else {
                radiance += clamp(throughput * envColor, 0.0, FIREFLY_CLAMP);
            }
            break;
        }

        // Handle Ocean/Floor materials
        if (hitMat == MAT_OCEAN) {
            vec3 normal = calcNormal(hitPos);
            vec3 viewDir = -currentRay.direction;
#ifdef BIDIR
            bdCosIn = max(dot(normal, viewDir), 0.001);
            bdD2 = max(hitDist * hitDist, 1e-8);
            if (bounce == 0) {
                float cosCam = max(dot(currentRay.direction, bdForward), 0.001);
                float halfH = tan(radians(fov) * 0.5);
                float halfW = halfH * fullResolution.x / fullResolution.y;
                float pixelArea = (2.0 * halfW / fullResolution.x) * (2.0 * halfH / fullResolution.y);
                float pCam = bdCosIn / (bdD2 * pixelArea * cosCam * cosCam * cosCam);
                bdQ = LT_LAND / pCam;
                bdArrivePt = pCam;
                bdLive = true;
            } else {
                bdArrivePt = bdPrevDelta ? 1.0 : lastBsdfPdf * bdCosIn / bdD2;
            }
#endif
            float fr = fresnelDielectric(max(dot(viewDir, normal), 0.0), 1.33);
            if (random(seed) < fr) {
                currentRay.direction = reflect(currentRay.direction, normal);
                currentRay.origin = leaveSurface(hitPos, normal, pathBias(hitPos));
                throughput *= 0.95;
                lastWasSpecular = true; lastBsdfPdf = 0.0;
#ifdef BIDIR
                if (bounce == 0) bdLive = false;          // nor on a mirror
                if (bdLive) { bdQ *= 1.0 / bdArrivePt; bdPrevDelta = true; bdPrevCosOut = max(abs(dot(normal, currentRay.direction)), 0.001); bdCoins = bdMatte; }
#endif
            } else {
                throughput *= oceanColor;
                currentRay.direction = randomCosineHemisphere(seed, normal);
                currentRay.origin = leaveSurface(hitPos, normal, pathBias(hitPos));
                lastWasSpecular = false; lastBsdfPdf = max(dot(normal, currentRay.direction), 0.001) / PI;
#ifdef BIDIR
                if (bdLive) {
                    if (bounce > 0) { bdQ *= ((bdCosIn / PI) * bdPrevCosOut / bdD2) / bdArrivePt; bdMatte++; }
                    bdPrevDelta = false; bdPrevCosOut = max(dot(normal, currentRay.direction), 0.001);
                }
                bdOnlyDelta = false; bdDeltaSince = false;
#endif
            }
            continue;
        }

        vec3 normal = calcNormal(hitPos);
        vec3 faceNormal = (dot(currentRay.direction, normal) > 0.0) ? -normal : normal;
#ifdef BIDIR
        bdCosIn = max(dot(faceNormal, -currentRay.direction), 0.001);
        bdD2 = max(hitDist * hitDist, 1e-8);
        if (bounce == 0) {
            float cosCam = max(dot(currentRay.direction, bdForward), 0.001);
            float halfH = tan(radians(fov) * 0.5);
            float halfW = halfH * fullResolution.x / fullResolution.y;
            float pixelArea = (2.0 * halfW / fullResolution.x) * (2.0 * halfH / fullResolution.y);
            float pCam = bdCosIn / (bdD2 * pixelArea * cosCam * cosCam * cosCam);
            bdQ = 1.0 / pCam;               // the eye's density of the first vertex, against the photon's connection
            bdArrivePt = pCam;
            bdLive = true;
        } else {
            bdArrivePt = bdPrevDelta ? 1.0 : lastBsdfPdf * bdCosIn / bdD2;
        }
#endif

        OrbitTrap trap;
        DE(hitPos, trap);

        vec3 albedo;
        int localMatType;
        float localIor, localMetalness, localEmissive;
        float safeRoughness;

        {
            vec3 mf = getFactors(trap);
#ifdef BOOLEAN_OPS
            mf = morphFactors(hitPos, mf);
#endif
            albedo = applyMaterial(remapTrapFactors(mf, hitPos), hitPos, normal, currentRay.direction);
        }
        localMatType = materialType;
        localIor = ior;
        localMetalness = metalness;
        safeRoughness = max(roughness, 0.02);
        localEmissive = emissiveIntensity;

#ifdef HAS_MATERIALS
        if (trap.matId >= 0) {
            MaterialData mat = materials[trap.matId];
            int mType = int(mat.type);
            int mColorMode = int(mat.colorMode);
            if (mColorMode == 1) albedo = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            else if (mColorMode == 2) albedo *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
            if (mType >= 0) localMatType = mType;
            if (mat.roughness >= 0.0) safeRoughness = max(mat.roughness, 0.02);
            if (mat.metallic >= 0.0) localMetalness = mat.metallic;
            if (mat.ior >= 0.0) localIor = mat.ior;
            if (mat.emission >= 0.0) localEmissive = mat.emission;
        }
#endif

        // Moss coloring
        if (mossEnabled != 0) {
            float ptAo = calcAO(hitPos, faceNormal);
            float mf = getMossFactor(hitPos, faceNormal, ptAo);
            albedo = mix(albedo, mossColor, mf);
        }

        vec3 viewDir = -currentRay.direction;
        float NdotV = max(dot(viewDir, faceNormal), 0.001);
        vec3 extraLightDirNorm;
        vec3 extraLightRadiance = sampleExtraLightRadiance(hitPos, faceNormal, hitDist, seed, extraLightDirNorm);
        bool hasExtraLight = dot(extraLightRadiance, extraLightRadiance) > 0.0;

        float a = safeRoughness * safeRoughness;
        float a2 = a * a;

#ifdef BIDIR
        // The photon pass's density of this vertex being a photon's first hit, from the sun
        // and from the beam, for the weights of their draws below; and the weight of a draw
        // from here: the segment to the previous vertex closed with the reverse density
        // given the light's direction, this vertex's passing coin when it is not the first.
        bool bdMetalHere = localMatType == MATERIAL_METALLIC;
        float bdLtSun = bdLive ? ltDensitySun(hitPos, faceNormal) : 0.0;
        float bdLtBeam = (bdLive && extraLightType == EXTRA_LIGHT_BEAM) ? ltDensityBeam(faceNormal) : 0.0;
        bool bdExtraPoint = extraLightType == EXTRA_LIGHT_POINT || extraLightType == EXTRA_LIGHT_SPOT;
        float bdLtExtra = (bdLive && bdExtraPoint && hasExtraLight) ? ltDensityExtra(hitPos, faceNormal, extraLightDirNorm) : 0.0;
        // a draw from here is the photon pass's path too when a glass, mirror or metal lies
        // between here and the light: this vertex itself when it is metal, else an earlier one
        bool bdEligible = bounce > 0 && (bdMetalHere || bdCoins >= 0);
        float bdCoinFactor = bdEligible ? LT_LAND * pow(1.0 - LT_LAND, float(bdMetalHere ? bdMatte : bdCoins)) : 0.0;
#define BD_DRAW_WEIGHT(ltDens, ptDens, wLight) ( \
            (bdLive && bdEligible && (ltDens) > 0.0) ? (1.0 / (1.0 + pow(bdQ * bdCoinFactor * \
                ((bdMetalHere ? ggxPdf(faceNormal, (wLight), viewDir, a2) : bdCosIn / PI) * bdPrevCosOut / bdD2) / bdArrivePt \
                * (ltDens) * ltCount() / (ptDens), 2.0))) : 1.0)
        float bdSunW = BD_DRAW_WEIGHT(bdLtSun, 1.0, normalize(lightDir));
        float bdBeamW = BD_DRAW_WEIGHT(bdLtBeam, 1.0, extraLightDirNorm);
        if (extraLightType == EXTRA_LIGHT_BEAM) extraLightRadiance *= bdBeamW;
        float bdExtraW = BD_DRAW_WEIGHT(bdLtExtra, 1.0, extraLightDirNorm);
        if (bdExtraPoint) extraLightRadiance *= bdExtraW;

        if (mergeRadius > 0.0 && bounce > 0 && bdOnlyDelta && localMatType != MATERIAL_GLASS) {
            vec3 gathered = vec3(0.0);
            float r2 = mergeRadius * mergeRadius;
            ivec3 c0 = ivec3(floor(hitPos / mergeRadius));
            vec3 F0g = mix(vec3(0.04), albedo, localMetalness);
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                ivec3 c = c0 + ivec3(dx, dy, dz);
                uint h = (uint(c.x) * 73856093u ^ uint(c.y) * 19349663u ^ uint(c.z) * 83492791u) & uint(cellMask);
                uint start = cellStart[h];
                uint n = min(cellCount[h], uint(MERGE_MAX_PER_CELL));
                for (uint j = 0u; j < n; j++) {
                    uint i = photonIndex[start + j];
                    vec4 pf = photonData[4u * i];
                    vec3 dp = pf.xyz - hitPos;
                    if (dot(dp, dp) > r2) continue;
                    vec3 pn = photonData[4u * i + 2].xyz;
                    if (dot(pn, faceNormal) < 0.7) continue;
                    vec3 wi = photonData[4u * i + 3].xyz;
                    float NdotLg = max(dot(faceNormal, wi), 0.0);
                    if (NdotLg <= 0.0) continue;
                    vec3 f;
                    if (localMatType == MATERIAL_METALLIC) {
                        vec3 Hg = normalize(wi + viewDir);
                        float NdotHg = max(dot(faceNormal, Hg), 0.0);
                        float VdotHg = max(dot(viewDir, Hg), 0.001);
                        float Dg = a2 / (PI * pow(NdotHg * NdotHg * (a2 - 1.0) + 1.0, 2.0));
                        float Gg = smithG2GGX(max(NdotLg, 0.001), NdotV, a2);
                        f = fresnelSchlickVec(VdotHg, F0g) * Dg * Gg / (4.0 * NdotV * max(NdotLg, 0.001));
                    } else {
                        f = albedo / PI;
                    }
                    gathered += photonData[4u * i + 1].xyz * f;
                }
            }
            radiance += clamp(throughput * gathered / (PI * r2), 0.0, FIREFLY_CLAMP);
            bdGathered = true;
            bdDeltaSince = true;
        } else if (localMatType != MATERIAL_GLASS) {
            bdOnlyDelta = false;               // a matte or metal vertex before any gather
            bdDeltaSince = false;              // or after it: a BSDF ray from here is not the gather's
        }
#endif

        // Emissive
        if (localEmissive > 0.0) {
#ifdef HAS_MATERIALS
            if (trap.matId >= 0) {
#ifdef HAS_EMITTERS
                // Found by a BSDF ray: weighted against the direct draw of the same point,
                // which the previous vertex made unless it was specular or the camera, and
                // against the photon pass making this path.
                float emitMis = 1.0;
                {
                    int li = lightOfMaterial(trap.matId);
                    float aB = 0.0, cB = 0.0;
                    if (li >= 0 && bounce > 0) {
                        float cosL = max(dot(faceNormal, -currentRay.direction), 0.0);
                        float ptB = lastWasSpecular ? 1.0 : lastBsdfPdf * cosL / max(hitDist * hitDist, 1e-8);
                        if (!lastWasSpecular) aB = emitterPickPdf(li) * emitterPdfArea(li) / ptB;
#ifdef BIDIR
                        if (bdLive && bdCoins >= 0) cB = bdQ * LT_LAND * pow(1.0 - LT_LAND, float(bdCoins)) * (lightPickPdf(li) * emitterPdfArea(li) * (cosL / PI) * bdPrevCosOut / max(hitDist * hitDist, 1e-8)) * ltCount() / ptB;
#endif
                    }
                    emitMis = 1.0 / (1.0 + aB * aB + cB * cB);
                }
#ifdef BIDIR
                if (bdGathered && bdDeltaSince && lastWasSpecular) emitMis = 0.0;   // the gather has this path
#endif
                if (emitterDebug == 1 && bounce > 0) emitMis = 0.0;
                if (emitterDebug == 2) emitMis = 1.0;
                radiance += clamp(throughput * albedo * localEmissive * emitMis, 0.0, FIREFLY_CLAMP);
#else
                radiance += clamp(throughput * albedo * localEmissive, 0.0, FIREFLY_CLAMP);
#endif
                break;
            }
#endif
            vec3 emFactorsRaw = getFactors(trap);
#ifdef BOOLEAN_OPS
            emFactorsRaw = morphFactors(hitPos, emFactorsRaw);
#endif
            vec3 factors = remapTrapFactors(emFactorsRaw, hitPos);
            float structural = factors.x;
            float depth = factors.z;
            float emFactor = mix(structural, 1.0 - depth, 0.5);
            emFactor = pow(clamp(emFactor, 0.0, 1.0), 2.0);
            radiance += clamp(throughput * albedo * localEmissive * emFactor, 0.0, FIREFLY_CLAMP);
        }

        if (localMatType == MATERIAL_GLASS) {
            // GLASS — identical to classic (no GGX fix needed for dielectrics)
            bool entering = dot(currentRay.direction, normal) < 0.0;
            float cosTheta = max(dot(viewDir, faceNormal), 0.0);
            float fr = fresnelDielectric(cosTheta, localIor);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);
#ifdef BIDIR
            vec3 bdOutN = normal;       // the normal where the ray leaves this glass: the exit's when it went through
#endif

            if (random(seed) < fr) {
                vec3 reflectDir = reflect(currentRay.direction, faceNormal);
                if (safeRoughness > 0.001) {
                    vec3 H = randomGGX(seed, faceNormal, safeRoughness);
                    reflectDir = reflect(-viewDir, H);
                }
                currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                currentRay.direction = normalize(reflectDir);
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                if (dispersionEnabled != 0) {
                    // this wavelength's index, and the path takes its colour once
                    localIor = dispersedIor(localIor, lambda);
                    if (!dispersed) { throughput *= spectralTint(lambda); dispersed = true; }
                }
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;

                if (!refractRay(currentRay.direction, faceNormal, entryEta, refractedDir)) {
                    currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                    currentRay.direction = reflect(currentRay.direction, faceNormal);
                    throughput *= mix(vec3(1.0), albedo, 0.05);
                } else if (entering) {
                    vec3 interiorDir = normalize(refractedDir);
                    float t = 0.02;
                    vec3 exitPos = hitPos;
                    for (int gs = 0; gs < 128; gs++) {
                        exitPos = hitPos + interiorDir * t;
                        float d = sceneDE_simple(exitPos);
                        if (d > 0.0) {
                            exitPos -= interiorDir * d;
                            break;
                        }
                        t += max(abs(d), 0.002);
                        if (t > 10.0) break;
                    }
                    vec3 exitNormal = calcNormal(exitPos);
                    vec3 exitFaceN = (dot(interiorDir, exitNormal) > 0.0) ? -exitNormal : exitNormal;
                    vec3 exitRefracted;
                    if (refractRay(interiorDir, exitFaceN, localIor, exitRefracted)) {
                        currentRay.direction = normalize(exitRefracted);
                    } else {
                        currentRay.direction = interiorDir;
                    }
                    currentRay.origin = exitPos + exitNormal * pathBias(exitPos);
                    if (glassHaze > 0.0) {
                        // a hazy glass: the beam crossing it is seen along this segment inside,
                        // and what passes through is dimmed (the photon pass dims its photons the same)
#ifdef EXTRA_BEAM
                        if (extraLightType == EXTRA_LIGHT_BEAM && extraLightIntensity > 0.0)
                            radiance += clamp(throughput * hazeInScatter(hitPos, interiorDir, t, localIor), 0.0, FIREFLY_CLAMP);
#endif
                        throughput *= exp(-glassHaze * t);
                    }
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
#ifdef BIDIR
                    bdOutN = exitNormal;
#endif
                } else {
                    currentRay.origin = leaveSurface(hitPos, -faceNormal, pathBias(hitPos));
                    currentRay.direction = normalize(refractedDir);
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                }
            }
            lastWasSpecular = true; // Glass has delta-like pdf
            lastBsdfPdf = 0.0;
#ifdef BIDIR
            // a photon cannot land on glass: seen through it, nothing is the photon pass's
            if (bounce == 0) bdLive = false;
            if (bdLive) { bdQ *= 1.0 / bdArrivePt; bdPrevDelta = true; bdPrevCosOut = max(abs(dot(bdOutN, currentRay.direction)), 0.001); bdCoins = bdMatte; }
#endif
        } else if (localMatType == MATERIAL_METALLIC) {
            // METALLIC with corrected GGX BRDF (Smith G2 geometry term)
            vec3 F0 = mix(vec3(0.04), albedo, localMetalness);

            // --- Direct light NEE (stochastic soft shadow via jittered sun disc) ---
            // Sample the sun disc once and evaluate BOTH visibility and the BRDF
            // along the same direction; otherwise the shadow ray and the specular
            // lobe disagree (NdotH mismatch) and the estimator is inconsistent.
            vec3 lightDirNorm = normalize(lightDir);
            if (dot(faceNormal, lightDirNorm) > 0.0) {
                vec3 sunSampleDir = jitterLightDir(lightDirNorm, seed, shadowSoftness);
                float NdotL = max(dot(faceNormal, sunSampleDir), 0.0);
                if (NdotL > 0.0) {
                    Ray shadowRay;
                    float shadowBias = surfaceBias(hitDist);
                    shadowRay.origin = leaveSurface(hitPos, faceNormal, shadowBias);
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimpleLeaving(shadowRay, shPos, shDist, shMat)) {
                        vec3 H_light = normalize(sunSampleDir + viewDir);
                        float NdotH = max(dot(faceNormal, H_light), 0.0);
                        float VdotH = max(dot(viewDir, H_light), 0.0);
                        vec3 F = fresnelSchlickVec(VdotH, F0);
                        float D = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
                        float G = smithG2GGX(NdotL, NdotV, a2);
                        vec3 spec = F * D * G / (4.0 * NdotV) * lightColor * lightIntensity;
#ifdef BIDIR
                        spec *= bdSunW;
#endif
                        radiance += clamp(throughput * spec, 0.0, FIREFLY_CLAMP);
                    }
                }
            }

            // --- Extra light NEE ---
            if (hasExtraLight) {
                float extraNdotL = max(dot(faceNormal, extraLightDirNorm), 0.0);
                if (extraNdotL > 0.0) {
                    vec3 H_extra = normalize(extraLightDirNorm + viewDir);
                    float NdotH_e = max(dot(faceNormal, H_extra), 0.0);
                    float VdotH_e = max(dot(viewDir, H_extra), 0.0);
                    vec3 F = fresnelSchlickVec(VdotH_e, F0);
                    float D = a2 / (PI * pow(NdotH_e * NdotH_e * (a2 - 1.0) + 1.0, 2.0));
                    float G = smithG2GGX(extraNdotL, NdotV, a2);
                    vec3 specExtra = F * D * G / (4.0 * NdotV) * extraLightRadiance;
                    radiance += clamp(throughput * specExtra, 0.0, FIREFLY_CLAMP);
                }
            }

#ifdef HAS_EMITTERS
            // --- Emitter NEE + MIS ---
            {
                EmitterSample es = sampleEmitter(hitPos, faceNormal, hitDist, seed);
                if (es.valid) {
                    float NdotL_e = max(dot(faceNormal, es.wi), 0.001);
                    vec3 H_e = normalize(es.wi + viewDir);
                    float NdotH_e = max(dot(faceNormal, H_e), 0.0);
                    float VdotH_e = max(dot(viewDir, H_e), 0.001);
                    vec3 F_e = fresnelSchlickVec(VdotH_e, F0);
                    float D_e = a2 / (PI * pow(NdotH_e * NdotH_e * (a2 - 1.0) + 1.0, 2.0));
                    float G_e = smithG2GGX(NdotL_e, NdotV, a2);
                    vec3 brdf_e = F_e * D_e * G_e / (4.0 * NdotV * NdotL_e);
                    float bsdfPdfArea = (D_e * NdotH_e / (4.0 * VdotH_e)) * es.cosL / (es.dist * es.dist);
                    float misW = powerHeuristic(es.pdfArea, bsdfPdfArea);
#ifdef BIDIR
                    {
                        float ltDens = lightPickPdf(es.li) * emitterPdfArea(es.li) * (es.cosL / PI) * NdotL_e / (es.dist * es.dist);
                        float cA = (bdLive && bdEligible) ? bdQ * bdCoinFactor * ((ggxPdf(faceNormal, es.wi, viewDir, a2) * bdPrevCosOut / bdD2) / bdArrivePt) * ltDens * ltCount() / es.pdfArea : 0.0;
                        float bA = bsdfPdfArea / es.pdfArea;
                        misW = 1.0 / (1.0 + bA * bA + cA * cA);
                    }
#endif
                    radiance += clamp(throughput * brdf_e * es.Le * NdotL_e * es.cosL / (es.dist * es.dist * es.pdfArea) * misW, 0.0, FIREFLY_CLAMP);
                }
            }
#endif

            // --- Environment NEE + MIS ---
            if (neeEnabled != 0 && useEnvMap != 0 && envMapWidth > 0) {
                vec3 envColor; vec3 envDir; float envPdf;
                sampleEnvironmentImportance(seed, envColor, envDir, envPdf);

                float envNdotL = dot(faceNormal, envDir);
                if (envNdotL > 0.0) {
                    // Shadow test
                    Ray envShadowRay;
                    envShadowRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                    envShadowRay.direction = envDir;
                    vec3 eShPos; float eShDist; int eShMat;
                    if (!rayMarchSimpleLeaving(envShadowRay, eShPos, eShDist, eShMat)) {
                        // Evaluate GGX BRDF for this direction
                        vec3 H_env = normalize(envDir + viewDir);
                        float NdotH_env = max(dot(faceNormal, H_env), 0.0);
                        float VdotH_env = max(dot(viewDir, H_env), 0.0);
                        vec3 F_env = fresnelSchlickVec(VdotH_env, F0);
                        float D_env = a2 / (PI * pow(NdotH_env * NdotH_env * (a2 - 1.0) + 1.0, 2.0));
                        float G_env = smithG2GGX(envNdotL, NdotV, a2);
                        vec3 brdfVal = F_env * D_env * G_env / (4.0 * NdotV * envNdotL);

                        // BSDF pdf for this direction (GGX importance sampling pdf)
                        float bsdfPdf = D_env * NdotH_env / (4.0 * VdotH_env);
                        float misW = powerHeuristic(envPdf, bsdfPdf);

                        radiance += clamp(throughput * brdfVal * envColor * envNdotL * misW / envPdf, 0.0, FIREFLY_CLAMP);
                    }
                }
            }

            // SSS contribution
            if (sssIntensity > 0.0) {
                vec3 sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
                if (hasExtraLight) {
                    vec3 sssExtra = calcSSS(hitPos, faceNormal, extraLightDirNorm);
                    radiance += clamp(throughput * albedo * sssColor * sssExtra * sssIntensity * extraLightRadiance, 0.0, FIREFLY_CLAMP);
                }
            }

            // --- Bounce with corrected throughput ---
            vec3 H = randomGGX(seed, faceNormal, safeRoughness);
            vec3 reflectDir = reflect(-viewDir, H);
            float NdotH = max(dot(faceNormal, H), 0.001);
            float VdotH = max(dot(viewDir, H), 0.001);
            float NdotL_bounce = max(dot(faceNormal, reflectDir), 0.0);
            vec3 F = fresnelSchlickVec(VdotH, F0);
            float G = smithG2GGX(max(NdotL_bounce, 0.001), NdotV, a2);
            // GGX importance sampling: throughput = F * G * VdotH / (NdotV * NdotH)
            throughput *= F * G * VdotH / (NdotV * NdotH);
            // GGX pdf = D * NdotH / (4 * VdotH)
            float D_bounce = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
            lastBsdfPdf = D_bounce * NdotH / (4.0 * VdotH);
            lastWasSpecular = false;

            currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
            currentRay.direction = normalize(reflectDir);
#ifdef BIDIR
            if (bdLive) {
                if (bounce > 0) { bdQ *= (ggxPdf(faceNormal, currentRay.direction, viewDir, a2) * bdPrevCosOut / bdD2) / bdArrivePt; bdCoins = bdMatte; bdMatte++; }
                bdPrevDelta = false; bdPrevCosOut = max(dot(faceNormal, currentRay.direction), 0.001);
            }
#endif
        } else {
            // LAMBERTIAN
            // Sample + shade the sun disc along one consistent direction.
            vec3 lightDirNorm = normalize(lightDir);
            if (dot(faceNormal, lightDirNorm) > 0.0) {
                vec3 sunSampleDir = jitterLightDir(lightDirNorm, seed, shadowSoftness);
                float NdotL = max(dot(faceNormal, sunSampleDir), 0.0);
                if (NdotL > 0.0) {
                    Ray shadowRay;
                    float shadowBias = surfaceBias(hitDist);
                    shadowRay.origin = leaveSurface(hitPos, faceNormal, shadowBias);
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimpleLeaving(shadowRay, shPos, shDist, shMat)) {
#ifdef BIDIR
                        radiance += clamp(throughput * albedo * lightColor * lightIntensity * NdotL / PI * bdSunW, 0.0, FIREFLY_CLAMP);
#else
                        radiance += clamp(throughput * albedo * lightColor * lightIntensity * NdotL / PI, 0.0, FIREFLY_CLAMP);
#endif
                    }
                }
            }

            if (hasExtraLight) {
                float extraNdotL = max(dot(faceNormal, extraLightDirNorm), 0.0);
                if (extraNdotL > 0.0) {
                    radiance += clamp(throughput * albedo * extraLightRadiance * extraNdotL / PI, 0.0, FIREFLY_CLAMP);
                }
            }

#ifdef HAS_EMITTERS
            // --- Emitter NEE + MIS for Lambertian ---
            {
                EmitterSample es = sampleEmitter(hitPos, faceNormal, hitDist, seed);
                if (es.valid) {
                    float NdotL_e = max(dot(faceNormal, es.wi), 0.0);
                    float bsdfPdfArea = (NdotL_e / PI) * es.cosL / (es.dist * es.dist);
                    float misW = powerHeuristic(es.pdfArea, bsdfPdfArea);
#ifdef BIDIR
                    {
                        float ltDens = lightPickPdf(es.li) * emitterPdfArea(es.li) * (es.cosL / PI) * NdotL_e / (es.dist * es.dist);
                        float cA = (bdLive && bdEligible) ? bdQ * bdCoinFactor * (((bdCosIn / PI) * bdPrevCosOut / bdD2) / bdArrivePt) * ltDens * ltCount() / es.pdfArea : 0.0;
                        float bA = bsdfPdfArea / es.pdfArea;
                        misW = 1.0 / (1.0 + bA * bA + cA * cA);
                    }
#endif
                    if (emitterDebug == 1) misW = 1.0;
                    if (emitterDebug == 2) misW = 0.0;
                    radiance += clamp(throughput * (albedo / PI) * es.Le * NdotL_e * es.cosL / (es.dist * es.dist * es.pdfArea) * misW, 0.0, FIREFLY_CLAMP);
                }
            }
#endif

            // --- Environment NEE + MIS for Lambertian ---
            if (neeEnabled != 0 && useEnvMap != 0 && envMapWidth > 0) {
                vec3 envColor; vec3 envDir; float envPdf;
                sampleEnvironmentImportance(seed, envColor, envDir, envPdf);

                float envNdotL = dot(faceNormal, envDir);
                if (envNdotL > 0.0) {
                    Ray envShadowRay;
                    envShadowRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));
                    envShadowRay.direction = envDir;
                    vec3 eShPos; float eShDist; int eShMat;
                    if (!rayMarchSimpleLeaving(envShadowRay, eShPos, eShDist, eShMat)) {
                        // Lambertian BRDF = albedo / PI
                        vec3 brdfVal = albedo / PI;
                        // Cosine hemisphere pdf = NdotL / PI
                        float bsdfPdf = envNdotL / PI;
                        float misW = powerHeuristic(envPdf, bsdfPdf);

                        radiance += clamp(throughput * brdfVal * envColor * envNdotL * misW / envPdf, 0.0, FIREFLY_CLAMP);
                    }
                }
            }

            // SSS
            if (sssIntensity > 0.0) {
                vec3 sss = calcSSS(hitPos, faceNormal, normalize(lightDir));
                radiance += clamp(throughput * albedo * sssColor * sss * sssIntensity * lightColor * lightIntensity, 0.0, FIREFLY_CLAMP);
                if (hasExtraLight) {
                    vec3 sssExtra = calcSSS(hitPos, faceNormal, extraLightDirNorm);
                    radiance += clamp(throughput * albedo * sssColor * sssExtra * sssIntensity * extraLightRadiance, 0.0, FIREFLY_CLAMP);
                }
            }

            currentRay.origin = leaveSurface(hitPos, faceNormal, pathBias(hitPos));

            // Stochastic reflection for Lambertian surfaces
            float cosTheta = max(dot(viewDir, faceNormal), 0.0);
            float reflProb = reflectionIntensity * fresnelSchlick(cosTheta, 0.04);
            if (reflectionIntensity > 0.0 && random(seed) < reflProb) {
                vec3 H = randomGGX(seed, faceNormal, max(safeRoughness, 0.3));
                currentRay.direction = normalize(reflect(-viewDir, H));
                throughput *= albedo / reflProb;
                lastWasSpecular = true; // Glossy reflection
                lastBsdfPdf = 0.0;
#ifdef BIDIR
                bdLive = false;         // the photon pass never takes this lobe
#endif
            } else {
                currentRay.direction = randomCosineHemisphere(seed, faceNormal);
                throughput *= albedo;
                // Cosine hemisphere pdf = NdotL / PI
                float bouncedNdotL = max(dot(faceNormal, currentRay.direction), 0.001);
                lastBsdfPdf = bouncedNdotL / PI;
                lastWasSpecular = false;
#ifdef BIDIR
                if (bdLive) {
                    if (bounce > 0) { bdQ *= ((bdCosIn / PI) * bdPrevCosOut / bdD2) / bdArrivePt; bdMatte++; }
                    bdPrevDelta = false; bdPrevCosOut = bouncedNdotL;
                }
#endif
            }
        }

        // Russian Roulette
        if (bounce >= 3) {
            float p = max(throughput.x, max(throughput.y, throughput.z));
            if (random(seed) > p) break;
            throughput /= p;
        }
    }

    return clamp(radiance, 0.0, FIREFLY_CLAMP * 2.0);
}

// ============================================================================
// Render Mode Dispatcher
// ============================================================================

vec3 renderByMode(RayHit hit, Ray ray, vec3 normal, float shadow, float ao) {
    vec3 factors = getFactors(hit.trap);
#ifdef BOOLEAN_OPS
    factors = morphFactors(hit.pos, factors);
#endif
    factors = remapTrapFactors(factors, hit.pos);
    vec3 baseColor = applyMaterial(factors, hit.pos, normal, ray.direction);
#ifdef HAS_MATERIALS
    if (hit.trap.matId >= 0) {
        MaterialData mat = materials[hit.trap.matId];
        int mColorMode = int(mat.colorMode);
        if (mColorMode == 1) baseColor = vec3(mat.albedoR, mat.albedoG, mat.albedoB);
        else if (mColorMode == 2) baseColor *= vec3(mat.albedoR, mat.albedoG, mat.albedoB);
    }
#endif

    // Moss coloring — applied here so ALL render modes see it
    if (mossEnabled != 0) {
        float mf = getMossFactor(hit.pos, normal, ao);
        baseColor = mix(baseColor, mossColor, mf);
    }

    switch (renderMode) {
        case RENDER_MODE_NORMALS:
            return normal * 0.5 + 0.5;

        case RENDER_MODE_DEPTH:
            // Use logarithmic depth for better visualization of near objects
            // Maps distance 0.1 -> white, distance 10 -> black
            float logDepth = 1.0 - clamp(log(hit.dist + 0.1) / log(15.0), 0.0, 1.0);
            return vec3(logDepth);

        case RENDER_MODE_AO:
            return vec3(ao);

        case RENDER_MODE_SHADOW:
            return vec3(shadow);

        case RENDER_MODE_ITERATIONS:
            return hsv2rgb(vec3(float(hit.steps) / float(maxRaySteps), 0.8, 0.9));

        case RENDER_MODE_ORBIT_TRAP:
            return factors; // Visualise raw geometric factors

        case RENDER_MODE_DIFFUSE:
            return baseColor * max(dot(normal, normalize(lightDir)), 0.0);

        case RENDER_MODE_SPECULAR:
            vec3 halfDir = normalize(normalize(lightDir) - ray.direction);
            float spec = pow(max(dot(normal, halfDir), 0.0), specularPower);
            return vec3(spec);

        default: // RENDER_MODE_FINAL
            return shade(hit, ray);
    }
}

// ============================================================================
// Main Render Function
// ============================================================================

#ifndef PHOTON_PASS
// ---- The probe ---------------------------------------------------------------------
// Every distance the app needs (the focus pick, the auto speed) is asked of the scene's own
// program, never of a copy of the estimator on the CPU, so it holds for every fractal, graph,
// hybrid chain and custom shader alike. With probeMode on, fragment i of a one-row target is
// one ray from the camera, probeRay[i]: the pinhole ray through the screen point xy
// ([-1, 1], y up) when w is 0, the ray along the world direction xyz when w is 1 (where the
// camera is about to move). It is marched by the view's own primary march, and the output is:
// the distance along the ray to the surface (-1 for a miss), the scene's estimate at the
// camera, the steps, 1 on a hit. The probe takes the same path through main() as the view and
// leaves right after the march: no second copy of the march or of the estimator to compile.
#define PROBE_RAYS 8
uniform vec4 probeRay[PROBE_RAYS];

void main() {
    // Initialize random for this pixel/sample
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    bool probe = probeMode != 0;

    // Adaptive sampling: skip converged pixels
    // Uses variance-of-mean (popVariance / count) to measure actual noise in the average.
    // A pixel is converged when adding more samples won't visibly change the result.
    if (adaptiveSampling != 0 && !probe) {
        vec4 varData = imageLoad(varianceImage, pixel);
        float count = varData.b;
        if (count >= float(minAdaptiveSamples)) {
            float meanLum = varData.r / count;
            float popVariance = max(0.0, (varData.g / count) - meanLum * meanLum);
            float varianceOfMean = popVariance / count;
            if (varianceOfMean < varianceThreshold) {
                FragColor = vec4(0.0);
                return;
            }
        }
    }

    // Blue noise: spatially uniform + temporally animated via golden ratio
    float bnAnim = float(sampleIndex) * 0.6180339887498949;
    ivec2 bnCoord = ivec2(gl_FragCoord.xy) % 64;
    vec2 bnJitter = fract(texelFetch(blueNoiseTex, bnCoord, 0).rg + bnAnim);
    vec2 jitter = (bnJitter - 0.5) / fullResolution;
    random(seed); random(seed); // advance PCG by 2 to keep downstream chain consistent

    // Blue noise for DoF aperture (offset texel for decorrelation from jitter)
    ivec2 bnCoordDof = (ivec2(gl_FragCoord.xy) + ivec2(37, 17)) % 64;
    vec2 bnDof = fract(texelFetch(blueNoiseTex, bnCoordDof, 0).rg + bnAnim);

    // Remap tile-local fragCoord to full-image NDC [-1,1]
    vec2 tileUV = fragCoord * 0.5 + 0.5;                          // [0,1] within tile
    vec2 fullUV = tileOffset + tileUV * tileScale;                 // [0,1] within full image
    vec2 screenUV = fullUV * 2.0 - 1.0 + jitter * 2.0;           // [-1,1] NDC + jitter
    vec4 probeSpec = probe ? probeRay[min(pixel.x, PROBE_RAYS - 1)] : vec4(0.0);
    if (probe) screenUV = probeSpec.xy;

    // Deep-zoom iteration LOD: how close the camera sits to the surface sets how
    // fine the structure on screen is, and therefore how many DE iterations are
    // needed to still resolve it. Probed at the base budget so the anchor itself
    // does not depend on the result.
#ifdef DETAIL_LOD
#ifdef HAS_DEEP_DE
    gExtraIterations = zoomDetailIterations(deepPrecision != 0 ? sceneDE_deep(camPosD) : sceneDE_simple(camPos));
#else
    gExtraIterations = zoomDetailIterations(sceneDE_simple(camPos));
#endif
#endif

    // Get camera ray (with optional DoF)
    Ray ray = getCameraRayDOF(screenUV, seed, bnDof);
    if (probe && probeSpec.w > 0.5) ray.direction = normalize(probeSpec.xyz);

    vec3 color;
    float depth = 100.0; // Default far distance

    // Choose rendering mode: Path Tracing or Raytracing (the probe takes the second: it
    // wants the primary march and nothing after it)
    if (pathTracingEnabled != 0 && renderMode == RENDER_MODE_FINAL && !probe) {
        // ====== PATH TRACING ======
        if (neeEnabled != 0) {
            color = pathTrace(ray, seed);      // NEE+MIS + corrected GGX
        } else {
            color = pathTraceClassic(ray, seed); // Legacy behavior
        }
        // Get surface distance for focus picking + volumetric fog
        vec3 surfPos;
        float surfDepth;
        int surfMatType;
        bool surfHit = rayMarchSimpleCamera(ray, surfPos, surfDepth, surfMatType);
        if (surfHit) {
            depth = surfDepth;
        }
        // Apply volumetric fog
        if (volumetricFogEnabled != 0 && fogDensity > 0.0) {
            float fogDepth = surfHit ? surfDepth : 100.0;
            float extinction;
            color = computeVolumetricFog(ray, fogDepth, color, extinction);
        }
    } else {
        // ====== CLASSIC RAYTRACING ======
        RayHit hit = rayMarch(ray);
        depth = hit.hit ? hit.dist : 100.0;
        if (probe) {
            FragColor = vec4(hit.hit ? hit.dist : -1.0, gMarchStartDE, float(hit.steps), hit.hit ? 1.0 : 0.0);
            return;
        }

        if (hit.hit) {
            // STRICT COLORING:
            // Evaluate fractal exactly at the surface hit point.
            // This ensures perfect alignment between geometry (Normals/AO) and Material Color.
            DE(hit.pos, hit.trap);

            vec3 normal = calcNormal(hit.pos);
            float shadowBias = surfaceBias(hit.dist);
            float shadow = hitShadow(hit.pos, normal, shadowBias, normalize(lightDir), 15.0);
            float ao = calcAO(hit.pos, normal);

            color = renderByMode(hit, ray, normal, shadow, ao);
        } else {
            // For debug modes, use simple background; for final mode use fancy background with volumetric fog
            if (renderMode == RENDER_MODE_FINAL) {
                color = shadeBackground(ray, hit.minDist);
            } else {
                // True black background for debug modes
                color = vec3(0.0);
            }
        }
    }

    // Apply DOF chromatic aberration weight
    color *= dofColorWeight;

    // Adaptive sampling: update variance statistics
    if (adaptiveSampling != 0) {
        float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
        vec4 varData = imageLoad(varianceImage, pixel);
        // .a accumulates the depth of the samples that were drawn, with .b their count: a
        // converged pixel skips samples (FragColor = 0 above, alpha included), so the depth
        // in the accumulation's alpha divided by the global sample count under-reads, late
        // in a refinement by a lot, and a focus pick then lands far too close.
        imageStore(varianceImage, pixel,
            vec4(varData.r + lum, varData.g + lum * lum, varData.b + 1.0, varData.a + depth));
    }

    // Output: RGB = color (accumulated), A = depth (for focus picking when adaptive
    // sampling is off; with it on the engine reads the variance image instead)
    FragColor = vec4(color, depth);
}
#endif
