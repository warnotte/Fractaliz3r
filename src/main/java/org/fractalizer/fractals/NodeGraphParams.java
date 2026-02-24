package org.fractalizer.fractals;

import org.fractalizer.graph.FractalNode;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameters for the Node Graph fractal type.
 * Stores a tree of GraphNodes that gets compiled into composite GLSL.
 */
public class NodeGraphParams extends AbstractFractalParams {

    private GraphNode graphRoot;
    private String compiledGLSL;
    private Map<String, Object> uniformValues = new LinkedHashMap<>();
    private boolean dirty = true;

    public NodeGraphParams() {
        super();
        this.graphRoot = new FractalNode(FractalType.MANDELBULB);
        getCamera().setPosition(0, 0, -3.5f);
    }

    @Override
    public FractalType getType() {
        return FractalType.NODE_GRAPH;
    }

    @Override
    public FractalParams withReducedQuality(int reductionFactor) {
        NodeGraphParams reduced = new NodeGraphParams();
        copyCommonParams(reduced);
        reduced.graphRoot = this.graphRoot;
        reduced.compiledGLSL = this.compiledGLSL;
        reduced.uniformValues = new LinkedHashMap<>(this.uniformValues);
        reduced.dirty = false;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public GraphNode getGraphRoot() {
        return graphRoot;
    }

    public void setGraphRoot(GraphNode graphRoot) {
        this.graphRoot = graphRoot;
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Recompile the graph into GLSL and collect uniforms.
     * Returns the compiled GLSL source, or null if compilation fails.
     */
    public String recompile() {
        if (graphRoot == null) return null;
        try {
            GraphCompiler compiler = new GraphCompiler();
            compiledGLSL = compiler.compile(graphRoot);
            uniformValues = compiler.getUniforms(graphRoot);
            dirty = false;
            return compiledGLSL;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getCompiledGLSL() {
        return compiledGLSL;
    }

    public Map<String, Object> getUniformValues() {
        return uniformValues;
    }

    /**
     * Refresh uniform values from the current graph tree without recompiling GLSL.
     * Uses already-assigned node IDs from the last compile() call.
     */
    public void updateUniforms() {
        if (graphRoot == null || compiledGLSL == null) return;
        uniformValues = GraphCompiler.collectUniformsStatic(graphRoot);
    }
}
