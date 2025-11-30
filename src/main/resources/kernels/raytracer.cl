/**
 * Generic Raytracer Pipeline for Fractaliz3r
 * Inspired by Fragmentarium's DE-Raytracer.frag architecture.
 *
 * USAGE:
 * Each fractal kernel should:
 * 1. Include common.cl first
 * 2. Define its OrbitTraps struct
 * 3. Define DE() and DE_simple() functions
 * 4. Define FRACTAL_DE_SIMPLE(pos) macro pointing to its DE_simple
 * 5. Include this file (raytracer.cl)
 * 6. Use the generic functions: calcNormalGeneric, calcShadowGeneric, calcAOGeneric
 *
 * Example:
 *   #define FRACTAL_DE_SIMPLE(p) myFractalDE_simple(p, param1, param2)
 *   #include "raytracer.cl"
 */

#ifndef RAYTRACER_CL
#define RAYTRACER_CL

// ============================================================================
// Generic Normal Calculation (Tetrahedron Method)
// Requires: FRACTAL_DE_SIMPLE(pos) macro to be defined
// ============================================================================

#ifdef FRACTAL_DE_SIMPLE

/**
 * Calculate surface normal using tetrahedron gradient method.
 * Uses 4 DE samples instead of 6 (central differences) for efficiency.
 */
float3 calcNormalGeneric(float3 pos) {
    const float e = NORMAL_EPSILON;
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float d1 = FRACTAL_DE_SIMPLE(pos + k1 * e);
    float d2 = FRACTAL_DE_SIMPLE(pos + k2 * e);
    float d3 = FRACTAL_DE_SIMPLE(pos + k3 * e);
    float d4 = FRACTAL_DE_SIMPLE(pos + k4 * e);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

/**
 * Calculate soft shadows using sphere tracing.
 * Returns shadow factor [0, 1] where 0 = full shadow, 1 = no shadow.
 */
float calcShadowGeneric(float3 ro, float3 rd, float mint, float maxt,
                        float softness, int shadowSteps) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = FRACTAL_DE_SIMPLE(pos);

        if (h < 0.0001f) return 0.0f;

        res = fmin(res, h * softness / t);
        t += clamp(h, 0.01f, 0.5f);
    }

    return clamp(res, 0.0f, 1.0f);
}

/**
 * Calculate ambient occlusion by sampling along normal direction.
 * Returns AO factor [0, 1] where 0 = fully occluded, 1 = no occlusion.
 */
float calcAOGeneric(float3 pos, float3 normal, int aoSteps) {
    float ao = 0.0f;
    float scale = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = FRACTAL_DE_SIMPLE(aoPos);
        ao += (hr - dd) * scale;
        scale *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

#endif // FRACTAL_DE_SIMPLE

// ============================================================================
// Generic Ray Marching Loop
// This is a template - each fractal customizes via macros
// ============================================================================

/**
 * Perform ray marching with quality scaling.
 * Returns a RayHit structure with hit information.
 *
 * Note: This is a helper that can be used by fractals that don't need
 * special ray marching logic. Fractals with orbit traps should implement
 * their own ray march loop to capture trap data.
 */
RayHit raymarchGeneric(
    float3 rayOrigin,
    float3 rayDir,
    int maxRaySteps,
    float baseEpsilon,
    float qualityMultiplier
) {
    RayHit result;
    result.hit = false;
    result.totalDist = 0.0f;
    result.minDist = 1e10f;
    result.iterations = 0;

    int effectiveMaxSteps = (int)((float)maxRaySteps * qualityMultiplier);
    float qualityEpsilon = baseEpsilon / qualityMultiplier;

    for (int i = 0; i < effectiveMaxSteps; i++) {
        result.pos = rayOrigin + rayDir * result.totalDist;

        #ifdef FRACTAL_DE_SIMPLE
        float dist = FRACTAL_DE_SIMPLE(result.pos);
        #else
        float dist = 1e10f; // Fallback - should never happen
        #endif

        result.minDist = fmin(result.minDist, dist);

        float adaptiveEpsilon = computeAdaptiveEpsilon(result.totalDist, qualityEpsilon, qualityMultiplier);

        if (dist < adaptiveEpsilon) {
            result.hit = true;
            break;
        }

        float step = computeStep(dist, qualityMultiplier, STEP_FACTOR);
        result.totalDist += step;

        if (result.totalDist > MAX_DISTANCE) break;
    }

    return result;
}

// ============================================================================
// Shading Helper with All Parameters
// ============================================================================

/**
 * Complete shading for a hit point.
 * Combines all the rendering pipeline steps into one call.
 */
float3 shadeHitPoint(
    float3 pos,
    float3 rayDir,
    float3 baseColor,
    int iterations,
    int maxIterations,
    float totalDist,
    // Light params
    float3 light,
    float3 lightCol,
    float lightIntensity,
    float3 ambientCol,
    float ambientIntensity,
    // Quality params
    float shadowSoftness,
    int shadowSteps,
    int aoSteps,
    float aoIntensity,
    float specularPower,
    float specularIntensity,
    int renderMode
) {
    #ifdef FRACTAL_DE_SIMPLE
    float3 normal = calcNormalGeneric(pos);
    float3 viewDir = -rayDir;

    // Shadow with bias
    float shadowBias = 0.001f + totalDist * 0.001f;
    float shadow = calcShadowGeneric(pos + normal * shadowBias, light,
                                      shadowBias, 15.0f, shadowSoftness, shadowSteps);

    // Ambient occlusion
    float ao = calcAOGeneric(pos, normal, aoSteps);

    // Use common rendering pipeline
    return renderByMode(
        renderMode, baseColor, normal, light, viewDir,
        lightCol, lightIntensity, ambientCol, ambientIntensity,
        shadow, ao, aoIntensity, specularPower, specularIntensity,
        totalDist, iterations, maxIterations
    );
    #else
    return baseColor; // Fallback
    #endif
}

#endif // RAYTRACER_CL
