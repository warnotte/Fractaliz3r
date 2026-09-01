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

out vec4 FragColor;

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

float calcAO(vec3 pos, vec3 normal) {
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

    return result;
}

// ============================================================================
// Volumetric Fog Helper
// ============================================================================

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
    
    return surfaceColor * extinction + volumetricLight;
}

// ============================================================================
// Shading
// ============================================================================

// Forward declaration (defined in Path Tracing section below)
bool rayMarchSimple(Ray ray, out vec3 hitPos, out float hitDist, out int matType);

vec3 getExtraLightAxisWS() {
    // Dampen lateral direction controls to keep spot steering predictable.
    vec3 localDir = vec3(extraLightDir.x * 0.2, extraLightDir.y * 0.2, extraLightDir.z);
    if (dot(localDir, localDir) < 1e-8) {
        localDir = vec3(0.0, 0.0, 1.0);
    }
    return normalize(rotateByQuaternion(localDir, camQuat));
}

vec3 getExtraLightPositionWS() {
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

    // Self-hit due to numerical precision: keep light visible.
    if (shDist <= shadowBias * 2.0) {
        return 1.0;
    }

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
    float shadow = calcShadow(hitPos + normal * shadowBias, light, shadowBias, 15.0);
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
        float shadow = calcShadow(hit.pos + normal * surfaceBias(hit.dist), normalize(lightDir), surfaceBias(hit.dist), 15.0);
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
    float shadow = calcShadow(hit.pos + normal * shadowBias, light, shadowBias, 15.0);

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

    for (int i = 0; i < effectiveMaxSteps; i++) {
        vec3 pos = ray.origin + ray.direction * t;
        float d = sceneDE(pos, dummyTrap, matType);

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

// Classic path trace (legacy, for A/B comparison - no NEE+MIS, no GGX G term fix)
vec3 pathTraceClassic(Ray ray, inout uint seed) {
    vec3 throughput = vec3(1.0);  // Path throughput (accumulated BRDF)
    vec3 radiance = vec3(0.0);    // Accumulated light
    const float FIREFLY_CLAMP = 8.0; // Limit maximum intensity per bounce

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;
        int hitMat;

        if (!rayMarchSimple(currentRay, hitPos, hitDist, hitMat)) {
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
                currentRay.origin = hitPos + normal * 0.005;
                throughput *= 0.95;
            } else {
                throughput *= oceanColor;
                currentRay.direction = randomCosineHemisphere(seed, normal);
                currentRay.origin = hitPos + normal * 0.005;
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
                currentRay.origin = hitPos + faceNormal * 0.005;
                currentRay.direction = normalize(reflectDir);
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                // Transmission — refract entry, march interior, refract exit
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;

                if (!refractRay(currentRay.direction, faceNormal, entryEta, refractedDir)) {
                    // Total internal reflection
                    currentRay.origin = hitPos + faceNormal * 0.005;
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
                    currentRay.origin = exitPos + exitNormal * 0.005;

                    // Chromatic dispersion through glass thickness
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                } else {
                    // Already exiting glass
                    currentRay.origin = hitPos - faceNormal * 0.005;
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
                    shadowRay.origin = hitPos + faceNormal * shadowBias;
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimple(shadowRay, shPos, shDist, shMat)) {
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
            currentRay.origin = hitPos + faceNormal * 0.005;
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
                    shadowRay.origin = hitPos + faceNormal * shadowBias;
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimple(shadowRay, shPos, shDist, shMat)) {
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
            currentRay.origin = hitPos + faceNormal * 0.005;

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
    float lastBsdfPdf = 0.0; // Track BSDF pdf for MIS on escape
    bool lastWasSpecular = false; // Glass/mirror bounces have delta pdf

    Ray currentRay = ray;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos;
        float hitDist;
        int hitMat;

        if (!rayMarchSimple(currentRay, hitPos, hitDist, hitMat)) {
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
            float fr = fresnelDielectric(max(dot(viewDir, normal), 0.0), 1.33);
            if (random(seed) < fr) {
                currentRay.direction = reflect(currentRay.direction, normal);
                currentRay.origin = hitPos + normal * 0.005;
                throughput *= 0.95;
                lastWasSpecular = true; lastBsdfPdf = 0.0;
            } else {
                throughput *= oceanColor;
                currentRay.direction = randomCosineHemisphere(seed, normal);
                currentRay.origin = hitPos + normal * 0.005;
                lastWasSpecular = false; lastBsdfPdf = max(dot(normal, currentRay.direction), 0.001) / PI;
            }
            continue;
        }

        vec3 normal = calcNormal(hitPos);
        vec3 faceNormal = (dot(currentRay.direction, normal) > 0.0) ? -normal : normal;

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

        // Emissive
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
            // GLASS — identical to classic (no GGX fix needed for dielectrics)
            bool entering = dot(currentRay.direction, normal) < 0.0;
            float cosTheta = max(dot(viewDir, faceNormal), 0.0);
            float fr = fresnelDielectric(cosTheta, localIor);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);

            if (random(seed) < fr) {
                vec3 reflectDir = reflect(currentRay.direction, faceNormal);
                if (safeRoughness > 0.001) {
                    vec3 H = randomGGX(seed, faceNormal, safeRoughness);
                    reflectDir = reflect(-viewDir, H);
                }
                currentRay.origin = hitPos + faceNormal * 0.005;
                currentRay.direction = normalize(reflectDir);
                throughput *= mix(vec3(1.0), albedo, 0.05);
            } else {
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;

                if (!refractRay(currentRay.direction, faceNormal, entryEta, refractedDir)) {
                    currentRay.origin = hitPos + faceNormal * 0.005;
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
                    currentRay.origin = exitPos + exitNormal * 0.005;
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                } else {
                    currentRay.origin = hitPos - faceNormal * 0.005;
                    currentRay.direction = normalize(refractedDir);
                    throughput *= vec3(0.98, 1.0, 1.02) * albedo;
                }
            }
            lastWasSpecular = true; // Glass has delta-like pdf
            lastBsdfPdf = 0.0;
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
                    shadowRay.origin = hitPos + faceNormal * shadowBias;
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimple(shadowRay, shPos, shDist, shMat)) {
                        vec3 H_light = normalize(sunSampleDir + viewDir);
                        float NdotH = max(dot(faceNormal, H_light), 0.0);
                        float VdotH = max(dot(viewDir, H_light), 0.0);
                        vec3 F = fresnelSchlickVec(VdotH, F0);
                        float D = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
                        float G = smithG2GGX(NdotL, NdotV, a2);
                        vec3 spec = F * D * G / (4.0 * NdotV) * lightColor * lightIntensity;
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

            // --- Environment NEE + MIS ---
            if (neeEnabled != 0 && useEnvMap != 0 && envMapWidth > 0) {
                vec3 envColor; vec3 envDir; float envPdf;
                sampleEnvironmentImportance(seed, envColor, envDir, envPdf);

                float envNdotL = dot(faceNormal, envDir);
                if (envNdotL > 0.0) {
                    // Shadow test
                    Ray envShadowRay;
                    envShadowRay.origin = hitPos + faceNormal * 0.005;
                    envShadowRay.direction = envDir;
                    vec3 eShPos; float eShDist; int eShMat;
                    if (!rayMarchSimple(envShadowRay, eShPos, eShDist, eShMat)) {
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

            currentRay.origin = hitPos + faceNormal * 0.005;
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
                    shadowRay.origin = hitPos + faceNormal * shadowBias;
                    shadowRay.direction = sunSampleDir;
                    vec3 shPos; float shDist; int shMat;
                    if (!rayMarchSimple(shadowRay, shPos, shDist, shMat)) {
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

            // --- Environment NEE + MIS for Lambertian ---
            if (neeEnabled != 0 && useEnvMap != 0 && envMapWidth > 0) {
                vec3 envColor; vec3 envDir; float envPdf;
                sampleEnvironmentImportance(seed, envColor, envDir, envPdf);

                float envNdotL = dot(faceNormal, envDir);
                if (envNdotL > 0.0) {
                    Ray envShadowRay;
                    envShadowRay.origin = hitPos + faceNormal * 0.005;
                    envShadowRay.direction = envDir;
                    vec3 eShPos; float eShDist; int eShMat;
                    if (!rayMarchSimple(envShadowRay, eShPos, eShDist, eShMat)) {
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

            currentRay.origin = hitPos + faceNormal * 0.005;

            // Stochastic reflection for Lambertian surfaces
            float cosTheta = max(dot(viewDir, faceNormal), 0.0);
            float reflProb = reflectionIntensity * fresnelSchlick(cosTheta, 0.04);
            if (reflectionIntensity > 0.0 && random(seed) < reflProb) {
                vec3 H = randomGGX(seed, faceNormal, max(safeRoughness, 0.3));
                currentRay.direction = normalize(reflect(-viewDir, H));
                throughput *= albedo / reflProb;
                lastWasSpecular = true; // Glossy reflection
                lastBsdfPdf = 0.0;
            } else {
                currentRay.direction = randomCosineHemisphere(seed, faceNormal);
                throughput *= albedo;
                // Cosine hemisphere pdf = NdotL / PI
                float bouncedNdotL = max(dot(faceNormal, currentRay.direction), 0.001);
                lastBsdfPdf = bouncedNdotL / PI;
                lastWasSpecular = false;
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

void main() {
    // Initialize random for this pixel/sample
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);
    ivec2 pixel = ivec2(gl_FragCoord.xy);

    // Adaptive sampling: skip converged pixels
    // Uses variance-of-mean (popVariance / count) to measure actual noise in the average.
    // A pixel is converged when adding more samples won't visibly change the result.
    if (adaptiveSampling != 0) {
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

    // Deep-zoom iteration LOD: how close the camera sits to the surface sets how
    // fine the structure on screen is, and therefore how many DE iterations are
    // needed to still resolve it. Probed at the base budget so the anchor itself
    // does not depend on the result.
#ifdef DETAIL_LOD
    gExtraIterations = zoomDetailIterations(sceneDE_simple(camPos));
#endif

    // Get camera ray (with optional DoF)
    Ray ray = getCameraRayDOF(screenUV, seed, bnDof);

    vec3 color;
    float depth = 100.0; // Default far distance

    // Choose rendering mode: Path Tracing or Raytracing
    if (pathTracingEnabled != 0 && renderMode == RENDER_MODE_FINAL) {
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
        bool surfHit = rayMarchSimple(ray, surfPos, surfDepth, surfMatType);
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

        if (hit.hit) {
            // STRICT COLORING:
            // Evaluate fractal exactly at the surface hit point.
            // This ensures perfect alignment between geometry (Normals/AO) and Material Color.
            DE(hit.pos, hit.trap);

            vec3 normal = calcNormal(hit.pos);
            float shadowBias = surfaceBias(hit.dist);
            float shadow = calcShadow(hit.pos + normal * shadowBias, normalize(lightDir), shadowBias, 15.0);
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
        imageStore(varianceImage, pixel,
            vec4(varData.r + lum, varData.g + lum * lum, varData.b + 1.0, 0.0));
    }

    // Output: RGB = color (accumulated), A = depth (for focus picking)
    // Depth is stored in alpha - will be averaged with colors, but that's fine for focus picking
    FragColor = vec4(color, depth);
}
