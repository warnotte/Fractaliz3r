package org.fractalizer.fractals;

/**
 * Parameters specific to Koch Quadratic Surface (Type 1) fractal rendering.
 * A surface fractal: flat base plane with boxes stacked in a cross pattern
 * (center + 4 edges of a 3x3 grid), applied recursively upward.
 */
public class KochSurfaceParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Scale")
    private float scale;

    private BasePrimitive basePrimitive = BasePrimitive.BOX;

    public KochSurfaceParams() {
        super();
        this.maxIterations = 8;
        this.scale = 3.0f;

        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.KOCH_SURFACE;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        KochSurfaceParams reduced = new KochSurfaceParams();
        copyCommonParams(reduced);
        reduced.scale = this.scale;
        reduced.basePrimitive = this.basePrimitive;
        reduced.maxIterations = Math.max(3, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public BasePrimitive getBasePrimitive() { return basePrimitive; }
    public void setBasePrimitive(BasePrimitive basePrimitive) { this.basePrimitive = basePrimitive; }
}
