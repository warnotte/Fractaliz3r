package org.fractalizer.graph;

import java.util.List;

/**
 * Transforms (translate/rotate/scale) the child node's coordinate space.
 * Generates {@code applyTransform_{id}(vec3 pos)} in GLSL.
 * DE result is multiplied by {@code scale} to correct the distance estimate.
 */
public class TransformNode extends GraphNode {

    private final GraphNode child;
    private float[] offset;
    private float[] rotation; // euler degrees
    private float scale;

    public TransformNode(GraphNode child, float[] offset, float[] rotation, float scale) {
        this.child = child;
        this.offset = offset != null ? offset : new float[]{0, 0, 0};
        this.rotation = rotation != null ? rotation : new float[]{0, 0, 0};
        this.scale = scale;
    }

    public TransformNode(GraphNode child, float[] offset) {
        this(child, offset, new float[]{0, 0, 0}, 1.0f);
    }

    public GraphNode getChild() {
        return child;
    }

    public float[] getOffset() {
        return offset;
    }

    public float[] getRotation() {
        return rotation;
    }

    public float getScale() {
        return scale;
    }

    @Override
    public List<GraphNode> getChildren() {
        return List.of(child);
    }
}
