package org.fractalizer.fractals;

/**
 * Parameters for Menger Sponge Test fractal.
 * Menger variant with Z-shift and center Z correction.
 */
public class MengerSpongeTestParams extends AbstractFractalParams {

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

    @Animatable(display = "Z Shift")
    private float zShift;

    @Animatable(display = "Center Z")
    private float centerZ;

    public MengerSpongeTestParams() {
        super();
        this.maxIterations = 6;
        this.scale = 3.0f;
        this.offset = 1.0f;
        this.rotX = 0.0f;
        this.rotZ = 0.0f;
        this.zShift = 0.0f;
        this.centerZ = 1.0f;
        this.epsilon = 0.0002f;
        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.MENGER_SPONGE_TEST;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        MengerSpongeTestParams reduced = new MengerSpongeTestParams();
        copyCommonParams(reduced);
        reduced.scale = this.scale;
        reduced.offset = this.offset;
        reduced.rotX = this.rotX;
        reduced.rotZ = this.rotZ;
        reduced.zShift = this.zShift;
        reduced.centerZ = this.centerZ;
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

    public float getZShift() { return zShift; }
    public void setZShift(float zShift) { this.zShift = zShift; }

    public float getCenterZ() { return centerZ; }
    public void setCenterZ(float centerZ) { this.centerZ = centerZ; }
}
