package org.fractalizer.explore;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Result;
import org.fractalizer.explore.ChainProspector.Settings;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prospector without a GPU. Its generator: every structure it draws must compile to a
 * valid chain and follow its recipe, every parameter draw must stay inside the step's
 * clamps, a drawn chain must survive the save it is written with. Its judgement: a turned
 * Mandelbox is still a Mandelbox, a true hybrid is new, a cut object is not framed. And
 * the search itself, driven against an analytic sphere: it frames, scores, reports every
 * find to its listener, stops when told to, and its discoveries load as scenes.
 */
class ChainProspectorTest {

    // ------------------------------------------------------------- generator

    @Test
    void everyDrawnStructureCompilesAndFollowsItsRecipe() {
        Random rnd = new Random(7);
        Set<String> signatures = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            ChainProspector.Structure st = ChainProspector.randomStructure(rnd);
            HybridNode node = ChainProspector.nodeFor(st);
            for (Step s : node.getSteps()) ChainProspector.drawParams(rnd, s);
            String glsl = assertDoesNotThrow(() -> new GraphCompiler().compile(node), st.signature());
            assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"), st.signature());
            assertTrue(ChainProspector.canonical(node.getSteps()).split(">").length >= 2,
                    "two shape steps at least: " + st.signature());
            boolean hasSeed = node.getSteps().stream().anyMatch(s -> s.getType() == StepType.ADD_C);
            if (st.deMode() == DEMode.LOG) assertTrue(hasSeed, "escape-time chains carry a seed: " + st.signature());
            assertTrue(node.getMaxIterations() >= 8 && node.getMaxIterations() <= 16, st.signature());
            signatures.add(st.signature());
        }
        assertTrue(signatures.size() > 40, "the generator should not keep drawing the same chain: " + signatures.size());
    }

    @Test
    void parameterDrawsStayInsideTheStepClamps() {
        Random rnd = new Random(3);
        for (StepType t : StepType.values()) {
            for (int i = 0; i < 20; i++) {
                Step s = new Step(t);
                ChainProspector.drawParams(rnd, s);
                assertTrue(s.getPower() >= 1f && s.getPower() <= 24f, t.name());
                assertTrue(Math.abs(s.getScale()) <= 5f, t.name());
                assertTrue(s.getMinRadius() > 0 && s.getFixedRadius() > 0, t.name());
                assertTrue(s.getCount() >= 1 && s.getCount() <= 24, t.name());
                assertTrue(s.getAxis() >= 0 && s.getAxis() <= 2, t.name());
            }
        }
    }

    @Test
    void aDrawnChainSurvivesTheSaveItIsWrittenWith() {
        Random rnd = new Random(11);
        ChainProspector.Structure st = ChainProspector.randomStructure(rnd);
        HybridNode node = ChainProspector.nodeFor(st);
        for (Step s : node.getSteps()) ChainProspector.drawParams(rnd, s);
        float[] c = ChainProspector.drawJulia(rnd);
        node.setJuliaCx(c[0]); node.setJuliaCy(c[1]); node.setJuliaCz(c[2]);
        GraphNode back = FractalConfig.deserializeGraphNode(FractalConfig.serializeGraphNode(node));
        assertInstanceOf(HybridNode.class, back);
        assertEquals(node.describeChain(), ((HybridNode) back).describeChain());
        assertEquals(new GraphCompiler().compile(node), new GraphCompiler().compile(back));
    }

    @Test
    void juliaSeedsLieOnTheShellWhereBoundariesTendToBe() {
        Random rnd = new Random(5);
        for (int i = 0; i < 200; i++) {
            float[] c = ChainProspector.drawJulia(rnd);
            double r = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
            assertTrue(r >= 0.3 - 1e-4 && r <= 1.1 + 1e-4, "seed radius " + r);
        }
    }

    // ------------------------------------------------------------- judgement

    @Test
    void knownFamiliesAreNamedAndTrueHybridsAreNot() {
        var library = ChainProspector.librarySignatures();
        // a Mandelbox turned between passes is still a Mandelbox
        List<Step> rotatedBox = List.of(new Step(StepType.BOX_FOLD), new Step(StepType.ROTATE_ITER), new Step(StepType.ADD_C));
        assertNotNull(ChainProspector.knownAs(rotatedBox, library));
        // a bare fold with no seed: a family (the library even has "ABox Mod, flattened")
        assertNotNull(ChainProspector.knownAs(List.of(new Step(StepType.ABOX_MOD)), library));
        // Buffalo from the library, under a different gating and an extra rotation, is still Buffalo
        HybridPresets.Preset buffalo = HybridPresets.all().stream().filter(p -> p.name().equals("Buffalo")).findFirst().orElseThrow();
        List<Step> dressed = new ArrayList<>();
        for (Step s : buffalo.steps()) { Step c = s.copy(); c.setIterStart(1); dressed.add(c); }
        dressed.add(0, new Step(StepType.ROTATE));
        assertEquals("Buffalo", ChainProspector.knownAs(dressed, library));
        // two shape steps the library does not pair: new
        List<Step> cube = List.of(new Step(StepType.OCTA_FOLD), new Step(StepType.ABOX_MOD),
                new Step(StepType.BOX_FOLD), new Step(StepType.ABS_FOLD));
        assertNull(ChainProspector.knownAs(cube, library));
    }

    @Test
    void edgeCoverageTellsACutObjectFromAFramedOne() {
        int w = 40, h = 24;
        float[] cut = new float[w * h];
        java.util.Arrays.fill(cut, 0.5f);                 // surface everywhere: cut on all four sides
        assertEquals(1.0, ChainProspector.edgeCoverage(cut, w, h), 1e-9);
        float[] framed = new float[w * h];
        for (int y = 6; y < h - 6; y++) for (int x = 10; x < w - 10; x++) framed[y * w + x] = 0.5f;
        assertEquals(0.0, ChainProspector.edgeCoverage(framed, w, h), 1e-9);
        assertEquals(0.0, ChainProspector.edgeCoverage(new float[w * h], w, h), 1e-9);
    }

    // ------------------------------------------------------------- the search

    /** A renderer that ignores the chain and shows a unit sphere at the origin, textured
     *  with a checkerboard so it has detail: enough to drive the search end to end. */
    static final class SphereRenderer implements ChainProspector.ChainRenderer {
        int chainsSet = 0, paramChanges = 0, frames = 0;
        HybridNode lastChain;

        @Override public void setChain(HybridNode chain) { chainsSet++; lastChain = chain; }
        @Override public void chainParamsChanged() { paramChanges++; }

        private static double[] unit(double x, double y, double z) {
            double n = Math.sqrt(x * x + y * y + z * z);
            return new double[]{x / n, y / n, z / n};
        }

        /** Distance along the pixel's ray to a unit sphere at the origin, or NaN. The
         *  basis is CameraUtils.lookAt's: right = worldUp x forward, up = forward x right. */
        private static double hit(float[] eye, float[] target, float fovDeg, int w, int h, int px, int py) {
            double[] f = unit(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]);
            double[] r = unit(f[2], 0, -f[0]);                                   // (0,1,0) x f
            double[] u = {f[1] * r[2] - f[2] * r[1], f[2] * r[0] - f[0] * r[2], f[0] * r[1] - f[1] * r[0]};
            double tanH = Math.tan(Math.toRadians(fovDeg) / 2), aspect = w / (double) h;
            double sx = (2.0 * (px + 0.5) / w - 1.0) * tanH * aspect, sy = (1.0 - 2.0 * (py + 0.5) / h) * tanH;
            double[] d = {f[0] + sx * r[0] + sy * u[0], f[1] + sx * r[1] + sy * u[1], f[2] + sx * r[2] + sy * u[2]};
            double n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            d[0] /= n; d[1] /= n; d[2] /= n;
            double b = eye[0] * d[0] + eye[1] * d[1] + eye[2] * d[2];
            double c = eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2] - 1.0;
            double disc = b * b - c;
            if (disc < 0) return Double.NaN;
            double t = -b - Math.sqrt(disc);
            return t > 0 ? t : Double.NaN;
        }

        @Override public float[] depth(float[] eye, float[] target, float fovDeg, int w, int h) {
            frames++;
            float[] out = new float[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double t = hit(eye, target, fovDeg, w, h, x, y);
                out[y * w + x] = Double.isNaN(t) ? 0f : (float) (1.0 - Math.log(t + 0.1) / Math.log(15.0));
            }
            return out;
        }

        @Override public BufferedImage colour(float[] eye, float[] target, float fovDeg, int w, int h, int samples) {
            frames++;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double t = hit(eye, target, fovDeg, w, h, x, y);
                int v = Double.isNaN(t) ? 8 : (((x / 2 + y / 2) & 1) == 0 ? 40 : 220);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
            return img;
        }
    }

    @Test
    void theSearchFramesScoresAndReportsEveryFind() {
        SphereRenderer renderer = new SphereRenderer();
        List<Discovery> reported = new ArrayList<>();
        List<Double> progress = new ArrayList<>();
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { reported.add(d); }
            @Override public void status(double p, String message) { progress.add(p); assertNotNull(message); }
        };
        Settings s = new Settings(3, 2, 1, 42L, 64, 36);
        Result r = new ChainProspector(renderer, listener, () -> false).prospect(s);

        assertEquals(3, renderer.chainsSet, "one chain set per structure");
        assertEquals(6, renderer.paramChanges, "one uniform refresh per draw");
        assertEquals(3, r.structures());
        assertEquals(6, r.discoveries().size(), "a sphere renders every time: " + r.empty() + " empty, " + r.solid() + " solid, " + r.flat() + " flat");
        assertEquals(reported, r.discoveries(), "every find went to the listener, in order");
        for (Discovery d : r.discoveries()) {
            assertEquals(64, d.thumbnail().getWidth());
            assertEquals(36, d.thumbnail().getHeight());
            double dist = Math.sqrt(d.eye()[0] * d.eye()[0] + d.eye()[1] * d.eye()[1] + d.eye()[2] * d.eye()[2]);
            assertTrue(dist > 1.2 && dist < 8, "the camera settled outside the sphere, close enough to frame it: " + dist);
            assertTrue(d.frame().coverage() > 0.04 && d.frame().coverage() < 0.97, "framed: " + d.frame().coverage());
            assertTrue(d.solidity() > 0.5, "a sphere is solid: " + d.solidity());
            assertTrue(d.score() > 0);
            assertTrue(ChainProspector.canonical(d.chain().getSteps()).split(">").length >= 2);
        }
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9, "progress ends at one");
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1) - 1e-9, "progress never goes back");
        int perRecipe = r.perRecipe().values().stream().mapToInt(a -> a[0]).sum();
        assertEquals(3, perRecipe);
    }

    @Test
    void cancellingStopsTheSearchAndKeepsWhatWasFound() {
        SphereRenderer renderer = new SphereRenderer();
        List<Discovery> reported = new ArrayList<>();
        boolean[] stop = {false};
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { reported.add(d); stop[0] = true; }
            @Override public void status(double p, String message) { }
        };
        Result r = new ChainProspector(renderer, listener, () -> stop[0]).prospect(new Settings(5, 4, 1, 1L, 48, 27));
        assertEquals(1, reported.size(), "stopped after the first find");
        assertEquals(1, r.discoveries().size());
        assertEquals(1, renderer.chainsSet, "no further structure was compiled");
    }

    @Test
    void aDiscoveryLoadsAsAFreshSceneWithItsChainAndCamera() {
        SphereRenderer renderer = new SphereRenderer();
        Result r = new ChainProspector(renderer, new ChainProspector.Listener() {
            @Override public void found(Discovery d) { }
            @Override public void status(double p, String message) { }
        }, () -> false).prospect(new Settings(1, 1, 1, 9L, 48, 27));
        Discovery d = r.discoveries().get(0);
        NodeGraphParams p = ChainProspector.toParams(d);
        assertInstanceOf(HybridNode.class, p.getGraphRoot());
        assertEquals(d.label(), ((HybridNode) p.getGraphRoot()).describeChain());
        assertNotSame(d.chain(), p.getGraphRoot(), "a copy: the discovery keeps its own");
        assertArrayEquals(d.eye(), p.getCamera().getPosition(), 1e-6f);
        assertFalse(p.isPathTracingEnabled(), "the showcase look, interactive");
        // and it survives a save, as File > Save would write it
        FractalConfig cfg = FractalConfig.fromParams(p);
        NodeGraphParams back = (NodeGraphParams) cfg.toFreshParams();
        assertEquals(d.label(), ((HybridNode) back.getGraphRoot()).describeChain());
    }

    @Test
    void diverseKeepsAtMostTwoPerStructureBestFirst() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        HybridNode chain = new HybridNode();
        FrameScorer.FrameScore fs = new FrameScorer.FrameScore(1, 0.5, 0.1);
        List<Discovery> all = List.of(
                new Discovery(0, "ifs", chain, new float[]{0, 0, 3}, 10, fs, 0.9, null, img),
                new Discovery(0, "ifs", chain, new float[]{0, 0, 3}, 30, fs, 0.9, null, img),
                new Discovery(0, "ifs", chain, new float[]{0, 0, 3}, 20, fs, 0.9, null, img),
                new Discovery(1, "power", chain, new float[]{0, 0, 3}, 25, fs, 0.9, null, img));
        List<Discovery> d = ChainProspector.diverse(all, 2);
        assertEquals(List.of(30.0, 25.0, 20.0), d.stream().map(Discovery::score).toList());
    }
}
