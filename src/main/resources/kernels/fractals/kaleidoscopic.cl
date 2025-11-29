/**
 * Kaleidoscopic IFS 3D fractal OpenCL kernel (Refactored)
 * Creates symmetrical fractals using reflection and scaling operations.
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
    float foldCount;
    float escape;
    int iterations;
} KaleidoOrbitTraps;

// ============================================================================
// Sphere fold (like Mandelbox)
// ============================================================================

void sphereFoldKaleido(float3* z, float* dz, float minRadius, float fixedRadius) {
    float r2 = dot3(*z, *z);
    float minR2 = minRadius * minRadius;
    float fixedR2 = fixedRadius * fixedRadius;

    if (r2 < minR2) {
        float temp = fixedR2 / minR2;
        *z *= temp;
        *dz *= temp;
    } else if (r2 < fixedR2) {
        float temp = fixedR2 / r2;
        *z *= temp;
        *dz *= temp;
    }
}

// ============================================================================
// Kaleidoscopic IFS distance estimator with orbit traps
// ============================================================================

float kaleidoscopicDE(float3 pos, int maxIterations, float scale,
                      float foldAngleX, float foldAngleY,
                      float3 offset, float minRadius,
                      float distanceHint, KaleidoOrbitTraps* traps) {

    traps->minDist = 1e10f;
    traps->sumDist = 0.0f;
    traps->foldCount = 0.0f;
    traps->escape = 0.0f;
    traps->iterations = 0;

    float3 z = pos;
    float dz = 1.0f;

    float3 foldN1 = (float3)(cos(foldAngleX), sin(foldAngleX), 0.0f);
    float3 foldN2 = (float3)(0.0f, cos(foldAngleY), sin(foldAngleY));

    int dynIter = maxIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynIter = min(maxIterations + 3, maxIterations * 2);
    }

    float foldCount = 0.0f;

    for (int i = 0; i < dynIter; i++) {
        z = fabs(z);

        float d1 = dot3(z, foldN1);
        if (d1 < 0.0f) {
            z -= 2.0f * d1 * foldN1;
            foldCount += 1.0f;
        }

        float d2 = dot3(z, foldN2);
        if (d2 < 0.0f) {
            z -= 2.0f * d2 * foldN2;
            foldCount += 1.0f;
        }

        if (minRadius > 0.001f) {
            sphereFoldKaleido(&z, &dz, minRadius, 1.0f);
        }

        z = z * scale - offset * (scale - 1.0f);
        dz = dz * fabs(scale) + 1.0f;

        float dist = length3(z);
        traps->minDist = fmin(traps->minDist, dist / dz);
        traps->sumDist += dist;

        traps->iterations = i + 1;

        if (dist > 1000.0f) {
            traps->escape = dist;
            break;
        }
    }

    traps->foldCount = foldCount / (float)traps->iterations;

    float de = (length3(z) - 2.0f) / dz;
    return fmax(de, 1e-7f);
}

/**
 * Simplified DE for shadows/AO
 */
float kaleidoscopicDE_simple(float3 pos, int maxIterations, float scale,
                             float foldAngleX, float foldAngleY,
                             float3 offset, float minRadius) {
    float3 z = pos;
    float dz = 1.0f;

    float3 foldN1 = (float3)(cos(foldAngleX), sin(foldAngleX), 0.0f);
    float3 foldN2 = (float3)(0.0f, cos(foldAngleY), sin(foldAngleY));

    for (int i = 0; i < maxIterations; i++) {
        z = fabs(z);

        float d1 = dot3(z, foldN1);
        if (d1 < 0.0f) z -= 2.0f * d1 * foldN1;

        float d2 = dot3(z, foldN2);
        if (d2 < 0.0f) z -= 2.0f * d2 * foldN2;

        if (minRadius > 0.001f) {
            sphereFoldKaleido(&z, &dz, minRadius, 1.0f);
        }

        z = z * scale - offset * (scale - 1.0f);
        dz = dz * fabs(scale) + 1.0f;

        if (length3(z) > 1000.0f) break;
    }

    float de = (length3(z) - 2.0f) / dz;
    return fmax(de, 1e-7f);
}

// ============================================================================
// Normal calculation (tetrahedron method)
// ============================================================================

float3 calcNormalKaleido(float3 pos, int maxIterations, float scale,
                          float foldAngleX, float foldAngleY,
                          float3 offset, float minRadius) {
    const float3 k1 = (float3)( 1.0f, -1.0f, -1.0f);
    const float3 k2 = (float3)(-1.0f, -1.0f,  1.0f);
    const float3 k3 = (float3)(-1.0f,  1.0f, -1.0f);
    const float3 k4 = (float3)( 1.0f,  1.0f,  1.0f);

    float e = NORMAL_EPSILON;

    float d1 = kaleidoscopicDE_simple(pos + k1 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d2 = kaleidoscopicDE_simple(pos + k2 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d3 = kaleidoscopicDE_simple(pos + k3 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
    float d4 = kaleidoscopicDE_simple(pos + k4 * e, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);

    float3 n = k1 * d1 + k2 * d2 + k3 * d3 + k4 * d4;
    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadowKaleido(float3 ro, float3 rd, float mint, float maxt,
                         float softness, int shadowSteps,
                         int maxIterations, float scale,
                         float foldAngleX, float foldAngleY,
                         float3 offset, float minRadius) {
    float res = 1.0f;
    float t = mint;

    for (int i = 0; i < shadowSteps && t < maxt; i++) {
        float3 p = ro + rd * t;
        float h = kaleidoscopicDE_simple(p, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);

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
                     float foldAngleX, float foldAngleY,
                     float3 offset, float minRadius) {
    float ao = 0.0f;
    float sca = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.01f + 0.12f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = kaleidoscopicDE_simple(aoPos, maxIterations, scale, foldAngleX, foldAngleY, offset, minRadius);
        ao += (hr - dd) * sca;
        sca *= 0.6f;
    }

    return clamp(1.0f - 5.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Material color from orbit traps
// ============================================================================

float3 getKaleidoColor(KaleidoOrbitTraps traps, float3 baseHue) {
    float t1 = traps.minDist * 4.0f;
    float t2 = traps.sumDist * 0.001f;
    float t3 = traps.foldCount;
    float t4 = (float)traps.iterations * 0.1f;

    float combined = t1 * 0.35f + t2 * 0.25f + t3 * 0.2f + t4 * 0.2f;
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
    float4 offset, float minRadius,
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

    float3 offsetVec = (float3)(offset.x, offset.y, offset.z);

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

        // Ray marching
        float totalDist = 0.0f;
        float3 pos = rayOrigin;
        KaleidoOrbitTraps traps;
        bool hit = false;
        float minDist = 1e10f;
        float lastDist = 1e10f;

        for (int i = 0; i < maxRaySteps; i++) {
            pos = rayOrigin + rayDir * totalDist;
            float dist = kaleidoscopicDE(pos, maxIterations, scale, foldAngleX, foldAngleY,
                                         offsetVec, minRadius, lastDist, &traps);

            minDist = fmin(minDist, dist);
            lastDist = dist;

            float adaptiveEpsilon = fmax(MIN_EPSILON, totalDist * EPSILON_FACTOR);
            adaptiveEpsilon = fmin(adaptiveEpsilon, MAX_EPSILON);
            adaptiveEpsilon = fmax(adaptiveEpsilon, baseEpsilon * 0.1f);

            if (dist < adaptiveEpsilon) {
                hit = true;
                break;
            }

            float step = dist * STEP_FACTOR;
            step = fmax(step, MIN_EPSILON);
            totalDist += step;

            if (totalDist > MAX_DISTANCE) break;
        }

        // Shading
        float3 sampleColor;

        if (hit) {
            float3 normal = calcNormalKaleido(pos, maxIterations, scale, foldAngleX, foldAngleY, offsetVec, minRadius);
            float3 viewDir = -rayDir;
            float3 baseColor = getKaleidoColor(traps, baseHue);

            // Calculate shadow and AO
            int reducedIter = max(6, maxIterations - 3);
            float shadowBias = 0.001f + totalDist * 0.001f;
            float shadow = calcShadowKaleido(pos + normal * shadowBias, light,
                                              shadowBias, 15.0f, shadowSoftness, shadowSteps,
                                              reducedIter, scale, foldAngleX, foldAngleY, offsetVec, minRadius);
            float ao = calcAOKaleido(pos, normal, aoSteps, reducedIter, scale, foldAngleX, foldAngleY, offsetVec, minRadius);

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