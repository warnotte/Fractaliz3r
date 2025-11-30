package org.fractalizer.fractals;

/**
 * Base interface for fractal parameters.
 * Each fractal type implements this to provide its specific parameters.
 */
public interface FractalParams {

    /**
     * Create a reduced quality version for preview rendering.
     */
    FractalParams withReducedQuality(int reductionFactor);

    /**
     * Get the fractal type name.
     */
    String getFractalType();
}