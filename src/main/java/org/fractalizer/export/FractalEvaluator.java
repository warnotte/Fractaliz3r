package org.fractalizer.export;

import javafx.scene.paint.Color;
import org.fractalizer.fractals.*;

/**
 * CPU-side Distance Estimator and coloring for all fractal types.
 * Direct port of GLSL math for use in Marching Cubes isosurface extraction.
 */
public class FractalEvaluator {

    /** Orbit trap data collected during DE evaluation. */
    public static class OrbitTrap {
        public float minDist;
        public float planeX, planeY, planeZ;
        public float avgFold, sphereHits, sumDist, avgDist, lastDist, trap;
        public int iterations;
    }

    // GLSL mod: x - y * floor(x/y)
    private static float mod(float x, float y) {
        return x - y * (float) Math.floor(x / y);
    }

    private static float clamp(float x, float a, float b) {
        return Math.max(a, Math.min(b, x));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float dot3(float ax, float ay, float az, float bx, float by, float bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static float length3(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /** Sanitize DE result: replace NaN/Infinity with a large positive value. */
    private static float sanitizeDE(float de) {
        return Float.isFinite(de) ? de : 1e10f;
    }

    /** Evaluate the simple DE (no orbit traps) for scalar field and normals. */
    public static float evaluateSimple(float x, float y, float z, AbstractFractalParams params) {
        return sanitizeDE(switch (params.getType()) {
            case MANDELBULB -> mandelbulbDE(x, y, z, (MandelbulbParams) params);
            case MANDELBOX -> mandelboxDE(x, y, z, (MandelboxParams) params);
            case MENGER_SPONGE -> mengerDE(x, y, z, (MengerSpongeParams) params);
            case KALEIDOSCOPIC_IFS -> kaleidoscopicDE(x, y, z, (KaleidoscopicIFSParams) params);
            case POLYHEDRAL_IFS -> polyhedralDE(x, y, z, (PolyhedralIFSParams) params);
            case SIERPINSKI -> sierpinskiDE(x, y, z, (SierpinskiParams) params);
            case PSEUDO_KLEINIAN -> pseudoKleinianDE(x, y, z, (PseudoKleinianParams) params);
            case APOLLONIAN -> apollonianDE(x, y, z, (ApollonianParams) params);
            case BRISTORBROT -> bristorbrotDE(x, y, z, (BristorbrotParams) params);
            case QUATERNION_JULIA_4D -> quaternionJulia4dDE(x, y, z, (QuaternionJulia4DParams) params);
            case FRACTAL_TERRAIN -> fractalTerrainDE(x, y, z, (FractalTerrainParams) params);
            default -> 1e10f;
        });
    }

    /** Evaluate DE with orbit traps for coloring. */
    public static float evaluate(float x, float y, float z, AbstractFractalParams params, OrbitTrap trap) {
        return sanitizeDE(switch (params.getType()) {
            case MANDELBULB -> mandelbulbDEFull(x, y, z, (MandelbulbParams) params, trap);
            case MANDELBOX -> mandelboxDEFull(x, y, z, (MandelboxParams) params, trap);
            case MENGER_SPONGE -> mengerDEFull(x, y, z, (MengerSpongeParams) params, trap);
            case KALEIDOSCOPIC_IFS -> kaleidoscopicDEFull(x, y, z, (KaleidoscopicIFSParams) params, trap);
            case POLYHEDRAL_IFS -> polyhedralDEFull(x, y, z, (PolyhedralIFSParams) params, trap);
            case SIERPINSKI -> sierpinskiDEFull(x, y, z, (SierpinskiParams) params, trap);
            case PSEUDO_KLEINIAN -> pseudoKleinianDEFull(x, y, z, (PseudoKleinianParams) params, trap);
            case APOLLONIAN -> apollonianDEFull(x, y, z, (ApollonianParams) params, trap);
            case BRISTORBROT -> bristorbrotDEFull(x, y, z, (BristorbrotParams) params, trap);
            case QUATERNION_JULIA_4D -> quaternionJulia4dDEFull(x, y, z, (QuaternionJulia4DParams) params, trap);
            case FRACTAL_TERRAIN -> fractalTerrainDEFull(x, y, z, (FractalTerrainParams) params, trap);
            default -> 1e10f;
        });
    }

    /** Compute surface normal via central differences (6 DE calls). */
    public static float[] computeNormal(float x, float y, float z, AbstractFractalParams params, float eps) {
        float dx = evaluateSimple(x + eps, y, z, params) - evaluateSimple(x - eps, y, z, params);
        float dy = evaluateSimple(x, y + eps, z, params) - evaluateSimple(x, y - eps, z, params);
        float dz = evaluateSimple(x, y, z + eps, params) - evaluateSimple(x, y, z - eps, params);
        float len = length3(dx, dy, dz);
        if (len < 1e-12f) return new float[]{0, 1, 0};
        return new float[]{dx / len, dy / len, dz / len};
    }

    /** Compute vertex color from factors or orbit traps. */
    public static Color computeColor(OrbitTrap trap, AbstractFractalParams params, float[] factors) {
        float structural = factors[0];
        float flow = factors[1];
        float depth = factors[2];

        int coloringMode = params.getColoringMode();
        float colorStrength = params.getColorStrength();
        float paletteOffset = params.getPaletteOffset();
        GradientPalette gradient = params.getCustomGradient();

        float r, g, b;

        if (coloringMode == 6) {
            // HSV Direct
            float h = fract(flow * colorStrength + paletteOffset);
            float s = mix(0.4f, 1f, structural);
            float v = mix(0.3f, 1f, 1f - depth * 0.7f);
            float[] rgb = hsv2rgb(h, s, v);
            return Color.color(clamp(rgb[0], 0, 1), clamp(rgb[1], 0, 1), clamp(rgb[2], 0, 1));
        }
        if (coloringMode == 7) {
            // Dual Palette
            float t1 = structural * colorStrength + paletteOffset;
            float t2 = flow * colorStrength * 1.5f + paletteOffset + 0.5f;
            Color c1 = gradient.sampleAt(fract(t1));
            Color c2 = gradient.sampleAt(fract(t2));
            r = mix((float) c1.getRed(), (float) c2.getRed(), depth);
            g = mix((float) c1.getGreen(), (float) c2.getGreen(), depth);
            b = mix((float) c1.getBlue(), (float) c2.getBlue(), depth);
            float dim = 1f - depth * 0.3f;
            return Color.color(clamp(r * dim, 0, 1), clamp(g * dim, 0, 1), clamp(b * dim, 0, 1));
        }
        if (coloringMode == 8) {
            // Neon
            float h = fract((float) Math.floor((structural + flow) * colorStrength * 8f) / 8f + paletteOffset);
            float s = 0.9f;
            float v = mix(0.5f, 1f, 1f - depth * 0.5f);
            float[] rgb = hsv2rgb(h, s, v);
            float glow = smoothstep(0.3f, 0.7f, structural) * 0.3f;
            float[] glowRgb = hsv2rgb(h, 0.5f, 1f);
            r = rgb[0] + glowRgb[0] * glow;
            g = rgb[1] + glowRgb[1] * glow;
            b = rgb[2] + glowRgb[2] * glow;
            return Color.color(clamp(r, 0, 1), clamp(g, 0, 1), clamp(b, 0, 1));
        }

        // Modes 0-5: palette-based
        float t;
        if (coloringMode == 1) {
            t = (float) Math.floor(depth * 12f) / 12f * colorStrength + paletteOffset;
        } else if (coloringMode == 2) {
            t = structural * colorStrength + paletteOffset;
        } else if (coloringMode == 3) {
            t = (float) (Math.atan2(flow - 0.5f, structural - 0.5f) / (2 * Math.PI)) + 0.5f;
            t = t * colorStrength + paletteOffset;
        } else if (coloringMode == 4) {
            t = (structural + flow + depth) * 0.333f * colorStrength + paletteOffset;
        } else if (coloringMode == 5) {
            float combined = flow * 3f + structural * 2f + depth;
            t = (float) Math.sin(combined * colorStrength * 20f) * 0.5f + 0.5f + paletteOffset;
        } else {
            // Standard (mode 0)
            t = flow * colorStrength + paletteOffset + depth * 0.1f;
        }

        Color palColor = gradient.sampleAt(fract(t));
        r = (float) palColor.getRed();
        g = (float) palColor.getGreen();
        b = (float) palColor.getBlue();

        // Structural highlights
        float hr = mix(1f, r * 1.5f, 0.5f);
        float hg = mix(1f, g * 1.5f, 0.5f);
        float hb = mix(1f, b * 1.5f, 0.5f);
        float sMix = clamp(structural * 0.9f, 0, 1);
        r = mix(r, hr, sMix);
        g = mix(g, hg, sMix);
        b = mix(b, hb, sMix);

        // Depth darkening
        float dim = 1f - depth * 0.4f;
        return Color.color(clamp(r * dim, 0, 1), clamp(g * dim, 0, 1), clamp(b * dim, 0, 1));
    }

    /** Compute getFactors() for a fractal type. */
    public static float[] computeFactors(AbstractFractalParams params, OrbitTrap trap) {
        return switch (params.getType()) {
            case MANDELBULB -> mandelbulbFactors(trap, ((MandelbulbParams) params).getMaxIterations());
            case MANDELBOX -> mandelboxFactors(trap, ((MandelboxParams) params).getMaxIterations());
            case MENGER_SPONGE -> mengerFactors(trap, ((MengerSpongeParams) params).getMaxIterations());
            case KALEIDOSCOPIC_IFS -> kaleidoscopicFactors(trap, ((KaleidoscopicIFSParams) params).getMaxIterations());
            case POLYHEDRAL_IFS -> polyhedralFactors(trap, ((PolyhedralIFSParams) params).getMaxIterations());
            case SIERPINSKI -> mandelbulbFactors(trap, ((SierpinskiParams) params).getMaxIterations());
            case PSEUDO_KLEINIAN -> pseudoKleinianFactors(trap, ((PseudoKleinianParams) params).getMaxIterations());
            case APOLLONIAN -> mandelbulbFactors(trap, ((ApollonianParams) params).getMaxIterations());
            case BRISTORBROT -> mandelbulbFactors(trap, ((BristorbrotParams) params).getMaxIterations());
            case QUATERNION_JULIA_4D -> quaternionJuliaFactors(trap, ((QuaternionJulia4DParams) params).getMaxIterations());
            case FRACTAL_TERRAIN -> fractalTerrainFactors(trap, (FractalTerrainParams) params);
            default -> new float[]{0.5f, 0.5f, 0.5f};
        };
    }

    private static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    private static float[] hsv2rgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs(fract(h * 6f) * 2f - 1f));
        float m = v - c;
        float r, g, b;
        int hi = (int) (h * 6f) % 6;
        switch (hi) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return new float[]{r + m, g + m, b + m};
    }

    // ========================================================================
    // getFactors implementations
    // ========================================================================

    private static float[] mandelbulbFactors(OrbitTrap trap, int maxIter) {
        float trapX = (float) Math.exp(-trap.planeX * 3.0);
        float trapY = (float) Math.exp(-trap.planeY * 3.0);
        float trapZ = (float) Math.exp(-trap.planeZ * 3.0);
        float structural = 1f - (float) Math.exp(-trap.minDist * 0.8);
        float flow = (trapX * 0.5f + trapY * 1.0f + trapZ * 1.5f) / 3f;
        float iterNorm = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, iterNorm};
    }

    private static float[] mandelboxFactors(OrbitTrap trap, int maxIter) {
        float structural = clamp(trap.sphereHits * 0.15f, 0, 1);
        float p = (trap.planeX + trap.planeY + trap.planeZ) * 0.33f;
        float flow = (float) Math.sin(p * 2.0) * 0.5f + 0.5f;
        float detail = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, detail};
    }

    private static float[] mengerFactors(OrbitTrap trap, int maxIter) {
        float structural = smoothstep(0.3f, 0.8f, trap.trap);
        float p = (trap.planeX + trap.planeY + trap.planeZ) * 0.33f;
        float flow = (float) Math.sin(p * 5.0) * 0.5f + 0.5f;
        float detail = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, detail};
    }

    private static float[] kaleidoscopicFactors(OrbitTrap trap, int maxIter) {
        float structural = 1f - (float) Math.exp(-trap.minDist * 4.0);
        float flow = (float) Math.sin(trap.sumDist * 0.2) * 0.5f + 0.5f;
        float detail = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, detail};
    }

    private static float[] quaternionJuliaFactors(OrbitTrap trap, int maxIter) {
        float structural = clamp(trap.lastDist * 0.2f, 0, 1);
        float flow = smoothstep(0f, 2f, trap.avgDist);
        float detail = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, detail};
    }

    private static float[] polyhedralFactors(OrbitTrap trap, int maxIter) {
        float structural = 1f - (float) Math.exp(-(float) Math.sqrt(trap.minDist) * 5.0);
        float p = (trap.planeX + trap.planeY + trap.planeZ) * 0.33f;
        float flow = (float) Math.sin(p * 2.0) * 0.5f + 0.5f;
        float detail = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, detail};
    }

    private static float[] pseudoKleinianFactors(OrbitTrap trap, int maxIter) {
        float trapX = (float) Math.exp(-trap.planeX * 2.0);
        float trapY = (float) Math.exp(-trap.planeY * 2.0);
        float trapZ = (float) Math.exp(-trap.planeZ * 2.0);
        float structural = 1f - (float) Math.exp(-trap.minDist * 0.5);
        float flow = (trapX * 0.5f + trapY * 1.0f + trapZ * 1.5f) / 3f;
        float iterNorm = (float) trap.iterations / Math.max(maxIter, 1);
        return new float[]{structural, flow, iterNorm};
    }

    // ========================================================================
    // Mandelbulb
    // ========================================================================

    private static float mandelbulbDE(float px, float py, float pz, MandelbulbParams p) {
        float zx = px, zy = py, zz = pz;
        float dr = 1f, r = 0f;
        float power = p.getPower();
        int maxIter = p.getMaxIterations();
        float bailout = p.getBailout();

        for (int i = 0; i < maxIter; i++) {
            r = length3(zx, zy, zz);
            if (r > bailout) break;
            if (r < 1e-21f) break;
            float theta = (float) Math.acos(clamp(zz / r, -1f, 1f));
            float phi = (float) Math.atan2(zy, zx);
            dr = (float) Math.pow(r, power - 1) * power * dr + 1f;
            float zr = (float) Math.pow(r, power);
            theta *= power;
            phi *= power;
            zx = (float) (zr * Math.sin(theta) * Math.cos(phi)) + px;
            zy = (float) (zr * Math.sin(theta) * Math.sin(phi)) + py;
            zz = (float) (zr * Math.cos(theta)) + pz;
        }
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * (float) Math.log(r) * r / dr;
    }

    private static float mandelbulbDEFull(float px, float py, float pz, MandelbulbParams p, OrbitTrap trap) {
        float zx = px, zy = py, zz = pz;
        float dr = 1f, r = 0f;
        float power = p.getPower();
        int maxIter = p.getMaxIterations();
        float bailout = p.getBailout();
        trap.minDist = 1e10f; trap.planeX = 1e10f; trap.planeY = 1e10f; trap.planeZ = 1e10f; trap.iterations = 0;

        for (int i = 0; i < maxIter; i++) {
            r = length3(zx, zy, zz);
            if (r > bailout) break;
            if (r < 1e-21f) break;
            float theta = (float) Math.acos(clamp(zz / r, -1f, 1f));
            float phi = (float) Math.atan2(zy, zx);
            dr = (float) Math.pow(r, power - 1) * power * dr + 1f;
            float zr = (float) Math.pow(r, power);
            theta *= power; phi *= power;
            zx = (float) (zr * Math.sin(theta) * Math.cos(phi)) + px;
            zy = (float) (zr * Math.sin(theta) * Math.sin(phi)) + py;
            zz = (float) (zr * Math.cos(theta)) + pz;
            trap.minDist = Math.min(trap.minDist, length3(zx, zy, zz));
            trap.planeX = Math.min(trap.planeX, Math.abs(zx));
            trap.planeY = Math.min(trap.planeY, Math.abs(zy));
            trap.planeZ = Math.min(trap.planeZ, Math.abs(zz));
            trap.iterations = i + 1;
        }
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * (float) Math.log(r) * r / dr;
    }

    // ========================================================================
    // Mandelbox
    // ========================================================================

    private static float mandelboxDE(float px, float py, float pz, MandelboxParams p) {
        float zx = px, zy = py, zz = pz;
        float dz = 1f;
        float scale = p.getScale();
        float minR2 = p.getMinRadius() * p.getMinRadius();
        float fixR2 = p.getFixedRadius() * p.getFixedRadius();
        float fold = p.getFoldingLimit();
        int maxIter = p.getMaxIterations();

        for (int i = 0; i < maxIter; i++) {
            zx = clamp(zx, -fold, fold) * 2f - zx;
            zy = clamp(zy, -fold, fold) * 2f - zy;
            zz = clamp(zz, -fold, fold) * 2f - zz;
            float r2 = zx * zx + zy * zy + zz * zz;
            if (r2 < minR2) {
                float s = fixR2 / minR2; zx *= s; zy *= s; zz *= s; dz *= s;
            } else if (r2 < fixR2) {
                float s = fixR2 / r2; zx *= s; zy *= s; zz *= s; dz *= s;
            }
            zx = zx * scale + px; zy = zy * scale + py; zz = zz * scale + pz;
            dz = dz * Math.abs(scale) + 1f;
            if (zx * zx + zy * zy + zz * zz > 1e6f) break;
        }
        float adz = Math.abs(dz);
        if (adz < 1e-21f) return 0f;
        return length3(zx, zy, zz) / adz;
    }

    private static float mandelboxDEFull(float px, float py, float pz, MandelboxParams p, OrbitTrap trap) {
        float zx = px, zy = py, zz = pz;
        float dz = 1f;
        float scale = p.getScale(); float minR2 = p.getMinRadius() * p.getMinRadius();
        float fixR2 = p.getFixedRadius() * p.getFixedRadius(); float fold = p.getFoldingLimit();
        int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.avgFold = 0; trap.sphereHits = 0; trap.planeX = 0; trap.planeY = 0; trap.planeZ = 0; trap.iterations = 0;

        for (int i = 0; i < maxIter; i++) {
            float ox = zx, oy = zy, oz = zz;
            zx = clamp(zx, -fold, fold) * 2f - zx;
            zy = clamp(zy, -fold, fold) * 2f - zy;
            zz = clamp(zz, -fold, fold) * 2f - zz;
            trap.avgFold += length3(zx - ox, zy - oy, zz - oz);
            float r2 = zx * zx + zy * zy + zz * zz;
            float r2Before = r2;
            if (r2 < minR2) { float s = fixR2 / minR2; zx *= s; zy *= s; zz *= s; dz *= s; }
            else if (r2 < fixR2) { float s = fixR2 / r2; zx *= s; zy *= s; zz *= s; dz *= s; }
            float r2After = zx * zx + zy * zy + zz * zz;
            if (r2After != r2Before) trap.sphereHits += 1f;
            zx = zx * scale + px; zy = zy * scale + py; zz = zz * scale + pz;
            dz = dz * Math.abs(scale) + 1f;
            float dist = length3(zx, zy, zz);
            trap.minDist = Math.min(trap.minDist, dist);
            float pw = (float) Math.pow(Math.abs(scale), i * 0.5f);
            trap.planeX += Math.abs(zx) / pw;
            trap.planeY += Math.abs(zy) / pw;
            trap.planeZ += Math.abs(zz) / pw;
            if (dist > 1000f) break;
            trap.iterations = i + 1;
        }
        trap.avgFold /= Math.max(trap.iterations, 1);
        float adz = Math.abs(dz);
        if (adz < 1e-21f) return 0f;
        return length3(zx, zy, zz) / adz;
    }

    // ========================================================================
    // Menger Sponge
    // ========================================================================

    private static float sdBox(float px, float py, float pz, float bx, float by, float bz) {
        float dx = Math.abs(px) - bx, dy = Math.abs(py) - by, dz = Math.abs(pz) - bz;
        return Math.min(Math.max(dx, Math.max(dy, dz)), 0f) + length3(Math.max(dx, 0), Math.max(dy, 0), Math.max(dz, 0));
    }

    private static float sdCross(float px, float py, float pz) {
        float da = Math.max(Math.abs(px), Math.abs(py));
        float db = Math.max(Math.abs(py), Math.abs(pz));
        float dc = Math.max(Math.abs(pz), Math.abs(px));
        return Math.min(Math.min(da, db), dc) - 1f;
    }

    private static float mengerDE(float px, float py, float pz, MengerSpongeParams p) {
        float d = sdBox(px, py, pz, 1, 1, 1);
        float s = 1f; float scale = p.getScale(); int maxIter = p.getMaxIterations();
        for (int m = 0; m < maxIter; m++) {
            float ax = mod(px * s, 2f) - 1f, ay = mod(py * s, 2f) - 1f, az = mod(pz * s, 2f) - 1f;
            s *= scale;
            float rx = 1f - scale * Math.abs(ax), ry = 1f - scale * Math.abs(ay), rz = 1f - scale * Math.abs(az);
            float c = sdCross(rx, ry, rz) / s;
            d = Math.max(d, c);
        }
        return d;
    }

    private static float mengerDEFull(float px, float py, float pz, MengerSpongeParams p, OrbitTrap trap) {
        float d = sdBox(px, py, pz, 1, 1, 1);
        float s = 1f; float scale = p.getScale(); int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.trap = 0; trap.planeX = 0; trap.planeY = 0; trap.planeZ = 0; trap.iterations = 0;
        for (int m = 0; m < maxIter; m++) {
            float ax = mod(px * s, 2f) - 1f, ay = mod(py * s, 2f) - 1f, az = mod(pz * s, 2f) - 1f;
            s *= scale;
            float rx = 1f - scale * Math.abs(ax), ry = 1f - scale * Math.abs(ay), rz = 1f - scale * Math.abs(az);
            float c = sdCross(rx, ry, rz) / s;
            d = Math.max(d, c);
            trap.minDist = Math.min(trap.minDist, length3(rx, ry, rz));
            trap.planeX += Math.abs(rx) / s;
            trap.planeY += Math.abs(ry) / s;
            trap.planeZ += Math.abs(rz) / s;
            trap.trap += length3(ax, ay, az);
            trap.iterations = m + 1;
        }
        trap.trap /= maxIter;
        return d;
    }

    // ========================================================================
    // Kaleidoscopic IFS
    // ========================================================================

    private static float kaleidoscopicDE(float px, float py, float pz, KaleidoscopicIFSParams p) {
        float zx = px, zy = py, zz = pz;
        float scale = p.getScale(); float offset = p.getOffsetX();
        float fax = (float) Math.toRadians(p.getFoldAngleX());
        float fay = (float) Math.toRadians(p.getFoldAngleY());
        int maxIter = p.getMaxIterations();
        int n;
        for (n = 0; n < maxIter; n++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; }
            if (Math.abs(fax) > 0.0001f) {
                float c = (float) Math.cos(fax), s = (float) Math.sin(fax);
                float ny = c * zy - s * zz, nz = s * zy + c * zz; zy = ny; zz = nz;
            }
            if (Math.abs(fay) > 0.0001f) {
                float c = (float) Math.cos(fay), s = (float) Math.sin(fay);
                float nx = c * zx + s * zz, nz = -s * zx + c * zz; zx = nx; zz = nz;
            }
            zx = zx * scale - offset * (scale - 1f);
            zy = zy * scale - offset * (scale - 1f);
            zz = zz * scale - offset * (scale - 1f);
        }
        return length3(zx, zy, zz) * (float) Math.pow(scale, -n);
    }

    private static float kaleidoscopicDEFull(float px, float py, float pz, KaleidoscopicIFSParams p, OrbitTrap trap) {
        float zx = px, zy = py, zz = pz;
        float scale = p.getScale(); float offset = p.getOffsetX();
        float fax = (float) Math.toRadians(p.getFoldAngleX());
        float fay = (float) Math.toRadians(p.getFoldAngleY());
        int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.sumDist = 0; trap.avgFold = 0; trap.iterations = 0;
        float foldSum = 0;
        int n;
        for (n = 0; n < maxIter; n++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; foldSum += 1; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; foldSum += 1; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; foldSum += 1; }
            if (Math.abs(fax) > 0.0001f) {
                float c = (float) Math.cos(fax), s = (float) Math.sin(fax);
                float ny = c * zy - s * zz, nz = s * zy + c * zz; zy = ny; zz = nz;
            }
            if (Math.abs(fay) > 0.0001f) {
                float c = (float) Math.cos(fay), s = (float) Math.sin(fay);
                float nx = c * zx + s * zz, nz = -s * zx + c * zz; zx = nx; zz = nz;
            }
            zx = zx * scale - offset * (scale - 1f);
            zy = zy * scale - offset * (scale - 1f);
            zz = zz * scale - offset * (scale - 1f);
            float dist = length3(zx, zy, zz);
            trap.minDist = Math.min(trap.minDist, dist);
            trap.sumDist += dist;
        }
        trap.iterations = n;
        trap.avgFold = foldSum / n;
        return length3(zx, zy, zz) * (float) Math.pow(scale, -n);
    }

    // ========================================================================
    // Polyhedral IFS
    // ========================================================================

    private static final float PHI = 1.61803398875f;
    private static final float _IKVNORM_ = 0.19098593171f;

    private static float[] createRotationMatrix(float rx, float ry, float rz) {
        float cx = (float) Math.cos(rx);
        float sx = (float) Math.sin(rx);
        float cy = (float) Math.cos(ry);
        float sy = (float) Math.sin(ry);
        float cz = (float) Math.cos(rz);
        float sz = (float) Math.sin(rz);
        return new float[] {
            cy*cz, sx*sy*cz + cx*sz, -cx*sy*cz + sx*sz,
            -cy*sz, -sx*sy*sz + cx*cz, cx*sy*sz + sx*cz,
            sy, -sx*cy, cx*cy
        };
    }

    private static float[] mat3Mul(float[] m, float x, float y, float z) {
        return new float[]{
            x * m[0] + y * m[3] + z * m[6],
            x * m[1] + y * m[4] + z * m[7],
            x * m[2] + y * m[5] + z * m[8]
        };
    }

    private static float polyhedralDE(float px, float py, float pz, PolyhedralIFSParams p) {
        float scale = p.getScale();
        int maxIter = p.getMaxIterations();
        int polyType = p.getPolyType().ordinal();
        float[] rot1 = createRotationMatrix((float)Math.toRadians(p.getRot1X()), (float)Math.toRadians(p.getRot1Y()), (float)Math.toRadians(p.getRot1Z()));
        float[] rot2 = createRotationMatrix((float)Math.toRadians(p.getRot2X()), (float)Math.toRadians(p.getRot2Y()), (float)Math.toRadians(p.getRot2Z()));
        float sox = p.getOffsetX() * (scale - 1f), soy = p.getOffsetY() * (scale - 1f), soz = p.getOffsetZ() * (scale - 1f);
        float shx = p.getShiftX(), shy = p.getShiftY(), shz = p.getShiftZ();
        float wx = px, wy = py, wz = pz;
        int i;
        for (i = 0; i < maxIter; i++) {
            float[] r1 = mat3Mul(rot1, wx, wy, wz); wx = r1[0]; wy = r1[1]; wz = r1[2];
            if (polyType == 0) { // Octahedral
                wx = Math.abs(wx + shx) - shx; wy = Math.abs(wy + shy) - shy; wz = Math.abs(wz + shz) - shz;
                if (wx < wy) { float t = wx; wx = wy; wy = t; }
                if (wx < wz) { float t = wx; wx = wz; wz = t; }
                if (wy < wz) { float t = wy; wy = wz; wz = t; }
            } else if (polyType == 1) { // Dodecahedron
                float t;
                t = wx * 0.5f * PHI + wy * (0.5f / PHI) - wz * 0.5f;
                if (t < 0) { wx -= 2*t*0.5f*PHI; wy -= 2*t*(0.5f/PHI); wz += 2*t*0.5f; }
                t = -wx * 0.5f + wy * 0.5f * PHI + wz * (0.5f / PHI);
                if (t < 0) { wx += 2*t*0.5f; wy -= 2*t*0.5f*PHI; wz -= 2*t*(0.5f/PHI); }
                t = wx * (0.5f / PHI) - wy * 0.5f + wz * 0.5f * PHI;
                if (t < 0) { wx -= 2*t*(0.5f/PHI); wy += 2*t*0.5f; wz -= 2*t*0.5f*PHI; }
                float c3x = PHI * (1f + PHI) * _IKVNORM_, c3y = (PHI * PHI - 1f) * _IKVNORM_, c3z = (1f + PHI) * _IKVNORM_;
                t = -wx * c3x + wy * c3y + wz * c3z;
                if (t < 0) { wx += 2*t*c3x; wy -= 2*t*c3y; wz -= 2*t*c3z; }
                t = wx * c3z - wy * c3x + wz * c3y;
                if (t < 0) { wx -= 2*t*c3z; wy += 2*t*c3x; wz -= 2*t*c3y; }
            } else if (polyType == 2) { // Icosahedron
                wx = Math.abs(wx); wy = Math.abs(wy); wz = Math.abs(wz);
                float t;
                t = wx*-0.80901699437f + wy*0.30901699437f + wz*0.5f; if (t > 0) { wx -= 2*t*-0.80901699437f; wy -= 2*t*0.30901699437f; wz -= 2*t*0.5f; }
                t = wx*0.30901699437f + wy*-0.5f + wz*0.80901699437f; if (t > 0) { wx -= 2*t*0.30901699437f; wy -= 2*t*-0.5f; wz -= 2*t*0.80901699437f; }
                t = wz*-1f; if (t > 0) { wz -= 2*t*-1f; }
                t = wx*0.30901699437f + wy*-0.5f + wz*0.80901699437f; if (t > 0) { wx -= 2*t*0.30901699437f; wy -= 2*t*-0.5f; wz -= 2*t*0.80901699437f; }
            } else if (polyType == 3) { // Tetrahedron
                if (wx + wy < 0) { float t = wx; wx = -wy; wy = -t; }
                if (wx + wz < 0) { float t = wx; wx = -wz; wz = -t; }
                if (wy + wz < 0) { float t = wy; wy = -wz; wz = -t; }
            }
            float[] r2 = mat3Mul(rot2, wx, wy, wz); wx = r2[0]; wy = r2[1]; wz = r2[2];
            wx = wx * scale - sox; wy = wy * scale - soy; wz = wz * scale - soz;
        }
        return (length3(wx, wy, wz) - 2f) * (float) Math.pow(scale, -maxIter);
    }

    private static float polyhedralDEFull(float px, float py, float pz, PolyhedralIFSParams p, OrbitTrap trap) {
        float scale = p.getScale();
        int maxIter = p.getMaxIterations();
        int polyType = p.getPolyType().ordinal();
        float[] rot1 = createRotationMatrix((float)Math.toRadians(p.getRot1X()), (float)Math.toRadians(p.getRot1Y()), (float)Math.toRadians(p.getRot1Z()));
        float[] rot2 = createRotationMatrix((float)Math.toRadians(p.getRot2X()), (float)Math.toRadians(p.getRot2Y()), (float)Math.toRadians(p.getRot2Z()));
        float sox = p.getOffsetX() * (scale - 1f), soy = p.getOffsetY() * (scale - 1f), soz = p.getOffsetZ() * (scale - 1f);
        float shx = p.getShiftX(), shy = p.getShiftY(), shz = p.getShiftZ();
        float wx = px, wy = py, wz = pz;
        trap.minDist = 1e10f; trap.sumDist = 0; trap.planeX = 0; trap.planeY = 0; trap.planeZ = 0; trap.iterations = 0;
        int i;
        for (i = 0; i < maxIter; i++) {
            float[] r1 = mat3Mul(rot1, wx, wy, wz); wx = r1[0]; wy = r1[1]; wz = r1[2];
            if (polyType == 0) { // Octahedral
                wx = Math.abs(wx + shx) - shx; wy = Math.abs(wy + shy) - shy; wz = Math.abs(wz + shz) - shz;
                if (wx < wy) { float t = wx; wx = wy; wy = t; }
                if (wx < wz) { float t = wx; wx = wz; wz = t; }
                if (wy < wz) { float t = wy; wy = wz; wz = t; }
            } else if (polyType == 1) { // Dodecahedron
                float t;
                t = wx * 0.5f * PHI + wy * (0.5f / PHI) - wz * 0.5f;
                if (t < 0) { wx -= 2*t*0.5f*PHI; wy -= 2*t*(0.5f/PHI); wz += 2*t*0.5f; }
                t = -wx * 0.5f + wy * 0.5f * PHI + wz * (0.5f / PHI);
                if (t < 0) { wx += 2*t*0.5f; wy -= 2*t*0.5f*PHI; wz -= 2*t*(0.5f/PHI); }
                t = wx * (0.5f / PHI) - wy * 0.5f + wz * 0.5f * PHI;
                if (t < 0) { wx -= 2*t*(0.5f/PHI); wy += 2*t*0.5f; wz -= 2*t*0.5f*PHI; }
                float c3x = PHI * (1f + PHI) * _IKVNORM_, c3y = (PHI * PHI - 1f) * _IKVNORM_, c3z = (1f + PHI) * _IKVNORM_;
                t = -wx * c3x + wy * c3y + wz * c3z;
                if (t < 0) { wx += 2*t*c3x; wy -= 2*t*c3y; wz -= 2*t*c3z; }
                t = wx * c3z - wy * c3x + wz * c3y;
                if (t < 0) { wx -= 2*t*c3z; wy += 2*t*c3x; wz -= 2*t*c3y; }
            } else if (polyType == 2) { // Icosahedron
                wx = Math.abs(wx); wy = Math.abs(wy); wz = Math.abs(wz);
                float t;
                t = wx*-0.80901699437f + wy*0.30901699437f + wz*0.5f; if (t > 0) { wx -= 2*t*-0.80901699437f; wy -= 2*t*0.30901699437f; wz -= 2*t*0.5f; }
                t = wx*0.30901699437f + wy*-0.5f + wz*0.80901699437f; if (t > 0) { wx -= 2*t*0.30901699437f; wy -= 2*t*-0.5f; wz -= 2*t*0.80901699437f; }
                t = wz*-1f; if (t > 0) { wz -= 2*t*-1f; }
                t = wx*0.30901699437f + wy*-0.5f + wz*0.80901699437f; if (t > 0) { wx -= 2*t*0.30901699437f; wy -= 2*t*-0.5f; wz -= 2*t*0.80901699437f; }
            } else if (polyType == 3) { // Tetrahedron
                if (wx + wy < 0) { float t = wx; wx = -wy; wy = -t; }
                if (wx + wz < 0) { float t = wx; wx = -wz; wz = -t; }
                if (wy + wz < 0) { float t = wy; wy = -wz; wz = -t; }
            }
            float[] r2 = mat3Mul(rot2, wx, wy, wz); wx = r2[0]; wy = r2[1]; wz = r2[2];
            float pw = (float) Math.pow(scale, i * 0.5f);
            trap.planeX += Math.abs(wx) / pw; trap.planeY += Math.abs(wy) / pw; trap.planeZ += Math.abs(wz) / pw;
            float d2 = wx*wx + wy*wy + wz*wz; trap.minDist = Math.min(trap.minDist, d2); trap.sumDist += d2 / pw;
            wx = wx * scale - sox; wy = wy * scale - soy; wz = wz * scale - soz;
            trap.iterations = i + 1;
        }
        return (length3(wx, wy, wz) - 2f) * (float) Math.pow(scale, -maxIter);
    }

    private static void applyPolyFold(int polyType, float shx, float shy, float shz) { /* marker */ }
    private static void polyFoldInPlace(int polyType, float shx, float shy, float shz, float wx, float wy, float wz) { /* marker */ }
    private static float[] polyFold(int polyType, float shx, float shy, float shz, float wx, float wy, float wz) { return new float[]{wx, wy, wz}; }

    // ========================================================================
    // Sierpinski Tetrahedron
    // ========================================================================

    private static float sierpinskiDE(float px, float py, float pz, SierpinskiParams p) {
        float zx = px, zy = py, zz = pz;
        float s = p.getScale(); int maxIter = p.getMaxIterations();
        for (int i = 0; i < maxIter; i++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; }
            zx = zx * s - (s - 1f); zy = zy * s - (s - 1f); zz = zz * s - (s - 1f);
        }
        return (length3(zx, zy, zz) - 2f) * (float) Math.pow(s, -maxIter);
    }

    private static float sierpinskiDEFull(float px, float py, float pz, SierpinskiParams p, OrbitTrap trap) {
        float zx = px, zy = py, zz = pz;
        float s = p.getScale(); int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.planeX = 1e10f; trap.planeY = 1e10f; trap.planeZ = 1e10f; trap.iterations = 0;
        for (int i = 0; i < maxIter; i++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; }
            zx = zx * s - (s - 1f); zy = zy * s - (s - 1f); zz = zz * s - (s - 1f);
            trap.minDist = Math.min(trap.minDist, length3(zx, zy, zz));
            trap.planeX = Math.min(trap.planeX, Math.abs(zx));
            trap.planeY = Math.min(trap.planeY, Math.abs(zy));
            trap.planeZ = Math.min(trap.planeZ, Math.abs(zz));
            trap.iterations = i + 1;
        }
        return (length3(zx, zy, zz) - 2f) * (float) Math.pow(s, -maxIter);
    }

    // ========================================================================
    // Pseudo-Kleinian
    // ========================================================================

    private static float pseudoKleinianDE(float px, float py, float pz, PseudoKleinianParams p) {
        float x = mod(px + 3f, 6f) - 3f;
        float y = mod(py + 2f, 4f) - 2f;
        float z = pz;
        float DEfactor = 1.5f;
        float apx = x + 1, apy = y + 1, apz = z + 1;
        float csx = p.getCSizeX(), csy = p.getCSizeY(), csz = p.getCSizeZ();
        float size = p.getSize(); float deOff = p.getDEOffset();
        float fcx = p.getFoldCx(), fcy = p.getFoldCy(), fcz = p.getFoldCz();
        int maxIter = p.getMaxIterations();
        for (int i = 0; i < maxIter; i++) {
            if (apx == x && apy == y && apz == z) break;
            apx = x; apy = y; apz = z;
            x = 2f * clamp(x, -csx, csx) - x; y = 2f * clamp(y, -csy, csy) - y; z = 2f * clamp(z, -csz, csz) - z;
            float r2 = x*x + y*y + z*z; float k = Math.max(size / r2, 1f);
            x *= k; y *= k; z *= k; DEfactor *= k;
            x += fcx; y += fcy; z += fcz;
        }
        return Math.abs(0.5f * Math.abs(z + 0.1f) / DEfactor - deOff);
    }

    private static float pseudoKleinianDEFull(float px, float py, float pz, PseudoKleinianParams p, OrbitTrap trap) {
        float x = mod(px + 3f, 6f) - 3f;
        float y = mod(py + 2f, 4f) - 2f;
        float z = pz;
        float DEfactor = 1.5f;
        float apx = x + 1, apy = y + 1, apz = z + 1;
        float csx = p.getCSizeX(), csy = p.getCSizeY(), csz = p.getCSizeZ();
        float size = p.getSize(); float deOff = p.getDEOffset();
        float fcx = p.getFoldCx(), fcy = p.getFoldCy(), fcz = p.getFoldCz();
        int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.planeX = 1e10f; trap.planeY = 1e10f; trap.planeZ = 1e10f; trap.iterations = 0;
        for (int i = 0; i < maxIter; i++) {
            if (apx == x && apy == y && apz == z) break;
            apx = x; apy = y; apz = z;
            x = 2f * clamp(x, -csx, csx) - x; y = 2f * clamp(y, -csy, csy) - y; z = 2f * clamp(z, -csz, csz) - z;
            float r2 = x*x + y*y + z*z; float k = Math.max(size / r2, 1f);
            x *= k; y *= k; z *= k; DEfactor *= k;
            x += fcx; y += fcy; z += fcz;
            trap.minDist = Math.min(trap.minDist, length3(x, y, z));
            trap.planeX = Math.min(trap.planeX, Math.abs(x)); trap.planeY = Math.min(trap.planeY, Math.abs(y)); trap.planeZ = Math.min(trap.planeZ, Math.abs(z));
            trap.iterations = i + 1;
        }
        return Math.abs(0.5f * Math.abs(z + 0.1f) / DEfactor - deOff);
    }

    // ========================================================================
    // Apollonian Gasket
    // ========================================================================

    private static float apollonianDE(float px, float py, float pz, ApollonianParams p) {
        float zx = px, zy = py, zz = pz;
        float s = 1f; float scale = p.getScale(); float fr2 = p.getFoldRadius() * p.getFoldRadius();
        int maxIter = p.getMaxIterations();
        for (int i = 0; i < maxIter; i++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; }
            zx = zx * scale - (scale - 1f); zy = zy * scale - (scale - 1f); zz = zz * scale - (scale - 1f);
            s *= scale; float r2 = zx*zx + zy*zy + zz*zz;
            if (r2 < fr2) { float k = fr2 / r2; zx *= k; zy *= k; zz *= k; s *= k; }
        }
        return (length3(zx, zy, zz) - 2f) / s;
    }

    private static float apollonianDEFull(float px, float py, float pz, ApollonianParams p, OrbitTrap trap) {
        float zx = px, zy = py, zz = pz;
        float s = 1f; float scale = p.getScale(); float fr2 = p.getFoldRadius() * p.getFoldRadius();
        int maxIter = p.getMaxIterations();
        trap.minDist = 1e10f; trap.planeX = 1e10f; trap.planeY = 1e10f; trap.planeZ = 1e10f; trap.iterations = 0;
        for (int i = 0; i < maxIter; i++) {
            if (zx + zy < 0) { float t = zx; zx = -zy; zy = -t; }
            if (zx + zz < 0) { float t = zx; zx = -zz; zz = -t; }
            if (zy + zz < 0) { float t = zy; zy = -zz; zz = -t; }
            zx = zx * scale - (scale - 1f); zy = zy * scale - (scale - 1f); zz = zz * scale - (scale - 1f);
            s *= scale; float r2 = zx*zx + zy*zy + zz*zz;
            if (r2 < fr2) { float k = fr2 / r2; zx *= k; zy *= k; zz *= k; s *= k; }
            trap.minDist = Math.min(trap.minDist, length3(zx, zy, zz));
            trap.planeX = Math.min(trap.planeX, Math.abs(zx)); trap.planeY = Math.min(trap.planeY, Math.abs(zy)); trap.planeZ = Math.min(trap.planeZ, Math.abs(zz));
            trap.iterations = i + 1;
        }
        return (length3(zx, zy, zz) - 2f) / s;
    }

    // ========================================================================
    // Bristorbrot
    // ========================================================================

    private static float bristorbrotDE(float px, float py, float pz, BristorbrotParams p) {
        boolean isJulia = p.getJuliaCx()*p.getJuliaCx() + p.getJuliaCy()*p.getJuliaCy() + p.getJuliaCz()*p.getJuliaCz() > 0.0001f;
        float cx = isJulia ? p.getJuliaCx() : px, cy = isJulia ? p.getJuliaCy() : py, cz = isJulia ? p.getJuliaCz() : pz;
        float zx = px, zy = py, zz = pz, dr = 1f, r = 0f;
        int maxIter = p.getMaxIterations(); float bailout = p.getBailout();
        for (int i = 0; i < maxIter; i++) {
            r = length3(zx, zy, zz); if (r > bailout) break;
            dr = 2f * r * dr + 1f;
            float nx = zx*zx - zy*zy - zz*zz + cx, ny = 2f*zx*zy + cy, nz = -2f*zx*zz + cz;
            zx = nx; zy = ny; zz = nz;
        }
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * (float) Math.log(r) * r / dr;
    }

    private static float bristorbrotDEFull(float px, float py, float pz, BristorbrotParams p, OrbitTrap trap) {
        boolean isJulia = p.getJuliaCx()*p.getJuliaCx() + p.getJuliaCy()*p.getJuliaCy() + p.getJuliaCz()*p.getJuliaCz() > 0.0001f;
        float cx = isJulia ? p.getJuliaCx() : px, cy = isJulia ? p.getJuliaCy() : py, cz = isJulia ? p.getJuliaCz() : pz;
        float zx = px, zy = py, zz = pz, dr = 1f, r = 0f;
        int maxIter = p.getMaxIterations(); float bailout = p.getBailout();
        trap.minDist = 1e10f; trap.planeX = 1e10f; trap.planeY = 1e10f; trap.planeZ = 1e10f; trap.iterations = 0;
        for (int i = 0; i < maxIter; i++) {
            r = length3(zx, zy, zz); if (r > bailout) break;
            dr = 2f * r * dr + 1f;
            float nx = zx*zx - zy*zy - zz*zz + cx, ny = 2f*zx*zy + cy, nz = -2f*zx*zz + cz;
            zx = nx; zy = ny; zz = nz;
            trap.minDist = Math.min(trap.minDist, length3(zx, zy, zz));
            trap.planeX = Math.min(trap.planeX, Math.abs(zx)); trap.planeY = Math.min(trap.planeY, Math.abs(zy)); trap.planeZ = Math.min(trap.planeZ, Math.abs(zz));
            trap.iterations = i + 1;
        }
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * (float) Math.log(r) * r / dr;
    }

    // ========================================================================
    // Quaternion Julia 4D
    // ========================================================================

    private static float[] apply4DRotation(float qx, float qy, float qz, float qw, float rotXW, float rotYW, float rotZW) {
        float c, s, tmp;
        if (Math.abs(rotXW) > 0.0001f) { c = (float) Math.cos(rotXW); s = (float) Math.sin(rotXW); tmp = c * qx - s * qw; qw = s * qx + c * qw; qx = tmp; }
        if (Math.abs(rotYW) > 0.0001f) { c = (float) Math.cos(rotYW); s = (float) Math.sin(rotYW); tmp = c * qy - s * qw; qw = s * qy + c * qw; qy = tmp; }
        if (Math.abs(rotZW) > 0.0001f) { c = (float) Math.cos(rotZW); s = (float) Math.sin(rotZW); tmp = c * qz - s * qw; qw = s * qz + c * qw; qz = tmp; }
        return new float[]{qx, qy, qz, qw};
    }

    private static float quaternionJulia4dDE(float px, float py, float pz, QuaternionJulia4DParams p) {
        float[] q0 = apply4DRotation(px, py, pz, p.getSliceW(), (float)Math.toRadians(p.getRotXW()), (float)Math.toRadians(p.getRotYW()), (float)Math.toRadians(p.getRotZW()));
        float qx = q0[0], qy = q0[1], qz = q0[2], qw = q0[3], dqx = 1, dqy = 0, dqz = 0, dqw = 0;
        float cx = p.getJuliaCx(), cy = p.getJuliaCy(), cz = p.getJuliaCz(), cw = p.getJuliaCw();
        int maxIter = p.getMaxIterations(); float bailout = p.getBailout(), r2 = qx*qx + qy*qy + qz*qz + qw*qw;
        for (int i = 0; i < maxIter; i++) {
            if (r2 > bailout) break;
            float tdx = 2*(qx*dqx - qy*dqy - qz*dqz - qw*dqw), tdy = 2*(qx*dqy + qy*dqx + qz*dqw - qw*dqz);
            float tdz = 2*(qx*dqz - qy*dqw + qz*dqx + qw*dqy), tdw = 2*(qx*dqw + qy*dqz - qz*dqy + qw*dqx);
            dqx = tdx; dqy = tdy; dqz = tdz; dqw = tdw;
            float nx = qx*qx - qy*qy - qz*qz - qw*qw + cx, ny = 2*qx*qy + cy, nz = 2*qx*qz + cz, nw = 2*qx*qw + cw;
            qx = nx; qy = ny; qz = nz; qw = nw; r2 = qx*qx + qy*qy + qz*qz + qw*qw;
        }
        float r = (float) Math.sqrt(r2), dr = (float) Math.sqrt(dqx*dqx + dqy*dqy + dqz*dqz + dqw*dqw);
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * r * (float) Math.log(r) / dr;
    }

    private static float quaternionJulia4dDEFull(float px, float py, float pz, QuaternionJulia4DParams p, OrbitTrap trap) {
        float[] q0 = apply4DRotation(px, py, pz, p.getSliceW(), (float)Math.toRadians(p.getRotXW()), (float)Math.toRadians(p.getRotYW()), (float)Math.toRadians(p.getRotZW()));
        float qx = q0[0], qy = q0[1], qz = q0[2], qw = q0[3], dqx = 1, dqy = 0, dqz = 0, dqw = 0;
        float cx = p.getJuliaCx(), cy = p.getJuliaCy(), cz = p.getJuliaCz(), cw = p.getJuliaCw();
        int maxIter = p.getMaxIterations(); float bailout = p.getBailout();
        trap.minDist = 1e10f; trap.avgDist = 0; trap.lastDist = 0; trap.iterations = 0;
        float r2 = qx*qx + qy*qy + qz*qz + qw*qw;
        for (int i = 0; i < maxIter; i++) {
            if (r2 > bailout) break;
            float tdx = 2*(qx*dqx - qy*dqy - qz*dqz - qw*dqw), tdy = 2*(qx*dqy + qy*dqx + qz*dqw - qw*dqz);
            float tdz = 2*(qx*dqz - qy*dqw + qz*dqx + qw*dqy), tdw = 2*(qx*dqw + qy*dqz - qz*dqy + qw*dqx);
            dqx = tdx; dqy = tdy; dqz = tdz; dqw = tdw;
            float nx = qx*qx - qy*qy - qz*qz - qw*qw + cx, ny = 2*qx*qy + cy, nz = 2*qx*qz + cz, nw = 2*qx*qw + cw;
            qx = nx; qy = ny; qz = nz; qw = nw; r2 = qx*qx + qy*qy + qz*qz + qw*qw;
            float dist = (float) Math.sqrt(r2); trap.minDist = Math.min(trap.minDist, dist); trap.avgDist += dist; trap.lastDist = dist; trap.iterations = i + 1;
        }
        trap.avgDist /= Math.max(trap.iterations, 1);
        float r = (float) Math.sqrt(r2), dr = (float) Math.sqrt(dqx*dqx + dqy*dqy + dqz*dqz + dqw*dqw);
        if (r < 1e-21f || dr < 1e-21f) return 0f;
        return 0.5f * r * (float) Math.log(r) / dr;
    }

    // ========================================================================
    // Fractal Terrain
    // ========================================================================

    private static float terrainHash(float px, float py) {
        float p3x = fract(px * 0.1031f), p3y = fract(py * 0.1031f), p3z = fract(px * 0.1031f);
        float d = p3x * (p3y + 33.33f) + p3y * (p3z + 33.33f) + p3z * (p3x + 33.33f);
        p3x += d; p3y += d; p3z += d; return fract((p3x + p3y) * p3z);
    }

    private static float terrainNoise(float px, float py) {
        float ix = (float) Math.floor(px), iy = (float) Math.floor(py), fx = px - ix, fy = py - iy;
        float ux = fx * fx * (3f - 2f * fx), uy = fy * fy * (3f - 2f * fy);
        return mix(mix(terrainHash(ix, iy), terrainHash(ix + 1, iy), ux), mix(terrainHash(ix, iy + 1), terrainHash(ix + 1, iy + 1), ux), uy);
    }

    private static float terrainFbm(float px, float pz, FractalTerrainParams p) {
        float x = px * p.getTerrainFrequency(), z = pz * p.getTerrainFrequency();
        if (p.getWarpStrength() > 0.001f) {
            x += p.getWarpStrength() * (terrainNoise(x + 5.2f, z + 1.3f) * 2f - 1f);
            z += p.getWarpStrength() * (terrainNoise(x + 1.7f, z + 9.2f) * 2f - 1f);
        }
        float value = 0f, amplitude = 1f, frequency = 1f, totalAmplitude = 0f;
        for (int i = 0; i < p.getOctaves(); i++) {
            float n = terrainNoise(x * frequency, z * frequency);
            value += mix(n, (1f - Math.abs(n * 2f - 1f)) * (1f - Math.abs(n * 2f - 1f)), p.getRidgeSharpness()) * amplitude;
            totalAmplitude += amplitude; frequency *= p.getLacunarity(); amplitude *= p.getRoughness();
        }
        return value / totalAmplitude;
    }

    private static float fractalTerrainDE(float px, float py, float pz, FractalTerrainParams p) {
        return (py - (terrainFbm(px, pz, p) * p.getTerrainHeight() + p.getTerrainOffset())) * 0.4f;
    }

    private static float fractalTerrainDEFull(float px, float py, float pz, FractalTerrainParams p, OrbitTrap trap) {
        float h = terrainFbm(px, pz, p) * p.getTerrainHeight() + p.getTerrainOffset(), d = py - h;
        trap.planeX = Math.abs(fract(px * 0.1f) - 0.5f); trap.planeY = clamp(h / Math.max(p.getTerrainHeight(), 0.01f), 0f, 1f);
        trap.planeZ = Math.abs(fract(pz * 0.1f) - 0.5f); trap.minDist = Math.abs(d); trap.iterations = p.getOctaves();
        return d * 0.4f;
    }

    private static float[] fractalTerrainFactors(OrbitTrap trap, FractalTerrainParams p) {
        return new float[]{trap.planeY, (trap.planeX + trap.planeZ) * 0.5f + trap.planeY * 0.3f, clamp(1f - trap.minDist * 0.5f, 0f, 1f)};
    }
}
