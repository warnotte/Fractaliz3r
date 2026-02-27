package org.fractalizer.graph;

import java.util.Collections;
import java.util.List;

/**
 * Leaf node representing an SDF geometric primitive (Sphere, Box, Torus, etc.).
 * Unlike FractalNode which loads .glsl files, PrimitiveNode generates inline GLSL
 * in GraphCompiler — simple 1-5 line analytic distance functions.
 */
public class PrimitiveNode extends GraphNode {

    public enum PrimitiveType {
        SPHERE("Sphere"),
        BOX("Box"),
        ROUNDED_BOX("Rounded Box"),
        PLANE("Plane"),
        TORUS("Torus"),
        CYLINDER("Cylinder"),
        CAPSULE("Capsule"),
        CONE("Cone"),
        OCTAHEDRON("Octahedron"),
        PYRAMID("Pyramid"),
        HEX_PRISM("Hex Prism");

        private final String displayName;
        PrimitiveType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private PrimitiveType primitiveType;
    private float sizeX = 1.0f;
    private float sizeY = 1.0f;
    private float sizeZ = 1.0f;
    private float rounding = 0.0f;
    private float shell = 0.0f;

    public PrimitiveNode(PrimitiveType primitiveType) {
        this.primitiveType = primitiveType;
    }

    public PrimitiveType getPrimitiveType() { return primitiveType; }
    public void setPrimitiveType(PrimitiveType primitiveType) { this.primitiveType = primitiveType; }

    public float getSizeX() { return sizeX; }
    public void setSizeX(float sizeX) { this.sizeX = Math.max(0.01f, Math.min(10f, sizeX)); }

    public float getSizeY() { return sizeY; }
    public void setSizeY(float sizeY) { this.sizeY = Math.max(0.01f, Math.min(10f, sizeY)); }

    public float getSizeZ() { return sizeZ; }
    public void setSizeZ(float sizeZ) { this.sizeZ = Math.max(0.01f, Math.min(10f, sizeZ)); }

    public float getRounding() { return rounding; }
    public void setRounding(float rounding) { this.rounding = Math.max(0f, Math.min(1f, rounding)); }

    public float getShell() { return shell; }
    public void setShell(float shell) { this.shell = Math.max(0f, Math.min(0.5f, shell)); }

    @Override
    public List<GraphNode> getChildren() {
        return Collections.emptyList();
    }

    // --- Static helpers for UI and animation: which sizes are used per type ---

    public static boolean usesSizeY(PrimitiveType type) {
        return switch (type) {
            case SPHERE, OCTAHEDRON, PYRAMID, PLANE -> false;
            default -> true;
        };
    }

    public static boolean usesSizeZ(PrimitiveType type) {
        return switch (type) {
            case BOX, ROUNDED_BOX, CONE -> true;
            default -> false;
        };
    }

    public static String getSizeXLabel(PrimitiveType type) {
        return switch (type) {
            case SPHERE, OCTAHEDRON -> "Radius";
            case BOX, ROUNDED_BOX -> "Width";
            case PLANE -> "Height Offset";
            case TORUS -> "Major Radius";
            case CYLINDER, CAPSULE, HEX_PRISM -> "Radius";
            case CONE -> "Height";
            case PYRAMID -> "Height";
        };
    }

    public static String getSizeYLabel(PrimitiveType type) {
        return switch (type) {
            case BOX, ROUNDED_BOX -> "Height";
            case TORUS -> "Minor Radius";
            case CYLINDER, CAPSULE, HEX_PRISM -> "Half Height";
            case CONE -> "Bottom Radius";
            default -> "Size Y";
        };
    }

    public static String getSizeZLabel(PrimitiveType type) {
        return switch (type) {
            case BOX, ROUNDED_BOX -> "Depth";
            case CONE -> "Top Radius";
            default -> "Size Z";
        };
    }
}
