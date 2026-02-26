package org.fractalizer.fractals;

/**
 * Parameters for Menger Advanced fractal.
 * Hybrid Menger with inner box fold and Z-scale stretch.
 */
public class MengerAdvancedParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Scale")
    private float scale;

    @Animatable(display = "Offset")
    private float offset;

    @Animatable(display = "Rotation X")
    private float rotX;

    @Animatable(display = "Rotation Z")
    private float rotZ;

    @Animatable(display = "Inner Fold")
    private float innerFold;

    @Animatable(display = "Z Scale")
    private float zScale;

    public MengerAdvancedParams() {
        super();
        this.maxIterations = 6;
        this.scale = 3.0f;
        this.offset = 1.0f;
        this.rotX = 0.0f;
        this.rotZ = 0.0f;
        this.innerFold = 0.5f;
        this.zScale = 1.5f;
        this.epsilon = 0.0002f;
        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.MENGER_ADVANCED;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MengerAdvancedParams reduced = new MengerAdvancedParams();
        copyCommonParams(reduced);
        reduced.scale = this.scale;
        reduced.offset = this.offset;
        reduced.rotX = this.rotX;
        reduced.rotZ = this.rotZ;
        reduced.innerFold = this.innerFold;
        reduced.zScale = this.zScale;
        reduced.maxIterations = Math.max(3, this.maxIterations - reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getOffset() { return offset; }
    public void setOffset(float offset) { this.offset = offset; }

    public float getRotX() { return rotX; }
    public void setRotX(float rotX) { this.rotX = rotX; }

    public float getRotZ() { return rotZ; }
    public void setRotZ(float rotZ) { this.rotZ = rotZ; }

    public float getInnerFold() { return innerFold; }
    public void setInnerFold(float innerFold) { this.innerFold = innerFold; }

    public float getZScale() { return zScale; }
    public void setZScale(float zScale) { this.zScale = zScale; }
}
