package org.fractalizer.fractals;

/**
 * Parameters specific to Mandelbox fractal rendering.
 * The Mandelbox is a box-like fractal with folding and spherical inversion.
 */
public class MandelboxParams extends AbstractFractalParams {

    // ========================================================================
    // Mandelbox-specific parameters
    // ========================================================================

    // Scale factor (-3 to 3, classic is -1.5 or 2.0)
    @Animatable(display = "Scale")
    private float scale;

    // Sphere radii for spherical folding
    @Animatable(display = "Min Radius")
    private float minRadius;      // Inner sphere (typically 0.25-0.5)

    @Animatable(display = "Fixed Radius")
    private float fixedRadius;    // Outer sphere (typically 1.0)

    // Folding limit for box fold
    @Animatable(display = "Folding Limit")
    private float foldingLimit;   // Typically 1.0

    // Fractal iteration count
    @Animatable(display = "Iterations")
    private int maxIterations;

    public MandelboxParams() {
        super();

        // Mandelbox-specific defaults
        this.scale = 2.0f;
        this.minRadius = 0.25f;
        this.fixedRadius = 1.0f;
        this.foldingLimit = 1.0f;
        this.maxIterations = 15;

        // Adjust some common defaults for Mandelbox
        this.epsilon = 0.0005f;  // Mandelbox needs slightly larger epsilon
    }

    @Override
    public FractalType getType() {
        return FractalType.MANDELBOX;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MandelboxParams reduced = new MandelboxParams();

        // Copy common params
        copyCommonParams(reduced);

        // Copy Mandelbox-specific params
        reduced.scale = this.scale;
        reduced.minRadius = this.minRadius;
        reduced.fixedRadius = this.fixedRadius;
        reduced.foldingLimit = this.foldingLimit;

        // Reduce quality
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);

        return reduced;
    }

    // ========================================================================
    // Mandelbox-specific getters and setters
    // ========================================================================

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getMinRadius() { return minRadius; }
    public void setMinRadius(float radius) { this.minRadius = radius; }

    public float getFixedRadius() { return fixedRadius; }
    public void setFixedRadius(float radius) { this.fixedRadius = radius; }

    public float getFoldingLimit() { return foldingLimit; }
    public void setFoldingLimit(float limit) { this.foldingLimit = limit; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    // Builder-style setters
    public MandelboxParams scale(float scale) {
        this.scale = scale;
        return this;
    }

    public MandelboxParams minRadius(float radius) {
        this.minRadius = radius;
        return this;
    }

    public MandelboxParams fixedRadius(float radius) {
        this.fixedRadius = radius;
        return this;
    }

    public MandelboxParams foldingLimit(float limit) {
        this.foldingLimit = limit;
        return this;
    }

    public MandelboxParams iterations(int max) {
        this.maxIterations = max;
        return this;
    }
}