package org.fractalizer.graph;

import java.util.List;

/**
 * Abstract base for all nodes in a fractal DE composition graph.
 * IDs are assigned during compilation by {@link GraphCompiler}.
 */
public abstract class GraphNode {

    String id;
    private String name;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public abstract List<GraphNode> getChildren();
}
