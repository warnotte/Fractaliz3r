package org.fractalizer.fractals;

import org.fractalizer.engine.OpenCLEngine;

/**
 * Parameters specific to Menger Sponge fractal rendering.
 * The Menger Sponge is a classic fractal based on recursive cube subdivision.
 */
public class MengerSpongeParams extends AbstractFractalParams {

    // ========================================================================
    // Menger Sponge-specific parameters
    // ========================================================================

    // Number of folding iterations (3-10 typical)
    private int maxIterations;

    // Scale factor per iteration (classic is 3.0)
    private float scale;

    // Offset for variation
    private float offsetX;
    private float offsetY;
    private float offsetZ;

    public MengerSpongeParams() {
        super();

        // Menger Sponge-specific defaults
        this.maxIterations = 6;
        this.scale = 3.0f;
        this.offsetX = 1.0f;
        this.offsetY = 1.0f;
        this.offsetZ = 1.0f;

        // Menger Sponge works well with smaller epsilon
        this.epsilon = 0.0002f;

        // Camera position good for Menger
        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.MENGER_SPONGE;
    }

    @Override
    public int setKernelParams(OpenCLEngine engine, String kernelName, int startIndex) {
        int idx = startIndex;

        // Set common camera params
        idx += setCommonKernelParams(engine, kernelName, idx);

        // Menger Sponge-specific parameters
        engine.setKernelArgInt(kernelName, idx++, maxIterations);
        engine.setKernelArgFloat(kernelName, idx++, scale);
        engine.setKernelArgFloats(kernelName, idx++, offsetX, offsetY, offsetZ, 0f);
        engine.setKernelArgInt(kernelName, idx++, maxRaySteps);
        engine.setKernelArgFloat(kernelName, idx++, epsilon);

        // Set lighting and effects params
        idx += setLightingKernelParams(engine, kernelName, idx);

        return idx - startIndex;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MengerSpongeParams reduced = new MengerSpongeParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Menger-specific params
        reduced.scale = this.scale;
        reduced.offsetX = this.offsetX;
        reduced.offsetY = this.offsetY;
        reduced.offsetZ = this.offsetZ;

        // Reduce quality
        reduced.maxIterations = Math.max(3, this.maxIterations - reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Menger Sponge-specific getters and setters
    // ========================================================================

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getOffsetZ() { return offsetZ; }
    public void setOffset(float x, float y, float z) {
        this.offsetX = x;
        this.offsetY = y;
        this.offsetZ = z;
    }

    // Builder-style setters
    public MengerSpongeParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }

    public MengerSpongeParams scale(float scale) {
        this.scale = scale;
        return this;
    }

    public MengerSpongeParams offset(float x, float y, float z) {
        setOffset(x, y, z);
        return this;
    }
}