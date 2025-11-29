/**
 * Kaleidoscopic IFS 3D fractal OpenCL kernel
 * Based on the classic Sierpinski tetrahedron folding algorithm.
 * Reference: Syntopia blog - Distance Estimated 3D Fractals (III): Folding Space
 *
 * This file contains only Kaleidoscopic-specific code.
 * Common functionality is provided by common.cl.
 */

// ============================================================================
// Orbit trap structure for rich coloring
// ============================================================================

typedef struct {
    float minDist;
    float sumDist;
    float avgFold;
    int iterations;
} KaleidoOrbitTraps;

// ============================================================================
// Kaleidoscopic IFS distance estimator with orbit traps
// Classic KIFS algorithm: fold + scale + translate
// ============================================================================

float kaleidoscopicDE(float3 pos, int maxIterations, float scale,
                      float offset, float foldAngleX, float foldAngleY,
                      KaleidoOrbitTraps* traps) {

    traps->minDist = 1e10f;
    traps->sumDist = 0.0f;
    traps->avgFold = 0.0f;
    traps->iterations = 0;

    float3 z = pos;
    float foldSum = 0.0f;

    int n;
    for (n = 0; n < maxIterations; n++) {
        // Classic KIFS folding - creates tetrahedral/kaleidoscopic symmetry
        // These are conditional reflections across planes

        // Fold 1: plane with normal (1, 1, 0)
        if (z.x + z.y < 0.0f) {
            float tmp = -z.y;
            z.y = -z.x;
            z.x = tmp;
            foldSum += 1.0f;
        }

        // Fold 2: plane with normal (1, 0, 1)
        if (z.x + z.z < 0.0f) {
            float tmp = -z.z;
            z.z = -z.x;
            z.x = tmp;
            foldSum += 1.0f;
        }

        // Fold 3: plane with normal (0, 1, 1)
        if (z.y + z.z < 0.0f) {
            float tmp = -z.z;
            z.z = -z.y;
            z.y = tmp;
            foldSum += 1.0f;
        }

        // Optional: apply small rotations for variety
        if (foldAngleX != 0.0f) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            float3 zr = (float3)(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
            z = zr;
        }
        if (foldAngleY != 0.0f) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            float3 zr = (float3)(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
            z = zr;
        }

        // Scale and translate
        // This is the key IFS transformation
        z = z * scale - offset * (scale - 1.0f);

        // Track orbit traps for coloring
        float dist = length3(z);
        traps->minDist = fmin(traps->minDist, dist);
        traps->sumDist += dist;
    }

    traps->iterations = n;
    traps->avgFold = foldSum / (float)n;

    // Distance estimation formula for KIFS
    // The accumulated scale factor is scale^n
    float r = length3(z);
    return r * pow(scale, -(float)n);
}

/**
 * Simplified DE for shadows/AO (same algorithm, no orbit traps)
 */
float kaleidoscopicDE_simple(float3 pos, int maxIterations, float scale,
                             float offset, float foldAngleX, float foldAngleY) {
    float3 z = pos;

    int n;
    for (n = 0; n < maxIterations; n++) {
        // Fold 1
        if (z.x + z.y < 0.0f) {
            float tmp = -z.y;
            z.y = -z.x;
            z.x = tmp;
        }

        // Fold 2
        if (z.x + z.z < 0.0f) {
            float tmp = -z.z;
            z.z = -z.x;
            z.x = tmp;
        }

        // Fold 3
        if (z.y + z.z < 0.0f) {
            float tmp = -z.z;
            z.z = -z.y;
            z.y = tmp;
        }

        // Optional rotations
        if (foldAngleX != 0.0f) {
            float c = cos(foldAngleX);
            float s = sin(foldAngleX);
            float3 zr = (float3)(z.x, c * z.y - s * z.z, s * z.y + c * z.z);
            z = zr;
        }
        if (foldAngleY != 0.0f) {
            float c = cos(foldAngleY);
            float s = sin(foldAngleY);
            float3 zr = (float3)(c * z.x + s * z.z, z.y, -s * z.x + c * z.z);
            z = zr;
        }

        // Scale and translate
        z = z * scale - offset * (scale - 1.0f);
    }

    float r = length3(z);
    return r * pow(scale, -(float)n);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalKaleido(float3 pos, int maxIterations, float scale,
                          float offset, float foldAngleX, float foldAngleY) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = 0.0005f;

    float d1 = kaleidoscopicDE_simple(pos + k1 * e, maxIterations, scale, offset, foldAngleX, foldAngleY);
    float d2 = kaleidoscopicDE_simple(pos + k2 * e, maxIterations, scale, offset, foldAngleX, foldAngleY);
    float d3 = kaleidoscopicDE_simple(pos + k3 * e, maxIterations, scale, offset, foldAngleX, foldAngleY);
    float d4 = kaleidoscopicDE_simple(pos + k4 * e, maxIterations, scale, offset, foldAngleX, foldAngleY);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadowKaleido(float3 ro, float3 rd, float mint, float maxt,
                         float softness, int shadowSteps,
                         int maxIterations, float scale,
                         float offset, float foldAngleX, float foldAngleY) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 p = ro + rd * t;
        float h = kaleidoscopicDE_simple(p, maxIterations, scale, offset, foldAngleX, foldAngleY);

        if (h < 0.0001f) return 0.0f;

        res = fmin(res, h * softness / t);
        t += clamp(h, 0.01f, 0.5f);
    }

    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAOKaleido(float3 pos, float3 normal, int aoSteps,
                     int maxIterations, float scale,
                     float offset, float foldAngleX, float foldAngleY) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = kaleidoscopicDE_simple(aoPos, maxIterations, scale, offset, foldAngleX, foldAngleY);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getKaleidoColor(KaleidoOrbitTraps traps, float3 baseHue) {
    float t1 = traps.minDist * 2.0f;
    float t2 = traps.sumDist * 0.005f;
    float t3 = traps.avgFold * 0.5f;
    float t4 = (float)traps.iterations * 0.1f;

    float combined = t1 * 0.3f + t2 * 0.3f + t3 * 0.2f + t4 * 0.2f;
    return palette(combined, baseHue);
}

// ============================================================================
// Main Kaleidoscopic IFS render kernel
// ============================================================================

__kernel void renderKaleidoscopic(
    __global float* output,
    int imageWidth, int imageHeight,
    int tileOffsetX, int tileOffsetY, int tileSize,
    // Camera
    float4 camPos, float4 camQuat, float fov,
    // Fractal params
    int maxIterations, float scale, float foldAngleX, float foldAngleY,
    float4 offsetVec, float minRadius,  // minRadius unused but kept for signature compatibility
    int maxRaySteps, float baseEpsilon,
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

    // Use offsetVec.x as the scalar offset (classic KIFS uses scalar offset)
    float offset = offsetVec.x;

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
        // Higher qualityMultiplier = smaller epsilon, more steps, better precision
        float totalDist = 0.0f;
        float3 pos = rayOrigin;
        KaleidoOrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;

        // Scale parameters by quality multiplier
        int effectiveMaxSteps = (int)((float)maxRaySteps * qualityMultiplier);
        float qualityEpsilon = baseEpsilon / qualityMultiplier;

        // For very high quality, increase fractal iterations near the surface
        int baseIterations = maxIterations;
        int highQualityIterations = (int)fmin((float)maxIterations + qualityMultiplier * 2.0f, 30.0f);

        for (int i = 0; i < effectiveMaxSteps; i++) {
            pos = rayOrigin + rayDir * totalDist;

            // Use more iterations when close to surface for ultimate detail
            int useIterations = (minDist < 0.1f) ? highQualityIterations : baseIterations;
            float dist = kaleidoscopicDE(pos, useIterations, scale, offset,
                                         foldAngleX, foldAngleY, &traps);

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
            float3 normal = calcNormalKaleido(pos, maxIterations, scale, offset, foldAngleX, foldAngleY);
            float3 viewDir = -rayDir;
            float3 baseColor = getKaleidoColor(traps, baseHue);

            // Calculate shadow and AO
            int reducedIter = max(6, maxIterations - 2);
            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcShadowKaleido(pos + normal * shadowBias, light,
                                              shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                              reducedIter, scale, offset, foldAngleX, foldAngleY);
            float ao = calcAOKaleido(pos, normal, aoSteps, reducedIter, scale, offset, foldAngleX, foldAngleY);

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
