/**
 * Menger Sponge 3D fractal OpenCL kernel
 * Classic IQ algorithm implementation
 */

// ============================================================================
// Orbit trap structure for coloring
// ============================================================================

typedef struct {
    float minDist;
    float trap;
    int iterations;
} MengerOrbitTraps;

// ============================================================================
// GLSL-style mod (x - y * floor(x/y))
// ============================================================================

float glsl_mod(float x, float y) {
    return x - y * floor(x / y);
}

float3 glsl_mod3(float3 x, float y) {
    return (float3)(glsl_mod(x.x, y), glsl_mod(x.y, y), glsl_mod(x.z, y));
}

// ============================================================================
// Box SDF
// ============================================================================

float sdBox(float3 p, float3 b) {
    float3 d = fabs(p) - b;
    return fmin(fmax(d.x, fmax(d.y, d.z)), 0.0f) + length(fmax(d, (float3)(0.0f)));
}

// ============================================================================
// Cross SDF (the shape we subtract at each iteration)
// ============================================================================

float sdCross(float3 p) {
    float da = fmax(fabs(p.x), fabs(p.y));
    float db = fmax(fabs(p.y), fabs(p.z));
    float dc = fmax(fabs(p.z), fabs(p.x));
    return fmin(fmin(da, db), dc) - 1.0f;
}

// ============================================================================
// Menger Sponge DE - Classic IQ algorithm
// ============================================================================

float mengerDE(float3 p, int maxIterations, float scale, float3 offset,
               float distanceHint, MengerOrbitTraps* traps) {

    float d = sdBox(p, (float3)(1.0f));

    traps->minDist = 1e10f;
    traps->trap = 0.0f;
    traps->iterations = 0;

    float s = 1.0f;

    for (int m = 0; m < maxIterations; m++) {
        // Map to repeating [-1, 1] space
        float3 a = glsl_mod3(p * s, 2.0f) - (float3)(1.0f);
        s *= scale;

        // Compute cross shape
        float3 r = (float3)(1.0f) - scale * fabs(a);

        // Cross distance
        float c = sdCross(r) / s;

        // Subtract cross from box
        d = fmax(d, c);

        // Orbit trap for coloring
        traps->minDist = fmin(traps->minDist, length(r));
        traps->trap += length(a);
        traps->iterations = m + 1;
    }

    traps->trap /= (float)maxIterations;

    return d;
}

// ============================================================================
// Simple DE for shadows/AO
// ============================================================================

float mengerDE_simple(float3 p, int maxIterations, float scale, float3 offset) {
    float d = sdBox(p, (float3)(1.0f));
    float s = 1.0f;

    for (int m = 0; m < maxIterations; m++) {
        float3 a = glsl_mod3(p * s, 2.0f) - (float3)(1.0f);
        s *= scale;
        float3 r = (float3)(1.0f) - scale * fabs(a);
        float c = sdCross(r) / s;
        d = fmax(d, c);
    }

    return d;
}

// ============================================================================
// Normal calculation
// ============================================================================

float3 calcNormalMenger(float3 pos, int maxIterations, float scale, float3 offset) {
    float e = 0.0001f;
    float3 n;
    n.x = mengerDE_simple(pos + (float3)(e, 0, 0), maxIterations, scale, offset) -
          mengerDE_simple(pos - (float3)(e, 0, 0), maxIterations, scale, offset);
    n.y = mengerDE_simple(pos + (float3)(0, e, 0), maxIterations, scale, offset) -
          mengerDE_simple(pos - (float3)(0, e, 0), maxIterations, scale, offset);
    n.z = mengerDE_simple(pos + (float3)(0, 0, e), maxIterations, scale, offset) -
          mengerDE_simple(pos - (float3)(0, 0, e), maxIterations, scale, offset);
    return normalize(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadowMenger(float3 ro, float3 rd, float mint, float maxt,
                       float k, int steps, int maxIterations, float scale, float3 offset) {
    float res = 1.0f;
    float t = mint;
    for (int i = 0; i < steps && t < maxt; i++) {
        float h = mengerDE_simple(ro + rd * t, maxIterations, scale, offset);
        if (h < 0.001f) return 0.0f;
        res = fmin(res, k * h / t);
        t += clamp(h, 0.01f, 0.2f);
    }
    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAOMenger(float3 pos, float3 nor, int maxIterations, float scale, float3 offset) {
    float occ = 0.0f;
    float sca = 1.0f;
    for (int i = 0; i < 5; i++) {
        float h = 0.01f + 0.12f * (float)i / 4.0f;
        float d = mengerDE_simple(pos + h * nor, maxIterations, scale, offset);
        occ += (h - d) * sca;
        sca *= 0.95f;
    }
    return clamp(1.0f - 3.0f * occ, 0.0f, 1.0f);
}

// ============================================================================
// Color from orbit trap
// ============================================================================

float3 getMengerColor(MengerOrbitTraps traps, float3 baseHue) {
    float t = traps.trap * 0.5f + traps.minDist * 0.3f;
    return palette(t, baseHue);
}

// ============================================================================
// Main render kernel
// ============================================================================

__kernel void renderMenger(
    __global float* output,
    int imageWidth, int imageHeight,
    int tileOffsetX, int tileOffsetY, int tileSize,
    float4 camPos, float4 camQuat, float fov,
    int maxIterations, float scale, float4 offset,
    int maxRaySteps, float baseEpsilon,
    float4 lightDir, float4 lightColor, float4 ambientColor, float4 materialHue,
    float shadowSoftness, int shadowSteps, int aoSteps, float aoIntensity, float glowIntensity,
    float specularIntensity, float specularPower,
    int renderMode,
    int dofEnabled, float focalDistance, float aperture, int dofSamples
) {
    int localX = get_global_id(0);
    int localY = get_global_id(1);
    if (localX >= tileSize || localY >= tileSize) return;

    int pixelX = tileOffsetX + localX;
    int pixelY = tileOffsetY + localY;
    if (pixelX >= imageWidth || pixelY >= imageHeight) return;

    int outputIdx = (localY * tileSize + localX) * 4;

    float aspectRatio = (float)imageWidth / (float)imageHeight;
    float u = (2.0f * ((float)pixelX + 0.5f) / (float)imageWidth - 1.0f) * aspectRatio;
    float v = 1.0f - 2.0f * ((float)pixelY + 0.5f) / (float)imageHeight;

    float3 ro = (float3)(camPos.x, camPos.y, camPos.z);
    float3 rd = getCameraRay((float2)(u, v), fov, camQuat);
    float3 offsetVec = (float3)(offset.x, offset.y, offset.z);

    // Ray march
    float t = 0.0f;
    float3 pos;
    MengerOrbitTraps traps;
    bool hit = false;
    float minDist = 1e10f;

    for (int i = 0; i < maxRaySteps; i++) {
        pos = ro + rd * t;
        float d = mengerDE(pos, maxIterations, scale, offsetVec, 0.0f, &traps);
        minDist = fmin(minDist, d);

        if (d < baseEpsilon * (1.0f + t * 0.1f)) {
            hit = true;
            break;
        }
        t += d * 0.9f;
        if (t > 100.0f) break;
    }

    // Shading
    float3 col;
    float3 light = normalize((float3)(lightDir.x, lightDir.y, lightDir.z));
    float3 lcol = (float3)(lightColor.x, lightColor.y, lightColor.z);
    float3 acol = (float3)(ambientColor.x, ambientColor.y, ambientColor.z);
    float3 hue = (float3)(materialHue.x, materialHue.y, materialHue.z);

    if (hit) {
        float3 nor = calcNormalMenger(pos, maxIterations, scale, offsetVec);
        float3 baseCol = getMengerColor(traps, hue);

        // Diffuse
        float dif = fmax(dot(nor, light), 0.0f);

        // Specular
        float3 hal = normalize(light - rd);
        float spe = pow(fmax(dot(nor, hal), 0.0f), specularPower) * specularIntensity;

        // Shadow
        float sha = calcShadowMenger(pos + nor * 0.01f, light, 0.01f, 10.0f,
                                     shadowSoftness, shadowSteps, maxIterations - 1, scale, offsetVec);

        // AO
        float ao = calcAOMenger(pos, nor, maxIterations - 1, scale, offsetVec);
        ao = mix(1.0f, ao, aoIntensity);

        if (renderMode == RENDER_NORMALS) {
            col = nor * 0.5f + 0.5f;
        } else if (renderMode == RENDER_DEPTH) {
            col = (float3)(exp(-t * 0.3f));
        } else if (renderMode == RENDER_AO) {
            col = (float3)(ao);
        } else if (renderMode == RENDER_SHADOWS) {
            col = (float3)(sha);
        } else if (renderMode == RENDER_ORBIT_TRAP) {
            col = baseCol;
        } else {
            // Final
            col = baseCol * (acol * ambientColor.w + lcol * dif * lightColor.w * sha) * ao;
            col += lcol * spe * sha;

            // Fog
            col = mix(col, acol * 0.1f, 1.0f - exp(-t * 0.03f));

            // Tone map & gamma
            col = col / (col + 1.0f);
            col = pow(fmax(col, (float3)(0.0f)), (float3)(0.4545f));
        }
    } else {
        // Background with glow
        float glow = exp(-minDist * 5.0f) * glowIntensity;
        col = acol * 0.1f + palette(0.5f, hue) * glow;
    }

    output[outputIdx] = clamp(col.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(col.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(col.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}
