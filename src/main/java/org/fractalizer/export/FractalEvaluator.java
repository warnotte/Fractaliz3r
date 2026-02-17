package org.fractalizer.export;

import javafx.scene.paint.Color;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.GradientPalette;

/**
 * CPU-side vertex coloring for 3D mesh export.
 * Converts orbit trap factors (from GPU slices) into vertex colors using the active palette.
 * All distance estimation and factor computation is done GPU-side via evaluator.glsl.
 */
public class FractalEvaluator {

    private static float clamp(float x, float a, float b) {
        return Math.max(a, Math.min(b, x));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
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

    /** Compute vertex color from GPU-provided factors using the active palette/gradient. */
    public static Color computeColor(AbstractFractalParams params, float[] factors) {
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
}
