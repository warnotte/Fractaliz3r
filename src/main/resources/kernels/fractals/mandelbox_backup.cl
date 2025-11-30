/**
 * Mandelbox 3D fractal OpenCL kernel (Refactored)
 *
 * This file contains only Mandelbox-specific code.
 * Common functionality is provided by common.cl.
 */

// ============================================================================
// Orbit trap structure for Mandelbox coloring
// ============================================================================

typedef struct {
    float minDist;
    float avgFold;
    float sphereHits;
    int iterations;
} MandelboxTraps;

// ============================================================================
// Box fold operation
// ============================================================================

float3 boxFold(float3 z, float foldingLimit) {
    return clamp(z, -foldingLimit, foldingLimit) * 2.0f - z;
}

// ============================================================================
// Sphere fold operation
// ============================================================================

void sphereFoldMandelbox(float3* z, float* dz, float minRadius, float fixedRadius) {
    float minR2 = minRadius * minRadius;
    float fixR2 = fixedRadius * fixedRadius;
    float r2 = dot3(*z, *z);

    if (r2 < minR2) {
        float scale = fixR2 / minR2;
        *z *= scale;
        *dz *= scale;
    } else if (r2 < fixR2) {
        float scale = fixR2 / r2;
        *z *= scale;
        *dz *= scale;
    }
}

// ============================================================================
// Mandelbox distance estimator with orbit traps
// ============================================================================

float mandelboxDE(float3 pos, float scale, float minRadius, float fixedRadius,
                  float foldingLimit, int maxIterations, MandelboxTraps* traps) {
    float3 z = pos;
    float3 offset = pos;
    float dz = 1.0f;

    traps->minDist = 1e10f;
    traps->avgFold = 0.0f;
    traps->sphereHits = 0.0f;
    traps->iterations = 0;

    int i;
    for (i = 0; i < maxIterations; i++) {
        float3 oldZ = z;
        z = boxFold(z, foldingLimit);
        float foldAmount = length3(z - oldZ);
        traps->avgFold += foldAmount;

        float r2Before = dot3(z, z);
        sphereFoldMandelbox(&z, &dz, minRadius, fixedRadius);
        float r2After = dot3(z, z);
        if (r2After != r2Before) {
            traps->sphereHits += 1.0f;
        }

        z = z * scale + offset;
        dz = dz * fabs(scale) + 1.0f;

        float dist = length3(z);
        traps->minDist = fmin(traps->minDist, dist);

        if (dist > 1000.0f) break;
    }

    traps->iterations = i;
    traps->avgFold /= (float)(i + 1);

    float r = length3(z);
    return r / fabs(dz);
}

/**
 * Simplified DE for shadows/AO
 */
float mandelboxDE_simple(float3 pos, float scale, float minRadius, float fixedRadius,
                         float foldingLimit, int maxIterations) {
    float3 z = pos;
    float3 offset = pos;
    float dz = 1.0f;

    for (int i = 0; i < maxIterations; i++) {
        z = boxFold(z, foldingLimit);
        sphereFoldMandelbox(&z, &dz, minRadius, fixedRadius);
        z = z * scale + offset;
        dz = dz * fabs(scale) + 1.0f;

        if (dot3(z, z) > 1000000.0f) break;
    }

    float r = length3(z);
    return r / fabs(dz);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalMandelbox(float3 pos, float scale, float minRadius, float fixedRadius,
                            float foldingLimit, int maxIterations) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = mandelboxDE_simple(pos + k1 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d2 = mandelboxDE_simple(pos + k2 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d3 = mandelboxDE_simple(pos + k3 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
    float d4 = mandelboxDE_simple(pos + k4 * e, scale, minRadius, fixedRadius, foldingLimit, maxIterations);

        float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
        return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadowMandelbox(float3 ro, float3 rd, float mint, float maxt,
                           float softness, int shadowSteps, float scale,
                           float minRadius, float fixedRadius, float foldingLimit, int maxIterations) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelboxDE_simple(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);

        if (h < 0.0001f) return 0.0f;

        res = fmin(res, h * softness / t);
        t += clamp(h, 0.01f, 0.5f);
    }

    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAOMandelbox(float3 pos, float3 normal, int aoSteps, float scale,
                       float minRadius, float fixedRadius, float foldingLimit, int maxIterations) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelboxDE_simple(aoPos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getMandelboxColor(MandelboxTraps traps, float3 baseHue, float scale) {
    float t1 = traps.minDist * 0.1f;
    float t2 = traps.avgFold * 0.5f;
    float t3 = traps.sphereHits * 0.1f;
    float t4 = (float)traps.iterations * 0.05f;

    float combined = t1 * 0.25f + t2 * 0.25f + t3 * 0.25f + t4 * 0.25f;
    combined += fabs(scale) * 0.1f;

    return palette(combined, baseHue);
}

// ============================================================================
// Main Mandelbox render kernel
// ============================================================================

__kernel void renderMandelbox(
    __global float* output,
    int imageWidth, int imageHeight,
    int tileOffsetX, int tileOffsetY, int tileSize,
    // Camera
    float4 camPos, float4 camQuat, float fov,
    // Fractal params
    float scale, float minRadius, float fixedRadius, float foldingLimit,
    int maxIterations, int maxRaySteps, float baseEpsilon,
    // Light direction
    float4 lightDir,
    // Light color and intensity
    float4 lightColor,
    // Ambient color
    float4 ambientColor,
    // Material base hue
    float4 materialHue,
    // Rendering quality
    float shadowSoftness, int shadowSteps, int aoSteps, float aoIntensity, float glowIntensity,
    float qualityMultiplier,
    // Specular
    float specularIntensity, float specularPower,
    // Render mode
    int renderMode,
    // Depth of Field
    int dofEnabled, float focalDistance, float aperture, int dofSamples
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
        MandelboxTraps traps;
        bool hit = false;
        float minDist = 1e10f;

        // Scale parameters by quality multiplier
        int effectiveMaxSteps = (int)((float)maxRaySteps * qualityMultiplier);
        float qualityEpsilon = baseEpsilon / qualityMultiplier;
        float qualityStepFactor = STEP_FACTOR / fmax(1.0f, qualityMultiplier * 0.5f);

        for (int i = 0; i < effectiveMaxSteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            float dist = mandelboxDE(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations, &traps);

            minDist = fmin(minDist, dist);

            float adaptiveEpsilon = computeAdaptiveEpsilon(totalDist, qualityEpsilon, qualityMultiplier);

            if (dist < adaptiveEpsilon) {
                hit = true;
                break;
            }

            float step = computeStep(dist, qualityMultiplier, STEP_FACTOR);
            totalDist += step;

            if (totalDist > MAX_DISTANCE) break;
        }

        // Shading
        float3 sampleColor;

        if (hit) {
            float3 normal = calcNormalMandelbox(pos, scale, minRadius, fixedRadius, foldingLimit, maxIterations);
            float3 viewDir = -rayDir;
            float3 baseColor = getMandelboxColor(traps, baseHue, scale);

            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcShadowMandelbox(pos + normal * shadowBias, light,
                                                shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                                scale, minRadius, fixedRadius, foldingLimit, maxIterations);
            float ao = calcAOMandelbox(pos, normal, aoSteps, scale, minRadius, fixedRadius, foldingLimit, maxIterations);

            // Use common rendering pipeline
            sampleColor = renderByMode(
                renderMode, baseColor, normal, light, viewDir,
                lightCol, lightInt, ambientCol, ambientInt,
                shadow, ao, aoIntensity, specularPower, specularIntensity,
                totalDist, traps.iterations, maxIterations
            );
        } else {
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
