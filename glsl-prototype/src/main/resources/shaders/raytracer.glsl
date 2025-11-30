/**
 * Generic Raytracer for Fractal Rendering
 *
 * This file is FRACTAL-AGNOSTIC. It uses:
 * - DE(pos, trap) - defined by the fractal
 * - DE_simple(pos) - defined by the fractal
 * - getColor(trap) - defined by the fractal
 *
 * Because GLSL has global uniforms, this works seamlessly!
 * (Unlike OpenCL where we had macro nightmares)
 */

in vec2 fragCoord;
in vec2 uv;

out vec4 FragColor;

// ============================================================================
// Ray marching settings
// ============================================================================

const int MAX_STEPS = 256;
const float BASE_EPSILON = 0.0001;

// ============================================================================
// Normal calculation (tetrahedron method)
// Uses DE_simple() which is defined by the fractal
// ============================================================================

vec3 calcNormal(vec3 pos) {
    const vec3 k1 = vec3( 1.0, -1.0, -1.0);
    const vec3 k2 = vec3(-1.0, -1.0,  1.0);
    const vec3 k3 = vec3(-1.0,  1.0, -1.0);
    const vec3 k4 = vec3( 1.0,  1.0,  1.0);
    const float e = NORMAL_EPSILON;

    return normalize(
        k1 * DE_simple(pos + k1 * e) +
        k2 * DE_simple(pos + k2 * e) +
        k3 * DE_simple(pos + k3 * e) +
        k4 * DE_simple(pos + k4 * e)
    );
}

// ============================================================================
// Soft shadows
// ============================================================================

float calcShadow(vec3 ro, vec3 rd, float mint, float maxt, float k) {
    float res = 1.0;
    float t = mint;

    for (int i = 0; i < 64 && t < maxt; i++) {
        float h = DE_simple(ro + rd * t);
        if (h < 0.0001) return 0.0;
        res = min(res, k * h / t);
        t += clamp(h, 0.01, 0.5);
    }

    return clamp(res, 0.0, 1.0);
}

// ============================================================================
// Ambient occlusion
// ============================================================================

float calcAO(vec3 pos, vec3 normal) {
    float ao = 0.0;
    float scale = 1.0;

    for (int i = 0; i < 5; i++) {
        float hr = 0.01 + 0.12 * float(i) / 4.0;
        float dd = DE_simple(pos + normal * hr);
        ao += (hr - dd) * scale;
        scale *= 0.7;
    }

    return clamp(1.0 - 4.0 * ao, 0.0, 1.0);
}

// ============================================================================
// Ray marching
// ============================================================================

struct RayHit {
    bool hit;
    vec3 pos;
    float dist;
    float minDist;  // For glow effect
    OrbitTrap trap;
};

RayHit rayMarch(vec3 ro, vec3 rd) {
    RayHit result;
    result.hit = false;
    result.dist = 0.0;
    result.minDist = 1e10;

    for (int i = 0; i < MAX_STEPS; i++) {
        result.pos = ro + rd * result.dist;

        float d = DE(result.pos, result.trap);
        result.minDist = min(result.minDist, d);

        float epsilon = computeAdaptiveEpsilon(result.dist, BASE_EPSILON);

        if (d < epsilon) {
            result.hit = true;
            break;
        }

        result.dist += computeStep(d, STEP_FACTOR);

        if (result.dist > MAX_DISTANCE) break;
    }

    return result;
}

// ============================================================================
// Shading
// ============================================================================

vec3 shade(RayHit hit, vec3 rd) {
    vec3 normal = calcNormal(hit.pos);
    vec3 viewDir = -rd;
    vec3 light = normalize(lightDir);

    // Base color from orbit traps
    vec3 baseColor = getColor(hit.trap);

    // Diffuse
    float NdotL = max(dot(normal, light), 0.0);

    // Specular (Blinn-Phong)
    vec3 halfDir = normalize(light + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), 32.0);

    // Shadow
    float shadow = calcShadow(hit.pos + normal * 0.01, light, 0.01, 10.0, 16.0);

    // Ambient occlusion
    float ao = calcAO(hit.pos, normal);

    // Combine lighting
    vec3 ambient = ambientColor * baseColor * ao;
    vec3 diffuse = lightColor * baseColor * NdotL * shadow;
    vec3 specular = lightColor * spec * shadow * 0.5;

    // Fresnel rim lighting
    float fresnel = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.0);
    vec3 rim = lightColor * fresnel * 0.2;

    return ambient + diffuse + specular + rim;
}

// ============================================================================
// Background
// ============================================================================

vec3 background(vec3 rd, float minDist) {
    // Gradient background
    float t = 0.5 + 0.5 * rd.y;
    vec3 bg = mix(vec3(0.1, 0.1, 0.15), vec3(0.02, 0.02, 0.05), t);

    // Glow effect based on how close we got
    float glow = exp(-minDist * 10.0) * 0.5;
    bg += fractalPalette(minDist * 2.0) * glow;

    return bg;
}

// ============================================================================
// Main render function
// ============================================================================

void main() {
    // Initialize random for this pixel/sample
    uint seed = initRandom(gl_FragCoord.xy, sampleIndex);

    // Anti-aliasing jitter
    vec2 jitter = random2(seed) - 0.5;

    // Get camera ray
    vec3 ro, rd;
    getCameraRayJittered(fragCoord, jitter, ro, rd);

    // Ray march
    RayHit hit = rayMarch(ro, rd);

    // Shade
    vec3 color;
    if (hit.hit) {
        color = shade(hit, rd);
    } else {
        color = background(rd, hit.minDist);
    }

    // Output (will be accumulated, tone mapping done in display shader)
    FragColor = vec4(color, 1.0);
}
