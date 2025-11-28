/**
 * Mandelbulb 3D fractal OpenCL kernel
 * Enhanced ray marching with quaternion camera and improved lighting
 */

// ============================================================================
// Vector operations
// ============================================================================

float3 normalize3(float3 v) {
    float len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
    if (len < 0.0001f) return (float3)(0.0f, 0.0f, 1.0f);
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

/**
 * Rotate a vector by a quaternion
 * q = (w, x, y, z)
 */
float3 rotateByQuaternion(float3 v, float4 q) {
    // v' = q * v * q^-1
    // Optimized formula
    float3 qv = (float3)(q.y, q.z, q.w);
    float3 uv = cross3(qv, v);
    float3 uuv = cross3(qv, uv);
    return v + ((uv * q.x) + uuv) * 2.0f;
}

/**
 * Get camera ray direction from quaternion orientation
 */
float3 getCameraRay(float2 uv, float fov, float4 quat) {
    float fovScale = tan(fov * 0.5f);

    // Local ray direction (looking down +Z in camera space)
    float3 localRay = normalize3((float3)(uv.x * fovScale, uv.y * fovScale, 1.0f));

    // Rotate by camera quaternion
    return rotateByQuaternion(localRay, quat);
}

// ============================================================================
// Mandelbulb distance estimator
// ============================================================================

float mandelbulbDE(float3 pos, float power, int maxIterations, float bailout, float* orbitTrap) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    float trap = 1e10f;

    for (int i = 0; i < maxIterations; i++) {
        r = length3(z);

        // Orbit trap for coloring
        trap = fmin(trap, length3(z));

        if (r > bailout) break;

        // Convert to spherical coordinates
        float theta = acos(z.z / r);
        float phi = atan2(z.y, z.x);
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        // Scale and rotate the point
        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        // Convert back to cartesian
        z = (float3)(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    *orbitTrap = trap;
    return 0.5f * log(r) * r / dr;
}

// Simple version for shadow/AO calculations
float mandelbulbDE_simple(float3 pos, float power, int maxIterations, float bailout) {
    float3 z = pos;
    float dr = 1.0f;
    float r = 0.0f;

    for (int i = 0; i < maxIterations; i++) {
        r = length3(z);
        if (r > bailout) break;

        float theta = acos(z.z / r);
        float phi = atan2(z.y, z.x);
        dr = pow(r, power - 1.0f) * power * dr + 1.0f;

        float zr = pow(r, power);
        theta *= power;
        phi *= power;

        z = (float3)(
            sin(theta) * cos(phi),
            sin(theta) * sin(phi),
            cos(theta)
        ) * zr + pos;
    }

    return 0.5f * log(r) * r / dr;
}

// ============================================================================
// Surface normal calculation
// ============================================================================

float3 calcNormal(float3 pos, float power, int maxIterations, float bailout, float epsilon) {
    float2 e = (float2)(epsilon, 0.0f);

    float3 n = (float3)(
        mandelbulbDE_simple(pos + (float3)(e.x, e.y, e.y), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(e.x, e.y, e.y), power, maxIterations, bailout),
        mandelbulbDE_simple(pos + (float3)(e.y, e.x, e.y), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(e.y, e.x, e.y), power, maxIterations, bailout),
        mandelbulbDE_simple(pos + (float3)(e.y, e.y, e.x), power, maxIterations, bailout) -
        mandelbulbDE_simple(pos - (float3)(e.y, e.y, e.x), power, maxIterations, bailout)
    );

    return normalize3(n);
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcSoftShadow(float3 ro, float3 rd, float mint, float maxt,
                     float softness, float power, int maxIterations, float bailout) {
    float res = 1.0f;
    float t = mint;
    float ph = 1e10f;

    for (int i = 0; i < 64 && t < maxt; i++) {
        float3 pos = ro + rd * t;
        float h = mandelbulbDE_simple(pos, power, maxIterations, bailout);

        if (h < 0.0001f) {
            return 0.0f;
        }

        float y = h * h / (2.0f * ph);
        float d = sqrt(h * h - y * y);
        res = fmin(res, softness * d / fmax(0.0f, t - y));
        ph = h;
        t += h * 0.5f;
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
        float hr = 0.01f + 0.12f * (float)i / (float)aoSteps;
        float3 aoPos = pos + normal * hr;
        float dd = mandelbulbDE_simple(aoPos, power, maxIterations, bailout);
        ao += (hr - dd) * scale;
        scale *= 0.7f;
    }

    return clamp(1.0f - 3.0f * ao, 0.0f, 1.0f);
}

// ============================================================================
// Color palette
// ============================================================================

float3 palette(float t) {
    // Beautiful fractal coloring
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
    __global float* output,      // Output RGBA buffer
    int imageWidth,              // Full image width
    int imageHeight,             // Full image height
    int tileOffsetX,             // Tile X offset in full image
    int tileOffsetY,             // Tile Y offset in full image
    int tileSize,                // Size of the tile being rendered
    float4 camPos,               // Camera position (x, y, z, unused)
    float4 camQuat,              // Camera quaternion (w, x, y, z)
    float fov,                   // Field of view in radians
    float power,                 // Mandelbulb power
    int maxIterations,           // Fractal iterations
    int maxRaySteps,             // Ray marching steps
    float bailout,               // Escape radius
    float epsilon,               // Surface threshold
    float4 lightDir,             // Light direction (x, y, z, unused)
    float shadowSoftness,        // Soft shadow factor
    int aoSteps,                 // AO sample count
    float aoIntensity,           // AO strength
    float glowIntensity          // Glow effect strength
) {
    int localX = get_global_id(0);
    int localY = get_global_id(1);

    // Skip if outside tile
    if (localX >= tileSize || localY >= tileSize) return;

    // Calculate actual pixel coordinates in full image
    int pixelX = tileOffsetX + localX;
    int pixelY = tileOffsetY + localY;

    // Skip if outside image
    if (pixelX >= imageWidth || pixelY >= imageHeight) return;

    // Output buffer index (within tile)
    int outputIdx = (localY * tileSize + localX) * 4;

    // Calculate normalized screen coordinates (-1 to 1)
    float aspectRatio = (float)imageWidth / (float)imageHeight;
    float u = (2.0f * ((float)pixelX + 0.5f) / (float)imageWidth - 1.0f) * aspectRatio;
    float v = 1.0f - 2.0f * ((float)pixelY + 0.5f) / (float)imageHeight;

    // Camera setup using quaternion
    float3 rayOrigin = (float3)(camPos.x, camPos.y, camPos.z);
    float3 rayDir = getCameraRay((float2)(u, v), fov, camQuat);

    // Ray marching
    float totalDist = 0.0f;
    float3 pos;
    float dist = 0.0f;
    float orbitTrap = 0.0f;
    bool hit = false;
    float minDist = 1e10f;
    int steps = 0;

    for (int i = 0; i < maxRaySteps; i++) {
        pos = rayOrigin + rayDir * totalDist;
        dist = mandelbulbDE(pos, power, maxIterations, bailout, &orbitTrap);

        minDist = fmin(minDist, dist);
        steps = i;

        if (dist < epsilon) {
            hit = true;
            break;
        }

        totalDist += dist;

        if (totalDist > 50.0f) break;
    }

    // Shading
    float4 color;

    if (hit) {
        float3 normal = calcNormal(pos, power, maxIterations, bailout, epsilon);
        float3 light = normalize3((float3)(lightDir.x, lightDir.y, lightDir.z));

        // Material color from orbit trap
        float3 baseColor = palette(orbitTrap * 2.0f);

        // Diffuse lighting
        float NdotL = max(dot3(normal, light), 0.0f);
        float diffuse = NdotL;

        // Specular (Blinn-Phong)
        float3 viewDir = -rayDir;
        float3 halfVec = normalize3(light + viewDir);
        float specular = pow(max(dot3(normal, halfVec), 0.0f), 32.0f) * 0.5f;

        // Soft shadows
        float shadow = calcSoftShadow(pos + normal * epsilon * 2.0f, light,
                                      0.01f, 10.0f, shadowSoftness,
                                      power, maxIterations, bailout);

        // Ambient occlusion
        float ao = calcAO(pos, normal, aoSteps, power, maxIterations, bailout);
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
        float fogAmount = 1.0f - exp(-totalDist * 0.05f);
        float3 fogColor = (float3)(0.02f, 0.02f, 0.05f);
        finalColor = mix(finalColor, fogColor, fogAmount);

        // Tone mapping
        finalColor = finalColor / (finalColor + 1.0f);

        // Gamma correction
        finalColor = pow(finalColor, (float3)(0.4545f, 0.4545f, 0.4545f));

        color = (float4)(finalColor.x, finalColor.y, finalColor.z, 1.0f);
    } else {
        // Background with glow effect
        float glow = exp(-minDist * 10.0f) * glowIntensity;
        float3 glowColor = palette(0.5f) * glow;

        // Gradient background
        float t = rayDir.y * 0.5f + 0.5f;
        float3 bgColor = mix(
            (float3)(0.02f, 0.02f, 0.05f),
            (float3)(0.05f, 0.05f, 0.1f),
            t
        );

        // Add subtle stars
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