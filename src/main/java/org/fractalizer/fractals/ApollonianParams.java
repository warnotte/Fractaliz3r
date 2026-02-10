package org.fractalizer.fractals;

/**
 * Parameters specific to Apollonian Gasket fractal rendering.
 * Tetrahedral folds combined with sphere inversion to produce
 * characteristic sphere-packing geometry.
 */
public class ApollonianParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Scale")
    private float scale;

    @Animatable(display = "Fold Radius")
    private float foldRadius;

    public ApollonianParams() {
        super();
        this.maxIterations = 15;
        this.scale = 2.0f;
        this.foldRadius = 1.0f;

        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.APOLLONIAN;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        ApollonianParams reduced = new ApollonianParams();
        copyCommonParams(reduced);
        reduced.scale = this.scale;
        reduced.foldRadius = this.foldRadius;
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getFoldRadius() { return foldRadius; }
    public void setFoldRadius(float radius) { this.foldRadius = radius; }
}
