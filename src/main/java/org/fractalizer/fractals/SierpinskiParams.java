package org.fractalizer.fractals;

/**
 * Parameters specific to Sierpinski Tetrahedron fractal rendering.
 * A simple IFS fractal using tetrahedral folds and uniform scaling.
 */
public class SierpinskiParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Scale")
    private float scale;

    private BasePrimitive basePrimitive = BasePrimitive.SPHERE;

    public SierpinskiParams() {
        super();
        this.maxIterations = 15;
        this.scale = 2.0f;

        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.SIERPINSKI;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        SierpinskiParams reduced = new SierpinskiParams();
        copyCommonParams(reduced);
        reduced.scale = this.scale;
        reduced.basePrimitive = this.basePrimitive;
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
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
