package org.fractalizer.graph;

import org.fractalizer.fractals.*;

import java.util.Collections;
import java.util.List;

/**
 * Leaf node that wraps a built-in fractal type.
 * Stores per-node fractal parameters (power, iterations, etc.)
 * so each node in the graph can be individually tuned.
 */
public class FractalNode extends GraphNode {

    private FractalType fractalType;
    private AbstractFractalParams fractalParams;

    public FractalNode(FractalType fractalType) {
        this.fractalType = fractalType;
        this.fractalParams = createDefaultParams(fractalType);
    }

    public FractalType getFractalType() {
        return fractalType;
    }

    public void setFractalType(FractalType fractalType) {
        this.fractalType = fractalType;
        this.fractalParams = createDefaultParams(fractalType);
    }

    public AbstractFractalParams getFractalParams() {
        return fractalParams;
    }

    @Override
    public List<GraphNode> getChildren() {
        return Collections.emptyList();
    }

    /**
     * Create default params for a given fractal type.
     * Returns null for unsupported types (scenes, custom shader, node graph).
     */
    public static AbstractFractalParams createDefaultParams(FractalType type) {
        return switch (type) {
            case MANDELBULB -> new MandelbulbParams();
            case MANDELBOX -> new MandelboxParams();
            case MENGER_SPONGE -> new MengerSpongeParams();
            case KALEIDOSCOPIC_IFS -> new KaleidoscopicIFSParams();
            case POLYHEDRAL_IFS -> new PolyhedralIFSParams();
            case SIERPINSKI -> new SierpinskiParams();
            case PSEUDO_KLEINIAN -> new PseudoKleinianParams();
            case APOLLONIAN -> new ApollonianParams();
            case BRISTORBROT -> new BristorbrotParams();
            case MANDELORUS -> new MandelorusParams();
            case QUATERNION_JULIA_4D -> new QuaternionJulia4DParams();
            case MENGER_ADVANCED -> new MengerAdvancedParams();
            case MENGER_SPONGE_TEST -> new MengerSpongeTestParams();
            case CUSTOM_SHADER -> new CustomShaderParams();
            default -> null;
        };
    }
}
