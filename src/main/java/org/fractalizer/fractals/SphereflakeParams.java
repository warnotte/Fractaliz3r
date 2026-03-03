package org.fractalizer.fractals;

/**
 * Parameters specific to Sphereflake fractal rendering.
 * Recursive 3D structure using octahedral symmetry folding where smaller
 * shapes are placed on the surface of a parent shape.
 */
public class SphereflakeParams extends AbstractFractalParams {

    @Animatable(display = "Iterations")
    private int maxIterations;

    @Animatable(display = "Child Scale")
    private float childScale;

    @Animatable(display = "Spacing")
    private float spacing;

    @Animatable(display = "Rotation X")
    private float rotAngleX;

    @Animatable(display = "Rotation Y")
    private float rotAngleY;

    @Animatable(display = "Rotation Z")
    private float rotAngleZ;

    @Animatable(display = "Offset Y")
    private float offsetY;

    @Animatable(display = "Offset Z")
    private float offsetZ;

    private BasePrimitive basePrimitive;

    public SphereflakeParams() {
        super();
        this.maxIterations = 8;
        this.childScale = 0.333f;
        this.spacing = 1.0f;
        this.rotAngleX = 0.0f;
        this.rotAngleY = 0.0f;
        this.rotAngleZ = 0.0f;
        this.offsetY = 0.0f;
        this.offsetZ = 0.0f;
        this.basePrimitive = BasePrimitive.SPHERE;

        camera.setPosition(0f, 0f, -4f);
    }

    @Override
    public FractalType getType() {
        return FractalType.SPHEREFLAKE;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        SphereflakeParams reduced = new SphereflakeParams();
        copyCommonParams(reduced);
        reduced.childScale = this.childScale;
        reduced.spacing = this.spacing;
        reduced.rotAngleX = this.rotAngleX;
        reduced.rotAngleY = this.rotAngleY;
        reduced.rotAngleZ = this.rotAngleZ;
        reduced.offsetY = this.offsetY;
        reduced.offsetZ = this.offsetZ;
        reduced.basePrimitive = this.basePrimitive;
        reduced.maxIterations = Math.max(3, this.maxIterations / reductionFactor);
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int iterations) { this.maxIterations = iterations; }

    public float getChildScale() { return childScale; }
    public void setChildScale(float childScale) { this.childScale = childScale; }

    public float getSpacing() { return spacing; }
    public void setSpacing(float spacing) { this.spacing = spacing; }

    public float getRotAngleX() { return rotAngleX; }
    public void setRotAngleX(float rotAngleX) { this.rotAngleX = rotAngleX; }

    public float getRotAngleY() { return rotAngleY; }
    public void setRotAngleY(float rotAngleY) { this.rotAngleY = rotAngleY; }

    public float getRotAngleZ() { return rotAngleZ; }
    public void setRotAngleZ(float rotAngleZ) { this.rotAngleZ = rotAngleZ; }

    public float getOffsetY() { return offsetY; }
    public void setOffsetY(float offsetY) { this.offsetY = offsetY; }

    public float getOffsetZ() { return offsetZ; }
    public void setOffsetZ(float offsetZ) { this.offsetZ = offsetZ; }

    public BasePrimitive getBasePrimitive() { return basePrimitive; }
    public void setBasePrimitive(BasePrimitive basePrimitive) { this.basePrimitive = basePrimitive; }

    // Backwards compatibility for old configs that use "rotAngle"
    public float getRotAngle() { return rotAngleX; }
    public void setRotAngle(float rotAngle) { this.rotAngleX = rotAngle; }
}
