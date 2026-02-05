package org.fractalizer.fractals;

/**
 * Parameters for Polyhedral IFS (Iterated Function Systems).
 * Supports Octahedral, Dodecahedron, Icosahedron, and Tetrahedron symmetries.
 * Based on formulas from the legacy fract.frag shader.
 */
public class PolyhedralIFSParams extends AbstractFractalParams {

    public enum PolyType {
        OCTAHEDRAL("Octahedral"),
        DODECAHEDRON("Dodecahedron"),
        ICOSAHEDRON("Icosahedron"),
        TETRAHEDRON("Tetrahedron");

        private final String name;
        PolyType(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private PolyType polyType = PolyType.OCTAHEDRAL;
    private int maxIterations = 15;
    private float scale = 2.0f;
    
    // Translation offsets
    private float offsetX = 1.0f;
    private float offsetY = 1.0f;
    private float offsetZ = 1.0f;
    
    // Fold shifts
    private float shiftX = 0.0f;
    private float shiftY = 0.0f;
    private float shiftZ = 0.0f;

    // Rotation angles (Euler) for fractal rotations 1 and 2
    private float rot1X = 0, rot1Y = 0, rot1Z = 0;
    private float rot2X = 0, rot2Y = 0, rot2Z = 0;

    public PolyhedralIFSParams() {
        super();
        this.epsilon = 0.0001f;
        getCamera().setPosition(0, 0, -4);
    }

    @Override
    public FractalType getType() {
        return FractalType.POLYHEDRAL_IFS;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        PolyhedralIFSParams reduced = new PolyhedralIFSParams();
        copyCommonParams(reduced);
        reduced.polyType = this.polyType;
        reduced.scale = this.scale;
        reduced.maxIterations = Math.max(5, this.maxIterations / reductionFactor);
        reduced.offsetX = this.offsetX;
        reduced.offsetY = this.offsetY;
        reduced.offsetZ = this.offsetZ;
        reduced.shiftX = this.shiftX;
        reduced.shiftY = this.shiftY;
        reduced.shiftZ = this.shiftZ;
        reduced.rot1X = this.rot1X; reduced.rot1Y = this.rot1Y; reduced.rot1Z = this.rot1Z;
        reduced.rot2X = this.rot2X; reduced.rot2Y = this.rot2Y; reduced.rot2Z = this.rot2Z;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    // Getters and Setters
    public PolyType getPolyType() { return polyType; }
    public void setPolyType(PolyType polyType) { this.polyType = polyType; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public float getOffsetX() { return offsetX; }
    public void setOffsetX(float offsetX) { this.offsetX = offsetX; }
    public float getOffsetY() { return offsetY; }
    public void setOffsetY(float offsetY) { this.offsetY = offsetY; }
    public float getOffsetZ() { return offsetZ; }
    public void setOffsetZ(float offsetZ) { this.offsetZ = offsetZ; }

    public float getShiftX() { return shiftX; }
    public void setShiftX(float shiftX) { this.shiftX = shiftX; }
    public float getShiftY() { return shiftY; }
    public void setShiftY(float shiftY) { this.shiftY = shiftY; }
    public float getShiftZ() { return shiftZ; }
    public void setShiftZ(float shiftZ) { this.shiftZ = shiftZ; }

    public float getRot1X() { return rot1X; }
    public void setRot1X(float rot1X) { this.rot1X = rot1X; }
    public float getRot1Y() { return rot1Y; }
    public void setRot1Y(float rot1Y) { this.rot1Y = rot1Y; }
    public float getRot1Z() { return rot1Z; }
    public void setRot1Z(float rot1Z) { this.rot1Z = rot1Z; }

    public float getRot2X() { return rot2X; }
    public void setRot2X(float rot2X) { this.rot2X = rot2X; }
    public float getRot2Y() { return rot2Y; }
    public void setRot2Y(float rot2Y) { this.rot2Y = rot2Y; }
    public float getRot2Z() { return rot2Z; }
    public void setRot2Z(float rot2Z) { this.rot2Z = rot2Z; }
}
