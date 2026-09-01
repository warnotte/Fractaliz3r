/**
 * Mandelorus Distance Estimator
 *
 * Mandelbrot on a torus topology. The iteration decomposes the position into
 * toroidal coordinates (ring angle phi + cross-section q), applies complex power
 * to the cross-section, then winds around the major ring.
 * Creates solenoid structures with configurable twist.
 */

uniform int maxIterations;
uniform float bailout;
uniform float ringRadius;
uniform float torusTwist;
uniform float power;
uniform float ringPhase;
uniform float crossPhase;
uniform float vertScale;

struct OrbitTrap {
    float minDist;
    float planeX;
    float planeY;
    float planeZ;
    int iterations;
};

float DE(vec3 pos, out OrbitTrap trap) {
    vec3 z = pos;
    vec3 c = pos;
    float dr = 1.0;

    trap.minDist = 1e10;
    trap.planeX = 1e10;
    trap.planeY = 1e10;
    trap.planeZ = 1e10;
    trap.iterations = 0;

    float zScale = 1.0 + vertScale;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        // Toroidal decomposition
        float lenXY = length(z.xy);
        float phi = atan(z.y, z.x);
        vec2 q = vec2(lenXY - ringRadius, z.z * zScale);

        // Twist: rotate cross-section by ring angle
        if (abs(torusTwist) > 0.001) {
            float rotAng = phi * torusTwist / max(1.0, power);
            float s = sin(rotAng), co = cos(rotAng);
            q = mat2(co, -s, s, co) * q;
        }

        // Cross-section complex power
        float r2 = dot(q, q);
        float r = sqrt(r2);

        if (r > bailout) break;

        float angleQ = atan(q.y, q.x) + crossPhase;

        // Derivative
        float dr_cross = power * pow(r, power - 1.0);
        float expansion = max(power, dr_cross);
        expansion = expansion * max(1.0, zScale);
        expansion = expansion * (1.0 + abs(torusTwist) * 0.3);
        dr = dr * expansion + 1.0;

        // Apply power to cross-section
        float newR = pow(r, power);
        float newAngleQ = angleQ * power;
        q = newR * vec2(cos(newAngleQ), sin(newAngleQ));

        // Solenoidal winding
        phi = phi * power + ringPhase;

        // Reconstruct 3D
        vec2 ringPos = vec2(cos(phi), sin(phi));
        z.xy = ringPos * (ringRadius + q.x);
        z.z = q.y;

        z = z + c;

        // Orbit traps
        trap.minDist = min(trap.minDist, r2);
        trap.planeX = min(trap.planeX, abs(z.x));
        trap.planeY = min(trap.planeY, abs(z.y));
        trap.planeZ = min(trap.planeZ, abs(z.z));
        trap.iterations = i + 1;
    }

    float r = length(z);
    float de = 0.5 * log(r) * r / max(abs(dr), 1e-6);
    float rPos = length(pos);
    if (rPos > 2.0 * (bailout + ringRadius)) de = min(de, rPos - bailout - ringRadius);
    return de;
}

float DE_simple(vec3 pos) {
    vec3 z = pos;
    vec3 c = pos;
    float dr = 1.0;
    float zScale = 1.0 + vertScale;

    for (int i = 0; i < maxIterations + gExtraIterations; i++) {
        float lenXY = length(z.xy);
        float phi = atan(z.y, z.x);
        vec2 q = vec2(lenXY - ringRadius, z.z * zScale);

        if (abs(torusTwist) > 0.001) {
            float rotAng = phi * torusTwist / max(1.0, power);
            float s = sin(rotAng), co = cos(rotAng);
            q = mat2(co, -s, s, co) * q;
        }

        float r2 = dot(q, q);
        float r = sqrt(r2);
        if (r > bailout) break;

        float angleQ = atan(q.y, q.x) + crossPhase;

        float dr_cross = power * pow(r, power - 1.0);
        float expansion = max(power, dr_cross) * max(1.0, zScale) * (1.0 + abs(torusTwist) * 0.3);
        dr = dr * expansion + 1.0;

        float newR = pow(r, power);
        q = newR * vec2(cos(angleQ * power), sin(angleQ * power));

        phi = phi * power + ringPhase;
        vec2 ringPos = vec2(cos(phi), sin(phi));
        z.xy = ringPos * (ringRadius + q.x);
        z.z = q.y;
        z = z + c;
    }

    float r = length(z);
    float de = 0.5 * log(r) * r / max(abs(dr), 1e-6);
    float rPos = length(pos);
    if (rPos > 2.0 * (bailout + ringRadius)) de = min(de, rPos - bailout - ringRadius);
    return de;
}

vec3 getFactors(OrbitTrap trap) {
    float structural = 1.0 - exp(-trap.minDist * 0.8);
    float trapX = exp(-trap.planeX * 3.0);
    float trapY = exp(-trap.planeY * 3.0);
    float trapZ = exp(-trap.planeZ * 3.0);
    float flow = (trapX * 0.5 + trapY * 1.0 + trapZ * 1.5) / 3.0;
    float iterNorm = float(trap.iterations) / float(max(maxIterations + gExtraIterations, 1));
    return vec3(structural, flow, iterNorm);
}
