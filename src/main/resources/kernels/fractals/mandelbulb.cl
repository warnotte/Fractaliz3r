co/**
 * Mandelbulb 3D fractal OpenCL kernel (Refactored)
 *
 * This file contains only Mandelbulb-specific code:
 * - Distance estimators (full + simple)
 * - Orbit trap structure
 * - Normal/shadow/AO calculations using the DE
 * - Color from orbit traps
 * - Main kernel function
 *
 * Common functionality is provided by common.cl:
 * - DoF setup (initDofSetup, getDofSampleRay)
 * - Shading (renderByMode, calculateShading)
 * - Background (renderBackground)
 * - Tone mapping (toneMapAndGamma)
 */

// ============================================================================
// Orbit trap structure for rich coloring
// ============================================================================

typedef struct {
    float plane;
    float sphere;
    float axis;
    float cube;
    int iterations;
} OrbitTraps;

// ============================================================================
// Mandelbulb distance estimator with orbit traps
// ============================================================================

float mandelbulbDE(float3 pos, float power, int baseIterations, float bailout,
                   float distanceHint, OrbitTraps* traps) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    traps->plane = 1e10f;
    traps->sphere = 1e10f;
    traps->axis = 1e10f;
    traps->cube = 1e10f;
    traps->iterations = 0;

    int dynamicIter = baseIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynamicIter = baseIterations + (int)((0.1f - distanceHint) * 10.0f * (float)baseIterations);
        dynamicIter = min(dynamicIter, baseIterations * 2);
    }

    int i;
    for (i = 0; i < dynamicIter; i++) {
        r = length3(z);

        traps->plane = fmin(traps->plane, fabs(z.y));
        traps->sphere = fmin(traps->sphere, fabs(r - 1.0f));
        traps->axis = fmin(traps->axis, sqrt(z.x * z.x + z.y * z.y));
        traps->cube = fmin(traps->cube, fmax(fmax(fabs(z.x), fabs(z.y)), fabs(z.z)));

        if (r > bailout) break;

        float theta = acos(clamp(z.z / r, -1.0f, 1.0f));
        float phi = atan2(z.y, z.x);
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        float sinTheta = sin(theta);
        z = (float3)(
            sinTheta * cos(phi),
            sinTheta * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    traps->iterations = i;
    float de = 0.5f * log(r) * r / dr;
    return fmax(de, 1e-7f);
}

/**
 * Simplified DE for shadows/AO (fixed iterations, no orbit trap)
 */
float mandelbulbDE_simple(float3 pos, float power, int maxIterations, float bailout) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    for (int i = 0; i < maxIterations; i++) {
        r = length3(z);
        if (r > bailout) break;

        float theta = acos(clamp(z.z / r, -1.0f, 1.0f));
        float phi = atan2(z.y, z.x);
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        float sinTheta = sin(theta);
        z = (float3)(
            sinTheta * cos(phi),
            sinTheta * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    float de = 0.5f * log(r) * r / dr;
    return fmax(de, 1e-7f);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalMandelbulb(float3 pos, float power, int maxIterations, float bailout) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = mandelbulbDE_simple(pos + k1 * e, power, maxIterations, bailout);
    float d2 = mandelbulbDE_simple(pos + k2 * e, power, maxIterations, bailout);
    float d3 = mandelbulbDE_simple(pos + k3 * e, power, maxIterations, bailout);
    float d4 = mandelbulbDE_simple(pos + k4 * e, power, maxIterations, bailout);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadowMandelbulb(float3 ro, float3 rd, float mint, float maxt,
                            float softness, int shadowSteps,
                            float power, int maxIterations, float bailout) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelbulbDE_simple(pos, power, maxIterations, bailout);

        if (h < 0.0001f) return 0.0f;

        res = fmin(res, h * softness / t);
        t += clamp(h, 0.01f, 0.5f);
    }

    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAOMandelbulb(float3 pos, float3 normal, int aoSteps,
                        float power, int maxIterations, float bailout) {
    float ao = 0.0f;
    float scale = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.005f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelbulbDE_simple(aoPos, power, maxIterations, bailout);
        ao += (hr - dd) * scale;
        scale *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getOrbitColor(OrbitTraps traps, float3 baseHue) {
    float t1 = traps.plane * 2.0f;
    float t2 = traps.sphere * 3.0f;
    float t3 = traps.axis * 1.5f;
    float t4 = traps.cube * 2.5f;

    float combined = t1 * 0.3f + t2 * 0.3f + t3 * 0.2f + t4 * 0.2f;
    return palette(combined, baseHue);
}

// ============================================================================
// Main Mandelbulb render kernel
// ============================================================================

__kernel void renderMandelbulb(
    __global float* output,
    int imageWidth,
    int imageHeight,
    int tileOffsetX,
    int tileOffsetY,
    int tileSize,
    // Camera
    float4 camPos,
    float4 camQuat,
    float fov,
    // Fractal params
    float power,
    int maxIterations,
    int maxRaySteps,
    float bailout,
    float baseEpsilon,
    // Light direction
    float4 lightDir,
    // Light color and intensity
    float4 lightColor,
    // Ambient color
    float4 ambientColor,
    // Material base hue
    float4 materialHue,
    // Rendering quality
    float shadowSoftness,
    int shadowSteps,
    int aoSteps,
    float aoIntensity,
    float glowIntensity,
    float qualityMultiplier,
    // Specular
    float specularIntensity,
    float specularPower,
    // Render mode
    int renderMode,
    // Depth of Field
    int dofEnabled,
    float focalDistance,
    float aperture,
    int dofSamples
) {
    // Bounds check
    int localX = get_global_id(0);
    int localY = get_global_id(1);
    if (localX >= tileSize || localY >= tileSize) return;

    int pixelX = tileOffsetX + localX;
    int pixelY = tileOffsetY + localY;
    if (pixelX >= imageWidth || pixelY >= imageHeight) return;

    int outputIdx = (localY * tileSize + localX) * 4;

    // Screen coordinates
    float aspectRatio = (float)imageWidth / (float)imageHeight;
    float u = (2.0f * ((float)pixelX + 0.5f) / (float)imageWidth - 1.0f) * aspectRatio;
    float v = 1.0f - 2.0f * ((float)pixelY + 0.5f) / (float)imageHeight;

    // Extract lighting parameters
    float3 lightCol = (float3)(lightColor.x, lightColor.y, lightColor.z);
    float lightInt = lightColor.w;
    float3 ambientCol = (float3)(ambientColor.x, ambientColor.y, ambientColor.z);
    float ambientInt = ambientColor.w;
    float3 baseHue = (float3)(materialHue.x, materialHue.y, materialHue.z);
    float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));

    // Initialize DoF
    DofSetup dof = initDofSetup(camPos, camQuat, fov, (float2)(u, v),
                                 focalDistance, aperture, dofEnabled, dofSamples);

    // Accumulator for DoF samples
    float3 accumulatedColor = (float3)(0.0f, 0.0f, 0.0f);

    // DoF sampling loop
    for (int sampleIdx = 0; sampleIdx < dof.numSamples; sampleIdx++) {
        float3 rayOrigin, rayDir;
        getDofSampleRay(dof, sampleIdx, pixelX, pixelY, aperture, dofEnabled,
                        &rayOrigin, &rayDir);

        // Ray marching with quality multiplier for ultimate detail
        float totalDist = 0.0f;
        float3 pos = rayOrigin;
        OrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;
        float lastDist = 1e10f;

        // Scale parameters by quality multiplier
        int effectiveMaxSteps = (int)((float)maxRaySteps * qualityMultiplier);
        float qualityEpsilon = baseEpsilon / qualityMultiplier;
        float qualityStepFactor = STEP_FACTOR / fmax(1.0f, qualityMultiplier * 0.5f);

        for (int i = 0; i < effectiveMaxSteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            float dist = mandelbulbDE(pos, power, maxIterations, bailout, lastDist, &traps);

            minDist = fmin(minDist, dist);
            lastDist = dist;

            // Adaptive epsilon scaled by quality
            float adaptiveEpsilon = fmax(MIN_EPSILON / qualityMultiplier, totalDist * EPSILON_FACTOR / qualityMultiplier);
            adaptiveEpsilon = fmin(adaptiveEpsilon, MAX_EPSILON / qualityMultiplier);
            adaptiveEpsilon = fmax(adaptiveEpsilon, qualityEpsilon * 0.1f);

            if (dist < adaptiveEpsilon) {
                hit = true;
                break;
            }

            float step = dist * qualityStepFactor;
            step = fmax(step, MIN_EPSILON / qualityMultiplier);
            totalDist += step;

            if (totalDist > MAX_DISTANCE) break;
        }

        // Shading
        float3 sampleColor;

        if (hit) {
            float3 normal = calcNormalMandelbulb(pos, power, maxIterations, bailout);
            float3 viewDir = -rayDir;
            float3 baseColor = getOrbitColor(traps, baseHue);

            // Calculate shadow and AO
            int reducedIter = max(8, maxIterations / 2);
            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcShadowMandelbulb(pos + normal * shadowBias, light,
                                                 shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                                 power, reducedIter, bailout);
            float ao = calcAOMandelbulb(pos, normal, aoSteps, power, reducedIter, bailout);

            // Use common rendering pipeline
            sampleColor = renderByMode(
                renderMode, baseColor, normal, light, viewDir,
                lightCol, lightInt, ambientCol, ambientInt,
                shadow, ao, aoIntensity, specularPower, specularIntensity,
                totalDist, traps.iterations, maxIterations
            );
        } else {
            // Background
            sampleColor = renderBackground(renderMode, rayDir, minDist,
                                           glowIntensity, baseHue, lightCol, ambientCol);
        }

        accumulatedColor += sampleColor;
    }

    // Average DoF samples and output
    float3 finalColor = accumulatedColor / (float)dof.numSamples;

    output[outputIdx] = clamp(finalColor.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(finalColor.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(finalColor.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}