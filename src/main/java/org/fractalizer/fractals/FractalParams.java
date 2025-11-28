package org.fractalizer.fractals;

import org.fractalizer.engine.OpenCLEngine;

/**
 * Base interface for fractal parameters.
 * Each fractal type implements this to provide its specific parameters.
 */
public interface FractalParams {

    /**
     * Set kernel parameters starting at the given index.
     * @return The number of parameters set
     */
    int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex);

    /**
     * Create a reduced quality version for preview rendering.
     */
    FractalParams withReducedQuality(int reductionFactor);

    /**
     * Get the fractal type name.
     */
    String getFractalType();
}