package org.fractalizer.explore;

import java.awt.image.BufferedImage;

/**
 * What the explorer needs from a renderer: a depth map and a colour image of the
 * scene from a given camera. The app implements it over the GPU controller; the unit
 * test implements it with an analytic sphere, which is how the exploration logic is
 * checked without a GPU.
 *
 * Depth values follow the engine's depth AOV: {@code 1 - clamp(log(d + 0.1) / log(15), 0, 1)},
 * so 0 (or anything at or below {@link FrameScorer#BACKGROUND}) is a miss and values at
 * or above {@link #SATURATED} mean the surface is closer than about 0.9 units.
 */
public interface ViewRenderer {

    /** Depth at or above this is "closer than the encoding can tell", about 0.9 units. */
    float SATURATED = 0.999f;

    int width();

    int height();

    /** Depth map from {@code eye} looking at {@code target}, row-major {@code width * height}. */
    float[] depth(float[] eye, float[] target, float fovDeg);

    /** Colour render from {@code eye} looking at {@code target}; null if cancelled. */
    BufferedImage colour(float[] eye, float[] target, float fovDeg, int samples);

    /** Invert the depth AOV encoding to a world distance. NaN when saturated or a miss. */
    static double decode(float v) {
        if (v >= SATURATED || v <= FrameScorer.BACKGROUND) return Double.NaN;
        return Math.pow(15.0, 1.0 - v) - 0.1;
    }
}
