/**
 * Photon pass: light tracing from every light of the list (lights.glsl).
 *
 * Appended to the scene program (common + lights + scene + raytracer) under PHOTON_PASS
 * and BIDIR, in place of the camera main. One fragment is one photon: a light is drawn by
 * power, a point and a direction on it, then the photon is marched like a camera ray,
 * refracted by glass (dispersed when dispersion is on), reflected by metal, scattered by
 * matte surfaces. At each matte or metal vertex a coin decides whether the photon lands
 * there, connected to the camera (the pixel that sees the point, a march back for
 * visibility, the radiance it adds), or goes on. What it adds is weighted by multiple
 * importance sampling against the path tracer's own ways of making the same path (a
 * direct draw of the light from the last matte vertex, a BSDF ray hitting the emitter),
 * with the same conventions the path tracer applies on its side under BIDIR, so every
 * path is counted once whatever found it. See docs/RENDERING.md, "One light transport".
 */

layout(location = 0) out vec4 photonPixel;   // xy: where on the tile, in [0,1]; zw: where on the sun's square
layout(location = 1) out vec4 photonColor;   // rgb: the radiance it adds to that pixel; a: flags, 1 landed, 2 met glass or metal

uniform int causticDebug;        // CausticProbe: 1 = every landing weighted 1, whatever the path tracer could do

bool gLanded = false;
bool gSpecular = false;
bool gStored = false;
int gSlot = 0;                   // this photon's slot in the vertex buffer

// A landing may not add more than this many times what a photon of average flux adds to a
// pixel at the distance of the caustic centre: the fireflies of a landing right under the
// camera, and nothing else.
const float CAUSTIC_CLAMP = 64.0;

// Connect x to the camera: which pixel sees it, is it visible from there, and the camera
// factor cosX / (d^2 * A_pixel * cosCam^3): the radiance a photon of flux phi adds to the
// pixel is phi * f * that factor (the flux per pixel footprint times the BRDF), and the
// factor is also the path tracer's density of x from the eye, in area measure. With depth
// of field the eye is a point of the lens and the pixel the one whose centre ray meets the
// lens ray on the focal sphere, as getCameraRayDOF builds its rays.
// For a surface point (surface true: the cosine at x and the bias above it), or a point of
// the medium (no cosine, no bias). The factor carries the fog's transmittance to the eye.
bool connectToCamera(vec3 x, vec3 n, bool surface, inout uint seed, out vec3 toCam, out float camFactor, out vec2 tileUV) {
    if (projectionMode != 0) return false;                 // no caustics in the 360 projection
    vec3 forward = rotateByQuaternion(vec3(0, 0, 1), camQuat);
    vec3 right   = rotateByQuaternion(vec3(1, 0, 0), camQuat);
    vec3 up      = rotateByQuaternion(vec3(0, 1, 0), camQuat);
    vec3 eye = camPos;
    vec3 imagePoint = x;
    if (dofEnabled != 0 && aperture >= 0.0001) {
        float r = sqrt(random(seed));
        float theta = TAU * random(seed);
        vec2 disk = r * vec2(cos(theta), sin(theta));
        if (bokehBlades >= 3) {
            float len = length(disk);
            if (len > 0.0001) {
                float t = atan(disk.y, disk.x) + bokehRotation;
                float nb = float(bokehBlades);
                float halfSector = PI / nb;
                float sectorT = mod(t + halfSector, TAU / nb) - halfSector;
                len = len * cos(PI / nb) / cos(sectorT);
                disk = len * vec2(cos(t), sin(t));
            }
        }
        disk *= aperture;
        disk.y *= anamorphicRatio;
        eye = camPos + right * disk.x + up * disk.y;
        vec3 d = normalize(x - eye);
        vec3 oc = eye - camPos;
        float b = dot(oc, d);
        float c = dot(oc, oc) - focalDistance * focalDistance;
        float disc = b * b - c;
        if (disc < 0.0) return false;
        float s = -b + sqrt(disc);
        if (s <= 0.0) return false;
        imagePoint = eye + d * s;
    }

    vec3 toX = x - eye;
    float d2 = dot(toX, toX);
    float d = sqrt(d2);
    vec3 dir = toX / d;
    float cosCam = dot(dir, forward);
    if (cosCam <= 0.001) return false;
    float cosX = surface ? dot(n, -dir) : 1.0;
    if (cosX <= 0.0) return false;

    vec3 ip = imagePoint - camPos;
    float z = dot(ip, forward);
    if (z <= 0.0) return false;
    float halfH = tan(radians(fov) * 0.5);
    float halfW = halfH * fullResolution.x / fullResolution.y;
    vec2 ndc = vec2(dot(ip, right) / (z * halfW), dot(ip, up) / (z * halfH));
    tileUV = (ndc * 0.5 + 0.5 - tileOffset) / tileScale;
    if (any(lessThan(tileUV, vec2(0.0))) || any(greaterThanEqual(tileUV, vec2(1.0)))) return false;

    Ray toEye;
    toEye.origin = surface ? x + n * surfaceBias(d) : x;
    toEye.direction = -dir;
    vec3 vp; float vd; int vm;
    if (rayMarchSimple(toEye, vp, vd, vm) && vd < d - 0.01) return false;

    float pixelArea = (2.0 * halfW / fullResolution.x) * (2.0 * halfH / fullResolution.y);
    camFactor = cosX / (d2 * pixelArea * cosCam * cosCam * cosCam);
    if (volumetricFogEnabled != 0 && fogDensity > 0.0) camFactor *= exp(-d * fogDensity);   // as the camera rays are attenuated
    toCam = -dir;
    return true;
}

// The landing's clamp: this many times what a photon of average flux adds to a pixel at
// the distance of the caustic centre.
float landingBound() {
    float sceneD = max(length(causticCenter - camPos), 0.1);
    float halfH = tan(radians(fov) * 0.5);
    float halfW = halfH * fullResolution.x / fullResolution.y;
    float pixelArea = (2.0 * halfW / fullResolution.x) * (2.0 * halfH / fullResolution.y);
    return CAUSTIC_CLAMP * lightPowerTotal / ltCount() / (PI * pixelArea * sceneD * sceneD);
}

// The photon's flight from light li to where it lands or dies.
void trace(int li, inout uint seed) {
    LightData L = lights[li];
    int ltype = int(L.type);
    float pick = lightPickPdf(li);
    bool emitter = isEmitter(li);

    // ---- emission: a point and a direction on the light, the flux the draw stands for ----
    Ray ray;
    vec3 flux;
    vec3 emitPos = vec3(0.0), emitN = vec3(0.0, 1.0, 0.0);
    float rho = 0.0;               // sun and beam: the draw's density over the square or the disk
    float dirPdf = 0.0;            // point and spot: the direction's density; their range too
    float range = 0.0;
    bool pointSource = false;
    if (ltype == LIGHT_SUN) {
        vec3 Ldir, u, v;
        sunFrame(Ldir, u, v);
        vec2 sq;
        rho = sunSquareDraw(seed, sq);
        photonPixel.zw = sq;
        ray.origin = sunSquarePoint(sq, Ldir, u, v);
        ray.direction = -jitterLightDir(Ldir, seed, shadowSoftness);
        flux = vec3(L.r, L.g, L.b) * L.intensity / (rho * pick);
    } else if (ltype == LIGHT_BEAM) {
        vec3 axis = vec3(L.dx, L.dy, L.dz);
        vec3 u = normalize(cross(abs(axis.y) < 0.999 ? vec3(0, 1, 0) : vec3(1, 0, 0), axis));
        vec3 v = cross(axis, u);
        float radius = max(L.sx, 1e-4);
        float r = radius * sqrt(random(seed));
        float phi = TAU * random(seed);
        ray.origin = vec3(L.px, L.py, L.pz) + u * (r * cos(phi)) + v * (r * sin(phi));
        ray.direction = axis;
        float edge = clamp(L.sz, 0.0, 1.0);
        float att = edge < 0.001 ? 1.0 : 1.0 - smoothstep(radius * (1.0 - edge), radius, r);
        rho = 1.0 / (PI * radius * radius);
        flux = vec3(L.r, L.g, L.b) * L.intensity * att / (rho * pick);
    } else if (ltype == LIGHT_POINT || ltype == LIGHT_SPOT) {
        // From the point (jittered over its area radius as the draw jitters it), a direction
        // over the sphere, or over the spot's cone with its soft edge as a weight.
        pointSource = true;
        vec3 pos = vec3(L.px, L.py, L.pz);
        if (L.sx > 0.0) {
            float z = 1.0 - 2.0 * random(seed), phi = TAU * random(seed), rxy = sqrt(max(0.0, 1.0 - z * z));
            pos += vec3(rxy * cos(phi), rxy * sin(phi), z) * L.sx;
        }
        vec3 dir;
        float att = 1.0;
        if (ltype == LIGHT_POINT) {
            float z = 1.0 - 2.0 * random(seed), phi = TAU * random(seed), rxy = sqrt(max(0.0, 1.0 - z * z));
            dir = vec3(rxy * cos(phi), rxy * sin(phi), z);
            dirPdf = 1.0 / (4.0 * PI);
        } else {
            vec3 axis = vec3(L.dx, L.dy, L.dz);
            float outer = clamp(L.sz, 1.0, 89.0);
            float cosOuter = cos(radians(outer));
            float cosT = mix(cosOuter, 1.0, random(seed));
            float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
            float phi = TAU * random(seed);
            vec3 u = normalize(cross(abs(axis.y) < 0.999 ? vec3(0, 1, 0) : vec3(1, 0, 0), axis));
            vec3 v = cross(axis, u);
            dir = normalize(u * (sinT * cos(phi)) + v * (sinT * sin(phi)) + axis * cosT);
            dirPdf = 1.0 / (TAU * (1.0 - cosOuter));
            float softness = clamp(L.pad0, 0.0, 1.0);
            float cosInner = cos(radians(mix(outer, 0.0, softness)));
            att = softness < 0.001 ? 1.0 : smoothstep(cosOuter, cosInner, cosT);
        }
        ray.origin = pos;
        ray.direction = dir;
        range = L.sy;
        flux = vec3(L.r, L.g, L.b) * L.intensity * att / (dirPdf * pick);
    } else if (emitter) {
        sampleEmitterPoint(li, seed, emitPos, emitN);
        ray.origin = emitPos + emitN * 0.005;
        ray.direction = randomCosineHemisphere(seed, emitN);
        flux = emitterRadiance(li) * (PI * L.area) / pick;   // L_e, the point at 1/area, the direction at cos/pi
    } else {
        return;                    // point and spot lights send no photons: their draw stands alone
    }
    flux /= float(photonSide * photonSide);
    vec3 weight = vec3(1.0);

    float lambda = 0.0;
    bool dispersed = false;
    if (dispersionEnabled != 0) lambda = 400.0 + 300.0 * random(seed);

    // ---- the bookkeeping: the path tracer's density of this path over ours --------------
    // rA with the path tracer drawing the light from its last matte vertex, rB with its
    // BSDF ray hitting the emitter: products over the segments of (path tracer density /
    // photon density) in area measure, the coins included, a delta on either side as 1.
    float rA = 0.0, rB = 0.0;
    bool first = true;
    float pendingLtPdf = 0.0;      // our bounce pdf at the vertex just left (solid angle)
    float prevCosOut = 1.0;        // |n . dir| at that vertex along the segment, for the area density of it from here
    bool prevDelta = false;

    for (int bounce = 0; bounce <= maxBounces; bounce++) {
        vec3 hitPos; float hitDist; int hitMat;
        bool hit = rayMarchSimple(ray, hitPos, hitDist, hitMat);
        if (bounce == 0 && hit && hitDist < 1e-3) return;     // the draw started inside something

        // In the fog, past a glass, mirror or metal, the photon may scatter before it gets
        // there: the light through the prism seen in the air. Its direct light in the fog
        // is the camera rays' march (computeVolumetricFog), so before such a vertex it flies
        // straight; its flight is not attenuated, as the path tracer's bounces are not.
        if (volumetricFogEnabled != 0 && fogDensity > 0.0 && gSpecular) {
            float t = -log(max(1.0 - random(seed), 1e-6)) / fogDensity;
            if (t < (hit ? hitDist : MAX_DISTANCE)) {
                vec3 p = ray.origin + ray.direction * t;
                vec3 toCam; float camFactor; vec2 tileUV;
                if (!connectToCamera(p, vec3(0.0), false, seed, toCam, camFactor, tileUV)) return;
                float phase = phaseHG(dot(ray.direction, toCam), fogScattering);
                vec3 c = flux * weight * fogColor * phase * camFactor;   // a real scatter event: sigma_s / sigma_t is the fog's albedo
                photonPixel.xy = tileUV;
                photonColor.rgb = min(c, vec3(landingBound()));
                gLanded = true;
                return;
            }
        }
        if (!hit) return;
        // A beam photon's first flight through the fog: attenuated as the path tracer's
        // draw of the beam is (sampleExtraLightRadiance), so both tell the same beam.
        if (bounce == 0 && ltype == LIGHT_BEAM && volumetricFogEnabled != 0 && fogDensity > 0.0) weight *= exp(-hitDist * fogDensity);

        vec3 normal = calcNormal(hitPos);
        vec3 faceN = dot(ray.direction, normal) > 0.0 ? -normal : normal;
        vec3 wIn = -ray.direction;                            // towards where the photon came from
        float cosIn = max(dot(faceN, wIn), 0.001);
        float d2 = max(hitDist * hitDist, 1e-8);

        // our density of this vertex, area measure
        float ltHere;
        if (first) {
            if (emitter) {
                float cosL = max(dot(emitN, ray.direction), 0.0);
                ltHere = pick * emitterPdfArea(li) * (cosL / PI) * cosIn / d2;
            } else if (pointSource) {
                ltHere = pick * dirPdf * cosIn / d2;
                // the draw's light falls with a range, not with the inverse square this flux
                // carries: the same falloff here, so both tell the same light
                float nd = hitDist / range;
                if (nd >= 1.0) return;
                weight *= (1.0 / (1.0 + nd * nd * 8.0)) * pow(max(1.0 - nd, 0.0), 3.0) * d2;
            } else {
                ltHere = pick * rho / cosIn;                   // a parallel draw, projected
            }
        } else {
            ltHere = prevDelta ? 1.0 : pendingLtPdf * cosIn / d2;
        }
        // the path tracer's densities of the light's point from here (the first segment)
        float ptA = emitter ? emitterPickPdf(li) * emitterPdfArea(li) : 1.0;
        float cosL = emitter ? max(dot(emitN, ray.direction), 0.0) : 0.0;

        // ---- the material ------------------------------------------------------------
        vec3 albedo; int localMatType; float localIor, localMetalness, safeRoughness, localEmissive;
        bool ocean = hitMat == MAT_OCEAN;
        if (ocean) {
            albedo = oceanColor; localMatType = 0; localIor = 1.33; localMetalness = 0.0; safeRoughness = 0.02; localEmissive = 0.0;
        } else {
            OrbitTrap trap;
            DE(hitPos, trap);
            vec3 mf = getFactors(trap);
#ifdef BOOLEAN_OPS
            mf = morphFactors(hitPos, mf);
#endif
            albedo = applyMaterial(remapTrapFactors(mf, hitPos), hitPos, normal, ray.direction);
            localMatType = materialType; localIor = ior; localMetalness = metalness;
            safeRoughness = max(roughness, 0.02); localEmissive = emissiveIntensity;
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
                if (localEmissive > 0.0) return;                  // a pure emitter, as the path tracer treats it
            }
#endif
        }
        float a = safeRoughness * safeRoughness;
        float a2 = a * a;

        // ---- specular: glass, and the ocean's reflection -------------------------------
        bool delta = false;
        vec3 outN = normal;                                       // the normal where the photon leaves a glass
        if (ocean) {
            if (random(seed) < fresnelDielectric(cosIn, 1.33)) {
                ray.direction = reflect(ray.direction, normal);
                ray.origin = hitPos + normal * 0.005;
                weight *= 0.95;
                delta = true;
            }
        } else if (localMatType == MATERIAL_GLASS) {
            delta = true;
            bool entering = dot(ray.direction, normal) < 0.0;
            float fr = fresnelDielectric(cosIn, localIor);
            if (safeRoughness > 0.01) fr = mix(fr, 0.5, safeRoughness * 0.5);
            if (random(seed) < fr) {
                vec3 reflectDir = reflect(ray.direction, faceN);
                if (safeRoughness > 0.001) reflectDir = reflect(ray.direction, randomGGX(seed, faceN, safeRoughness));
                ray.origin = hitPos + faceN * 0.005;
                ray.direction = normalize(reflectDir);
                weight *= mix(vec3(1.0), albedo, 0.05);
            } else {
                if (dispersionEnabled != 0) {
                    localIor = dispersedIor(localIor, lambda);
                    if (!dispersed) { weight *= spectralTint(lambda); dispersed = true; }
                }
                float entryEta = entering ? (1.0 / localIor) : localIor;
                vec3 refractedDir;
                if (!refractRay(ray.direction, faceN, entryEta, refractedDir)) {
                    ray.origin = hitPos + faceN * 0.005;
                    ray.direction = reflect(ray.direction, faceN);
                    weight *= mix(vec3(1.0), albedo, 0.05);
                } else if (entering) {
                    vec3 interiorDir = normalize(refractedDir);
                    float t = 0.02;
                    vec3 exitPos = hitPos;
                    for (int gs = 0; gs < 128; gs++) {
                        exitPos = hitPos + interiorDir * t;
                        float dd = sceneDE_simple(exitPos);
                        if (dd > 0.0) { exitPos -= interiorDir * dd; break; }
                        t += max(abs(dd), 0.002);
                        if (t > 10.0) break;
                    }
                    vec3 exitNormal = calcNormal(exitPos);
                    vec3 exitFaceN = dot(interiorDir, exitNormal) > 0.0 ? -exitNormal : exitNormal;
                    vec3 exitRefracted;
                    ray.direction = refractRay(interiorDir, exitFaceN, localIor, exitRefracted) ? normalize(exitRefracted) : interiorDir;
                    ray.origin = exitPos + exitNormal * 0.005;
                    weight *= vec3(0.98, 1.0, 1.02) * albedo;
                    outN = exitNormal;
                } else {
                    ray.origin = hitPos - faceN * 0.005;
                    ray.direction = normalize(refractedDir);
                    weight *= vec3(0.98, 1.0, 1.02) * albedo;
                }
            }
        }

        if (delta) {
            gSpecular = true;
            // the path tracer's density of the previous vertex from here is a delta (1); of
            // the light's point from here, none by a draw, a delta by a BSDF ray
            if (first) { rA = 0.0; rB = emitter ? 1.0 / ltHere : 0.0; first = false; }
            else { rA *= 1.0 / ltHere; rB *= 1.0 / ltHere; }
            prevDelta = true; pendingLtPdf = 0.0;
            prevCosOut = max(abs(dot(outN, ray.direction)), 0.001);   // the segment's cosine where we leave
            if (bounce >= 3) {
                float p = max(weight.x, max(weight.y, weight.z));
                if (random(seed) > p) return;
                weight /= p;
            }
            continue;
        }

        // ---- matte or metal: land here, or go on ------------------------------------------
        // The photon pass makes the paths with a glass, mirror or metal vertex between the
        // light and the landing; direct and diffuse light are the path tracer's, whose draw
        // has far less variance than a scatter of landings. So the coin is flipped only past
        // such a vertex, and the path tracer counts the coins the same way.
        bool metal = localMatType == MATERIAL_METALLIC;
        vec3 F0 = mix(vec3(0.04), albedo, localMetalness);
        bool eligible = gSpecular || causticDebug != 0;             // the calibration check lands anywhere, weight one

        // the first eligible vertex is stored for the gather, whatever the coin says
        if (gSpecular && !gStored) {
            photonData[4 * gSlot] = vec4(hitPos, 1.0);
            photonData[4 * gSlot + 1] = vec4(flux * weight, 0.0);
            photonData[4 * gSlot + 2] = vec4(faceN, 0.0);
            photonData[4 * gSlot + 3] = vec4(wIn, 0.0);
            gStored = true;
        }

        if (eligible && random(seed) < LT_LAND) {
            vec3 toCam; float camFactor; vec2 tileUV;
            if (!connectToCamera(hitPos, faceN, true, seed, toCam, camFactor, tileUV)) return;
            float NdotV = max(dot(faceN, toCam), 0.001);
            vec3 f;
            if (metal) {
                vec3 H = normalize(toCam + wIn);
                float NdotH = max(dot(faceN, H), 0.0);
                float VdotH = max(dot(toCam, H), 0.001);
                float D = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
                float G = smithG2GGX(cosIn, NdotV, a2);
                f = fresnelSchlickVec(VdotH, F0) * D * G / (4.0 * NdotV * cosIn);
            } else {
                f = albedo / PI;
            }
            // the path tracer at this vertex comes from the eye and would sample towards
            // where we came from: the density of the previous vertex (or the light) from here
            float ptBack = (metal ? ggxPdf(faceN, toCam, wIn, a2) : cosIn / PI);
            if (first) {
                rA = ptA / ltHere;
                rB = emitter ? (ptBack * cosL / d2) / ltHere : 0.0;
            } else {
                float pt = ptBack * prevCosOut / d2;
                rA *= pt / ltHere;
                rB *= pt / ltHere;
            }
            // the camera segment (the eye's density of this vertex, ours a delta) and the coin;
            // the density is the geometric factor, without the fog's transmittance
            float pCam = camFactor;
            if (volumetricFogEnabled != 0 && fogDensity > 0.0) pCam /= exp(-length(hitPos - camPos) * fogDensity);
            rA *= pCam / LT_LAND;
            rB *= pCam / LT_LAND;
            float nA = rA / ltCount(), nB = rB / ltCount();       // one camera path against all our photons
            float w = causticDebug != 0 ? 1.0 : 1.0 / (1.0 + nA * nA + nB * nB);
            vec3 c = flux * weight * f * camFactor * w / LT_LAND;
            photonPixel.xy = tileUV;
            photonColor.rgb = min(c, vec3(landingBound()));
            gLanded = true;
            return;
        }

        // go on: a bounce, its pdf for the next segment, the coin in the densities
        vec3 wOut;
        float pdfOmega;
        if (metal) {
            vec3 H = randomGGX(seed, faceN, safeRoughness);
            wOut = reflect(-wIn, H);
            float NdotH = max(dot(faceN, H), 0.001);
            float VdotH = max(dot(wIn, H), 0.001);
            float NdotL = dot(faceN, wOut);
            if (NdotL <= 0.0) return;
            float G = smithG2GGX(max(NdotL, 0.001), cosIn, a2);
            weight *= fresnelSchlickVec(VdotH, F0) * G * VdotH / (cosIn * NdotH);
            float D = a2 / (PI * pow(NdotH * NdotH * (a2 - 1.0) + 1.0, 2.0));
            pdfOmega = D * NdotH / (4.0 * VdotH);
        } else {
            wOut = randomCosineHemisphere(seed, faceN);
            weight *= albedo;
            pdfOmega = max(dot(faceN, wOut), 0.001) / PI;
        }
        // the path tracer at this vertex comes from where we go and samples where we came from
        float ptBack = metal ? ggxPdf(faceN, wOut, wIn, a2) : cosIn / PI;
        if (first) {
            rA = ptA / ltHere;
            rB = emitter ? (ptBack * cosL / d2) / ltHere : 0.0;
            first = false;
        } else {
            float pt = ptBack * prevCosOut / d2;
            rA *= pt / ltHere;
            rB *= pt / ltHere;
        }
        if (eligible) {
            rA /= (1.0 - LT_LAND);
            rB /= (1.0 - LT_LAND);
            weight /= (1.0 - LT_LAND);
        }
        if (metal) gSpecular = true;                             // past it, the next landing counts
        pendingLtPdf = pdfOmega;
        prevCosOut = max(dot(faceN, wOut), 0.001);
        prevDelta = false;
        ray.origin = hitPos + faceN * 0.005;
        ray.direction = normalize(wOut);

        if (bounce >= 3) {
            float p = max(weight.x, max(weight.y, weight.z));
            if (random(seed) > p) return;
            weight /= p;
        }
    }
}

void main() {
    photonPixel = vec4(0.0);
    photonColor = vec4(0.0);
    gSlot = int(gl_FragCoord.y) * photonSide + int(gl_FragCoord.x);
    photonData[4 * gSlot] = vec4(0.0);                        // no vertex until one is stored
    uint seed = initRandom(gl_FragCoord.xy + vec2(0.5, 7919.0), sampleIndex);
    float pick;
    int li = pickLight(seed, pick);
    if (li >= 0) trace(li, seed);
    photonColor.a = (gLanded ? 1.0 : 0.0) + (gSpecular ? 2.0 : 0.0);
}
