package org.fractalizer.graph;

import java.util.List;

/**
 * Unary node that assigns per-node material properties to its child subtree.
 * Properties use sentinel value -1 to fall back to global material uniforms.
 * matColor is a multiplicative tint (1,1,1 = palette color only).
 */
public class MaterialNode extends GraphNode {

    /** -1 = use global, 0 = Lambertian, 1 = Metallic, 2 = Glass */
    public static final int TYPE_GLOBAL = -1;
    public static final int TYPE_LAMBERTIAN = 0;
    public static final int TYPE_METALLIC = 1;
    public static final int TYPE_GLASS = 2;

    /** Color mode: how matColor interacts with fractal palette colors */
    public static final int COLOR_PALETTE = 0;  // Keep fractal palette colors (default)
    public static final int COLOR_SOLID = 1;    // Replace with solid albedo color
    public static final int COLOR_TINT = 2;     // Multiply palette by tint color

    private GraphNode child;
    private int materialType;
    private int colorMode;
    private float colorR;
    private float colorG;
    private float colorB;
    private float roughness;
    private float metallic;
    private float ior;
    private float emission;

    public MaterialNode(GraphNode child) {
        this.child = child;
        applyDefaults();
    }

    private void applyDefaults() {
        materialType = TYPE_GLOBAL;
        colorMode = COLOR_PALETTE;
        colorR = 1.0f;
        colorG = 1.0f;
        colorB = 1.0f;
        roughness = -1.0f;
        metallic = -1.0f;
        ior = -1.0f;
        emission = -1.0f;
    }

    public GraphNode getChild() { return child; }
    public void setChild(GraphNode child) { this.child = child; }

    public int getMaterialType() { return materialType; }
    public void setMaterialType(int materialType) { this.materialType = Math.max(-1, Math.min(2, materialType)); }

    public int getColorMode() { return colorMode; }
    public void setColorMode(int colorMode) { this.colorMode = Math.max(0, Math.min(2, colorMode)); }

    public float getColorR() { return colorR; }
    public void setColorR(float colorR) { this.colorR = Math.max(0f, Math.min(1f, colorR)); }

    public float getColorG() { return colorG; }
    public void setColorG(float colorG) { this.colorG = Math.max(0f, Math.min(1f, colorG)); }

    public float getColorB() { return colorB; }
    public void setColorB(float colorB) { this.colorB = Math.max(0f, Math.min(1f, colorB)); }

    public float getRoughness() { return roughness; }
    public void setRoughness(float roughness) { this.roughness = Math.max(-1f, Math.min(1f, roughness)); }

    public float getMetallic() { return metallic; }
    public void setMetallic(float metallic) { this.metallic = Math.max(-1f, Math.min(1f, metallic)); }

    public float getIor() { return ior; }
    public void setIor(float ior) { this.ior = Math.max(-1f, Math.min(3f, ior)); }

    public float getEmission() { return emission; }
    public void setEmission(float emission) { this.emission = Math.max(-1f, Math.min(50f, emission)); }

    @Override
    public List<GraphNode> getChildren() {
        return List.of(child);
    }
}
