package org.fractalizer.graph;

import java.util.List;

/**
 * Abstract base for all nodes in a fractal DE composition graph.
 * IDs are assigned during compilation by {@link GraphCompiler}.
 */
public abstract class GraphNode {

    String id;

    public String getId() {
        return id;
    }

    public abstract List<GraphNode> getChildren();
}
