package org.fractalizer.fractals;

import org.fractalizer.engine.OpenCLEngine;

/**
 * Parameters specific to Mandelbulb fractal rendering.
 * Inherits common rendering parameters from AbstractFractalParams.
 */
public class MandelbulbParams extends AbstractFractalParams {

    // ========================================================================
    // Mandelbulb-specific parameters
    // ========================================================================

    // Mandelbulb power (classic is 8)
    private float power;

    // Fractal iteration parameters
    private int maxIterations;
    private float bailout;

    public MandelbulbParams() {
        super();

        // Mandelbulb-specific defaults
        this.power = 8f;
        this.maxIterations = 15;
        this.bailout = 2f;
    }

    @Override
    public FractalType getType() {
        return FractalType.MANDELBULB;
    }

    @Override
    public int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex) {
        int idx = startIndex;

        // Set common camera params
        idx += setCommonKernelParams(engine, kernelName, idx);

        // Mandelbulb-specific parameters
        engine.setKernelArgFloat(kernelName, idx++, power);
        engine.setKernelArgInt(kernelName, idx++, maxIterations);
        engine.setKernelArgInt(kernelName, idx++, maxRaySteps);
        engine.setKernelArgFloat(kernelName, idx++, bailout);
        engine.setKernelArgFloat(kernelName, idx++, epsilon);

        // Set lighting and effects params
        idx += setLightingKernelParams(engine, kernelName, idx);

        return idx - startIndex;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelbulbParams reduced = new MandelbulbParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Mandelbulb-specific params
        reduced.power = this.power;
        reduced.bailout = this.bailout;

        // Reduce quality
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Mandelbulb-specific getters and setters
    // ========================================================================

    public float getPower() { return power; }
    public void setPower(float power) { this.power = power; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getBailout() { return bailout; }
    public void setBailout(float bailout) { this.bailout = bailout; }

    // Builder-style setters
    public MandelbulbParams power(float power) {
        this.power = power;
        return this;
    }

    public MandelbulbParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public MandelbulbParams bailout(float bailout) {
        this.bailout = bailout;
        return this;
    }
}
