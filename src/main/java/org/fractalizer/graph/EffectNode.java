package org.fractalizer.graph;

import java.util.List;

/**
 * Unary node that applies a surface effect (erosion, crystallization, moss)
 * to the child node's distance field. Per-node alternative to the global
 * effects in QualityPanel.
 */
public class EffectNode extends GraphNode {

    public enum EffectType {
        EROSION("Erosion"),
        CRYSTAL("Crystal"),
        MOSS("Moss");

        private final String displayName;
        EffectType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private GraphNode child;
    private EffectType effectType;
    private float strength;
    private float time;
    private float scale;
    private int erosionType;    // EROSION only: 0=All, 1=Hydraulic, 2=Thermal, 3=Cracks
    private float sharpness;    // CRYSTAL only

    public EffectNode(GraphNode child, EffectType effectType) {
        this.child = child;
        this.effectType = effectType;
        applyDefaults();
    }

    private void applyDefaults() {
        strength = 0.5f;
        time = 3.0f;
        scale = 1.0f;
        erosionType = 0;
        sharpness = 2.0f;
    }

    public GraphNode getChild() { return child; }
    public void setChild(GraphNode child) { this.child = child; }

    public EffectType getEffectType() { return effectType; }
    public void setEffectType(EffectType effectType) { this.effectType = effectType; }

    public float getStrength() { return strength; }
    public void setStrength(float strength) { this.strength = Math.max(0f, Math.min(1f, strength)); }

    public float getTime() { return time; }
    public void setTime(float time) { this.time = Math.max(0f, Math.min(20f, time)); }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = Math.max(0.1f, Math.min(5f, scale)); }

    public int getErosionType() { return erosionType; }
    public void setErosionType(int erosionType) { this.erosionType = Math.max(0, Math.min(3, erosionType)); }

    public float getSharpness() { return sharpness; }
    public void setSharpness(float sharpness) { this.sharpness = Math.max(0.5f, Math.min(5f, sharpness)); }

    @Override
    public List<GraphNode> getChildren() {
        return List.of(child);
    }
}
