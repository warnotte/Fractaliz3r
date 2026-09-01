package org.fractalizer.fractals;

import org.fractalizer.graph.FractalNode;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.GraphNodeNamer;

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
    private float[] materialSSBOData;
    private boolean dirty = true;

    public NodeGraphParams() {
        super();
        this.graphRoot = new FractalNode(FractalType.MANDELBULB);
        GraphNodeNamer.ensureAllNamed(graphRoot);
        getCamera().setPosition(0, 0, -3.5f);
    }

    /**
     * Create a NodeGraphParams wrapping a single FractalNode of the given type.
     * Used when migrated fractal types are routed through the node graph pipeline.
     */
    public NodeGraphParams(FractalType initialType) {
        super();
        this.graphRoot = new FractalNode(initialType);
        GraphNodeNamer.ensureAllNamed(graphRoot);
        // Copy the camera position from the fractal type's default params
        AbstractFractalParams defaultParams = FractalNode.createDefaultParams(initialType);
        if (defaultParams != null) {
            float[] pos = defaultParams.getCamera().getPosition();
            getCamera().setPosition(pos[0], pos[1], pos[2]);
        }
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
        reduced.materialSSBOData = this.materialSSBOData;
        reduced.dirty = false;
        applyReducedQuality(reduced, reductionFactor);
        return reduced;
    }

    public GraphNode getGraphRoot() {
        return graphRoot;
    }

    public void setGraphRoot(GraphNode graphRoot) {
        this.graphRoot = graphRoot;
        if (graphRoot != null) GraphNodeNamer.ensureAllNamed(graphRoot);
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Toggling the deep-zoom LOD on or off changes a compile-time define, so the
     *  program has to be rebuilt — a plain uniform update would not take effect. */
    @Override
    public void setDetailLOD(float v) {
        boolean wasEnabled = detailLOD > 0f;
        super.setDetailLOD(v);
        if ((detailLOD > 0f) != wasEnabled) markDirty();
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
            materialSSBOData = GraphCompiler.collectMaterialSSBOData(graphRoot);
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
        materialSSBOData = GraphCompiler.collectMaterialSSBOData(graphRoot);
    }

    public float[] getMaterialSSBOData() {
        return materialSSBOData;
    }

    /**
     * Get the fractal params of the root node (if it's a FractalNode).
     * Returns null if the root is a CSG/Transform node or null.
     */
    public AbstractFractalParams getRootFractalParams() {
        if (graphRoot instanceof FractalNode fn) return fn.getFractalParams();
        return null;
    }

    /**
     * Get the fractal type of the root node (if it's a FractalNode).
     * Returns NODE_GRAPH if the root is not a simple FractalNode.
     */
    public FractalType getRootFractalType() {
        if (graphRoot instanceof FractalNode fn) return fn.getFractalType();
        return FractalType.NODE_GRAPH;
    }
}
