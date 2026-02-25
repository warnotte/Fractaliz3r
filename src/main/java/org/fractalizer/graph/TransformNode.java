package org.fractalizer.graph;

import java.util.List;

/**
 * Transforms the child node's coordinate space.
 * Supports multiple modes: translate/rotate/scale, mirror, twist, repetition.
 */
public class TransformNode extends GraphNode {

    public enum Mode {
        STANDARD("Transform"),
        MIRROR("Mirror"),
        TWIST("Twist"),
        BEND("Bend"),
        TAPER("Taper"),
        REPETITION("Repetition"),
        REPETITION_1D("Repetition 1D");

        private final String displayName;
        Mode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private GraphNode child;
    private Mode mode;
    private float[] offset;      // STANDARD: translation, REPETITION: period
    private float[] rotation;    // STANDARD: euler degrees
    private float scale;         // STANDARD: uniform scale, TWIST/BEND/TAPER: strength
    private int axis;            // MIRROR/TWIST/BEND/TAPER/REP1D: 0=X, 1=Y, 2=Z
    private float frequency;     // TWIST/BEND/TAPER: multiplier

    public TransformNode(GraphNode child, float[] offset, float[] rotation, float scale) {
        this.child = child;
        this.mode = Mode.STANDARD;
        this.offset = offset != null ? offset : new float[]{0, 0, 0};
        this.rotation = rotation != null ? rotation : new float[]{0, 0, 0};
        this.scale = scale;
        this.axis = 1;
        this.frequency = 1.0f;
    }

    public TransformNode(GraphNode child, float[] offset) {
        this(child, offset, new float[]{0, 0, 0}, 1.0f);
    }

    public GraphNode getChild() { return child; }
    public float[] getOffset() { return offset; }
    public float[] getRotation() { return rotation; }
    public float getScale() { return scale; }
    public Mode getMode() { return mode; }
    public int getAxis() { return axis; }
    public float getFrequency() { return frequency; }

    public void setChild(GraphNode child) { this.child = child; }
    public void setScale(float scale) { this.scale = scale; }
    public void setMode(Mode mode) { this.mode = mode; }
    public void setAxis(int axis) { this.axis = Math.max(0, Math.min(2, axis)); }
    public void setFrequency(float frequency) { this.frequency = Math.max(0.01f, Math.min(10f, frequency)); }

    public void setOffset(float[] offset) {
        this.offset = offset != null ? offset : new float[]{0, 0, 0};
    }

    public void setRotation(float[] rotation) {
        this.rotation = rotation != null ? rotation : new float[]{0, 0, 0};
    }

    @Override
    public List<GraphNode> getChildren() {
        return List.of(child);
    }
}
