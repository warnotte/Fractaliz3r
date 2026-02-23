package org.fractalizer.graph;

import org.fractalizer.fractals.FractalType;
import java.util.Collections;
import java.util.List;

/**
 * Leaf node that wraps a built-in fractal type.
 * Uses default parameters for uniforms (same pattern as boolean ops secondary).
 */
public class FractalNode extends GraphNode {

    private final FractalType fractalType;

    public FractalNode(FractalType fractalType) {
        this.fractalType = fractalType;
    }

    public FractalType getFractalType() {
        return fractalType;
    }

    @Override
    public List<GraphNode> getChildren() {
        return Collections.emptyList();
    }
}
