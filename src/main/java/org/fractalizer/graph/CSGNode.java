package org.fractalizer.graph;

import java.util.List;

/**
 * Boolean-combines two child distance fields (union, intersect, subtract).
 * Smooth blending when {@code blend > 0}.
 */
public class CSGNode extends GraphNode {

    public enum Op {
        UNION, INTERSECT, SUBTRACT
    }

    private final GraphNode left;
    private final GraphNode right;
    private final Op op;
    private float blend;

    public CSGNode(Op op, GraphNode left, GraphNode right, float blend) {
        this.op = op;
        this.left = left;
        this.right = right;
        this.blend = blend;
    }

    public CSGNode(Op op, GraphNode left, GraphNode right) {
        this(op, left, right, 0.0f);
    }

    public GraphNode getLeft() {
        return left;
    }

    public GraphNode getRight() {
        return right;
    }

    public Op getOp() {
        return op;
    }

    public float getBlend() {
        return blend;
    }

    @Override
    public List<GraphNode> getChildren() {
        return List.of(left, right);
    }
}
