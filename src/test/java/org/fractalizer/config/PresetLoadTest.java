package org.fractalizer.config;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loading a preset must give the preset, whatever was loaded before it. The bug this
 * guards: a single-type file applied onto a node graph whose root was a hybrid chain
 * found no fractal node to write into and dropped its Julia constant without a word —
 * the thumbnail forge shipped every Julia preset as a picture of the last chain.
 */
class PresetLoadTest {

    @Test
    void aJuliaPresetOnFreshParamsKeepsItsConstant() throws Exception {
        FractalConfig cfg = FractalConfigManager.load(new File("presets/JULIA_BULB_CORAL.frac"));
        NodeGraphParams fresh = cfg.toFreshParams();
        assertEquals(FractalType.MANDELBULB, fresh.getRootFractalType());
        MandelbulbParams mb = assertInstanceOf(MandelbulbParams.class, fresh.getRootFractalParams());
        assertEquals(-0.15f, mb.getJuliaCx(), 1e-6f, "the file's Julia constant is on the fresh scene");
        assertTrue(mb.getJuliaCx() != 0 || mb.getJuliaCy() != 0 || mb.getJuliaCz() != 0, "Julia mode");
    }

    /** The old path, kept as a statement of the failure mode: no fractal node, no values. */
    @Test
    void applyingASingleTypeFileOntoAHybridRootLosesItsFractalParams() throws Exception {
        FractalConfig cfg = FractalConfigManager.load(new File("presets/JULIA_BULB_CORAL.frac"));
        NodeGraphParams polluted = new NodeGraphParams();
        HybridNode chain = new HybridNode();
        HybridPresets.apply(chain, HybridPresets.all().get(3));
        polluted.setGraphRoot(chain);

        cfg.applyTo(polluted);
        assertInstanceOf(HybridNode.class, polluted.getGraphRoot(), "the chain is still the root");
        assertNull(polluted.getRootFractalParams(), "so there is nowhere for the Julia constant to go");

        // and the same file through toFreshParams() is immune to what came before
        assertNotNull(cfg.toFreshParams().getRootFractalParams());
    }

    @Test
    void everyShippedPresetLoadsOntoFreshParamsWithTheRootItDeclares() throws Exception {
        File[] files = new File("presets").listFiles((d, n) -> n.endsWith(".frac"));
        assertNotNull(files);
        Arrays.sort(files);
        assertTrue(files.length >= 10);
        for (File f : files) {
            FractalConfig cfg = FractalConfigManager.load(f);
            NodeGraphParams fresh = cfg.toFreshParams();
            assertNotNull(fresh.getGraphRoot(), f.getName() + " has a graph root");
            FractalType type = cfg.getFractalTypeEnum();
            if (type != FractalType.NODE_GRAPH) {
                assertEquals(type, fresh.getRootFractalType(), f.getName());
                assertNotNull(fresh.getRootFractalParams(), f.getName() + " keeps its fractal params");
            }
            // the camera is the file's, not the type's default
            float[] filePos = cfg.camera.position;
            assertArrayEquals(filePos, fresh.getCamera().getPosition(), 1e-6f, f.getName() + " camera");
        }
    }

    @Test
    void aChainLoadsAsAFreshSceneFramedLikeItsThumbnail() {
        HybridPresets.Preset p = HybridPresets.all().get(5);
        NodeGraphParams fresh = HybridPresets.toFreshParams(p);
        HybridNode root = assertInstanceOf(HybridNode.class, fresh.getGraphRoot());
        assertEquals(p.steps().size(), root.getSteps().size());
        assertArrayEquals(HybridPresets.previewEye(p), fresh.getCamera().getPosition(), 1e-6f);
        assertFalse(fresh.isPathTracingEnabled(), "thumbnails are classic-shaded, so is the load");
        assertNotNull(root.getName(), "nodes are named, for the editor and the animation tracks");
        // Not the monochrome defaults: a chain on coloring mode 0 with the stock gradient
        // renders silver, which is what the first browser shipped.
        assertEquals(10, fresh.getColoringMode(), "a multi-hue coloring mode");
        assertEquals(5, fresh.getCustomGradient().getStops().size(), "the showcase gradient");
        assertEquals(1, fresh.getSkyType(), "space sky with its own nebula tint");
    }
}
