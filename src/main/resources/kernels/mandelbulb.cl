/**
 * Mandelbulb 3D fractal OpenCL kernel
 * Enhanced ray marching with adaptive precision
 */

// ============================================================================
// Constants for ray marching quality
// ============================================================================

#define STEP_FACTOR 0.5f          // Conservative step multiplier (0.5-0.7 recommended)
#define MIN_EPSILON 1e-7f         // Minimum epsilon for close-up precision
#define MAX_EPSILON 1e-3f         // Maximum epsilon for distant objects
#define EPSILON_FACTOR 1e-4f      // Epsilon scales with distance: epsilon = max(MIN_EPSILON, totalDist * EPSILON_FACTOR)
#define MAX_DISTANCE 100.0f       // Maximum ray travel distance
#define NORMAL_EPSILON_FACTOR 0.5f // Normal calculation uses smaller epsilon

// ============================================================================
// Vector operations
// ============================================================================

float3 normalize3(float3 v) {
    float len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    if (len < 1e-10f) return (float3)(0.0f, 0.0f, 1.0f);
    return v / len;
}

float length3(float3 v) {
    return sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
}

float dot3(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

float3 cross3(float3 a, float3 b) {
    return (float3)(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

// Fractional part (x - floor(x))
float fract1(float x) {
    return x - floor(x);
}

float3 fract3(float3 v) {
    return v - floor(v);
}

// ============================================================================
// Quaternion operations for camera
// ============================================================================

float3 rotateByQuaternion(float3 v, float4 q) {
    float3 qv = (float3)(q.y, q.z, q.w);
    float3 uv = cross3(qv, v);
    float3 uuv = cross3(qv, uv);
    return v + ((uv * q.x) + uuv) * 2.0f;
}

float3 getCameraRay(float2 uv, float fov, float4 quat) {
    float fovScale = tan(fov * 0.5f);
    float3 localRay = normalize3((float3)(uv.x * fovScale, uv.y * fovScale, 1.0f));
    return rotateByQuaternion(localRay, quat);
}

// ============================================================================
// Mandelbulb distance estimator with dynamic iterations
// ============================================================================

/**
 * Main distance estimator with orbit trap for coloring
 * Uses more iterations when closer to surface for better detail
 */
float mandelbulbDE(float3 pos, float power, int baseIterations, float bailout,
                   float distanceHint, float* orbitTrap) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;
    float trap = 1e10f;

    // Dynamic iterations: more iterations when closer
    // distanceHint < 0.1 -> up to 2x iterations
    int dynamicIter = baseIterations;
    if (distanceHint < 0.1f && distanceHint > 0.0f) {
        dynamicIter = baseIterations + (int)((0.1f - distanceHint) * 10.0f * (float)baseIterations);
        dynamicIter = min(dynamicIter, baseIterations * 2);
    }

    for (int i = 0; i < dynamicIter; i++) {
        r = length3(z);

        // Orbit trap for coloring - track minimum distance to origin
        trap = fmin(trap, r);

        if (r > bailout) break;

        // Spherical coordinates
        float theta = acos(clamp(z.z / r, -1.0f, 1.0f));
        float phi = atan2(z.y, z.x);

        // Derivative
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        // Scale and rotate
        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        // Back to cartesian
        float sinTheta = sin(theta);
        z = (float3)(
            sinTheta * cos(phi),
            sinTheta * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    *orbitTrap = trap;

    // Distance estimation with safety clamp
    float de = 0.5f * log(r) * r / dr;
    return fmax(de, 1e-7f); // Prevent zero/negative distances
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
// Surface normal calculation with adaptive epsilon
// ============================================================================

float3 calcNormal(float3 pos, float power, int maxIterations, float bailout, float epsilon) {
    // Use smaller epsilon for normal calculation for sharper details
    float e = epsilon * NORMAL_EPSILON_FACTOR;
    e = fmax(e, MIN_EPSILON);

    // Central differences for better accuracy
    float3 n = (float3)(
        mandelbulbDE_simple(pos + (float3)(e, 0, 0), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(e, 0, 0), power, maxIterations, bailout),
        mandelbulbDE_simple(pos + (float3)(0, e, 0), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(0, e, 0), power, maxIterations, bailout),
        mandelbulbDE_simple(pos + (float3)(0, 0, e), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(0, 0, e), power, maxIterations, bailout)
    );

    return normalize3(n);
}

// ============================================================================
// Soft shadows with improved precision
// ============================================================================

float calcSoftShadow(float3 ro, float3 rd, float mint, float maxt,
                     float softness, float power, int maxIterations, float bailout) {
    float res = 1.0f;
    float t = mint;
    float ph = 1e10f;

    for (int i = 0; i < 48 && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelbulbDE_simple(pos, power, maxIterations, bailout);

        if (h < MIN_EPSILON) {
            return 0.0f;
        }

        // Improved soft shadow calculation
        float y = h * h / (2.0f * ph);
        float d = sqrt(fmax(0.0f, h * h - y * y));
        res = fmin(res, softness * d / fmax(1e-6f, t - y));
        ph = h;

        // Step with safety factor
        t += h * STEP_FACTOR;
    }

    return clamp(res, 0.0f, 1.0f);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAO(float3 pos, float3 normal, int aoSteps, float power, int maxIterations, float bailout) {
    float ao = 0.0f;
    float scale = 1.0f;

    for (int i = 0; i < aoSteps; i++) {
        float hr = 0.005f + 0.1f * (float)(i + 1) / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelbulbDE_simple(aoPos, power, maxIterations, bailout);
        ao += (hr - dd) * scale;
        scale *= 0.65f;
    }

    return clamp(1.0f - 4.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Color palette
// ============================================================================

float3 palette(float t) {
    float3 a = (float3)(0.5f, 0.5f, 0.5f);
    float3 b = (float3)(0.5f, 0.5f, 0.5f);
    float3 c = (float3)(1.0f, 1.0f, 1.0f);
    float3 d = (float3)(0.0f, 0.33f, 0.67f);

    return a + b * cos(6.28318f * (c * t + d));
}

// ============================================================================
// Main kernel
// ============================================================================

__kernel void renderMandelbulb(
    __global float* output,
    int imageWidth,
    int imageHeight,
    int tileOffsetX,
    int tileOffsetY,
    int tileSize,
    float4 camPos,
    float4 camQuat,
    float fov,
    float power,
    int maxIterations,
    int maxRaySteps,
    float bailout,
    float baseEpsilon,           // Base epsilon (will be adapted)
    float4 lightDir,
    float shadowSoftness,
    int aoSteps,
    float aoIntensity,
    float glowIntensity
) {
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

    // Camera ray
    float3 rayOrigin = (float3)(camPos.x, camPos.y, camPos.z);
    float3 rayDir = getCameraRay((float2)(u, v), fov, camQuat);

    // ========================================================================
    // Ray marching with adaptive epsilon
    // ========================================================================

    float totalDist = 0.0f;
    float3 pos = rayOrigin;
    float dist = 0.0f;
    float orbitTrap = 0.0f;
    bool hit = false;
    float minDist = 1e10f;
    float lastDist = 1e10f;

    for (int i = 0; i < maxRaySteps; i++) {
        pos = rayOrigin + rayDir * totalDist;

        // Get distance estimate with dynamic iterations based on proximity
        dist = mandelbulbDE(pos, power, maxIterations, bailout, lastDist, &orbitTrap);

        // Track minimum distance for glow effect
        minDist = fmin(minDist, dist);
        lastDist = dist;

        // Adaptive epsilon based on distance traveled
        // Closer = more precision, farther = less precision needed
        float adaptiveEpsilon = fmax(MIN_EPSILON, totalDist * EPSILON_FACTOR);
        adaptiveEpsilon = fmin(adaptiveEpsilon, MAX_EPSILON);

        // Also consider base epsilon from user settings
        adaptiveEpsilon = fmax(adaptiveEpsilon, baseEpsilon * 0.1f);

        if (dist < adaptiveEpsilon) {
            hit = true;
            break;
        }

        // Step with conservative factor to avoid overshooting fine details
        float step = dist * STEP_FACTOR;

        // Minimum step to ensure progress
        step = fmax(step, MIN_EPSILON);

        totalDist += step;

        if (totalDist > MAX_DISTANCE) break;
    }

    // ========================================================================
    // Shading
    // ========================================================================

    float4 color;

    // Epsilon for shading calculations (based on final distance)
    float shadingEpsilon = fmax(MIN_EPSILON, totalDist * EPSILON_FACTOR * 0.5f);

    if (hit) {
        float3 normal = calcNormal(pos, power, maxIterations, bailout, shadingEpsilon);
        float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));

        // Material color from orbit trap
        float3 baseColor = palette(orbitTrap * 2.0f);

        // Diffuse
        float NdotL = max(dot3(normal, light), 0.0f);
        float diffuse = NdotL;

        // Specular (Blinn-Phong)
        float3 viewDir = -rayDir;
        float3 halfVec = normalize3(light + viewDir);
        float specular = pow(max(dot3(normal, halfVec), 0.0f), 32.0f) * 0.5f;

        // Soft shadows
        float shadowOffset = shadingEpsilon * 10.0f;
        float shadow = calcSoftShadow(pos + normal * shadowOffset, light,
                                      shadowOffset, 10.0f, shadowSoftness,
                                      power, max(8, maxIterations / 2), bailout);

        // Ambient occlusion
        float ao = calcAO(pos, normal, aoSteps, power, max(8, maxIterations / 2), bailout);
        ao = mix(1.0f, ao, aoIntensity);

        // Rim lighting
        float rim = pow(1.0f - max(dot3(normal, viewDir), 0.0f), 3.0f) * 0.3f;

        // Combine lighting
        float3 ambient = baseColor * 0.15f;
        float3 diffuseColor = baseColor * diffuse * shadow;
        float3 specularColor = (float3)(1.0f, 1.0f, 1.0f) * specular * shadow;
        float3 rimColor = baseColor * rim;

        float3 finalColor = (ambient + diffuseColor + specularColor + rimColor) * ao;

        // Distance fog
        float fogAmount = 1.0f - exp(-totalDist * 0.03f);
        float3 fogColor = (float3)(0.02f, 0.02f, 0.05f);
        finalColor = mix(finalColor, fogColor, fogAmount);

        // Tone mapping (ACES-like)
        finalColor = finalColor * (finalColor + 0.5f) / (finalColor * (finalColor + 0.5f) + 0.5f);

        // Gamma correction
        finalColor = pow(finalColor, (float3)(0.4545f, 0.4545f, 0.4545f));

        color = (float4)(finalColor.x, finalColor.y, finalColor.z, 1.0f);
    } else {
        // Background with glow
        float glow = exp(-minDist * 10.0f) * glowIntensity;
        float3 glowColor = palette(0.5f) * glow;

        // Gradient background
        float t = rayDir.y * 0.5f + 0.5f;
        float3 bgColor = mix(
            (float3)(0.02f, 0.02f, 0.05f),
            (float3)(0.05f, 0.05f, 0.1f),
            t
        );

        // Subtle stars
        float stars = 0.0f;
        float3 starDir = rayDir * 100.0f;
        float starNoise = fract1(sin(dot3(floor(starDir), (float3)(12.9898f, 78.233f, 45.164f))) * 43758.5453f);
        if (starNoise > 0.995f) {
            stars = (starNoise - 0.995f) * 200.0f;
        }

        float3 finalBg = bgColor + glowColor + (float3)(stars, stars, stars);

        color = (float4)(finalBg.x, finalBg.y, finalBg.z, 1.0f);
    }

    // Write output
    output[outputIdx] = clamp(color.x, 0.0f, 1.0f);
    output[outputIdx + 1] = clamp(color.y, 0.0f, 1.0f);
    output[outputIdx + 2] = clamp(color.z, 0.0f, 1.0f);
    output[outputIdx + 3] = 1.0f;
}
