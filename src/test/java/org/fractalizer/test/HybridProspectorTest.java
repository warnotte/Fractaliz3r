package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hybrid prospector's generator, without a GPU: every structure it draws must compile
 * to a valid chain, every parameter draw must stay inside the step's clamps, a drawn chain
 * must survive the save it is written with, and the framing test must tell an object cut
 * by the frame from one that sits inside it.
 */
class HybridProspectorTest {

    @Test
    void everyDrawnStructureCompilesAndFollowsItsRecipe() {
        Random rnd = new Random(7);
        Set<String> signatures = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            HybridProspector.Structure st = HybridProspector.randomStructure(rnd);
            HybridNode node = HybridProspector.nodeFor(st);
            for (Step s : node.getSteps()) HybridProspector.drawParams(rnd, s);
            String glsl = assertDoesNotThrow(() -> new GraphCompiler().compile(node), st.signature());
            assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"), st.signature());
            assertFalse(node.getSteps().isEmpty());
            boolean hasShape = node.getSteps().stream().anyMatch(s ->
                    s.getType().getFamily() == HybridNode.Family.POWER || s.getType().getFamily() == HybridNode.Family.FOLD);
            assertTrue(hasShape, "a chain needs a map that makes a shape: " + st.signature());
            boolean hasSeed = node.getSteps().stream().anyMatch(s -> s.getType() == StepType.ADD_C);
            if (st.deMode() == DEMode.LOG) assertTrue(hasSeed, "escape-time chains carry a seed: " + st.signature());
            assertTrue(node.getMaxIterations() >= 8 && node.getMaxIterations() <= 16, st.signature());
            signatures.add(st.signature());
        }
        assertTrue(signatures.size() > 40, "the generator should not keep drawing the same chain: " + signatures.size());
    }

    @Test
    void everyRecipeYieldsAtLeastTwoShapeSteps() {
        Random rnd = new Random(19);
        for (int i = 0; i < 200; i++) {
            HybridProspector.Structure st = HybridProspector.randomStructure(rnd);
            String canon = HybridProspector.canonical(st.steps());
            assertTrue(canon.split(">").length >= 2, st.recipe() + ": " + canon);
        }
    }

    @Test
    void knownFamiliesAreNamedAndTrueHybridsAreNot() {
        var library = HybridProspector.librarySignatures();
        // a Mandelbox turned between passes is still a Mandelbox
        java.util.List<Step> rotatedBox = java.util.List.of(new Step(StepType.BOX_FOLD), new Step(StepType.ROTATE_ITER), new Step(StepType.ADD_C));
        assertNotNull(HybridProspector.knownAs(rotatedBox, library));
        // a bare fold with no seed: a family
        assertNotNull(HybridProspector.knownAs(java.util.List.of(new Step(StepType.ABOX_MOD)), library));   // the library has "ABox Mod, flattened"
        // Buffalo from the library, under a different gating and an extra rotation, is still Buffalo
        HybridPresets.Preset buffalo = HybridPresets.all().stream().filter(p -> p.name().equals("Buffalo")).findFirst().orElseThrow();
        java.util.List<Step> dressed = new java.util.ArrayList<>();
        for (Step s : buffalo.steps()) { Step c = s.copy(); c.setIterStart(1); dressed.add(c); }
        dressed.add(0, new Step(StepType.ROTATE));
        assertEquals("Buffalo", HybridProspector.knownAs(dressed, library));
        // two shape steps the library does not pair: new
        java.util.List<Step> cube = java.util.List.of(new Step(StepType.OCTA_FOLD), new Step(StepType.ABOX_MOD),
                new Step(StepType.BOX_FOLD), new Step(StepType.ABS_FOLD));
        assertNull(HybridProspector.knownAs(cube, library));
    }

    @Test
    void parameterDrawsStayInsideTheStepClamps() {
        Random rnd = new Random(3);
        for (StepType t : StepType.values()) {
            for (int i = 0; i < 20; i++) {
                Step s = new Step(t);
                HybridProspector.drawParams(rnd, s);
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
        HybridProspector.Structure st = HybridProspector.randomStructure(rnd);
        HybridNode node = HybridProspector.nodeFor(st);
        for (Step s : node.getSteps()) HybridProspector.drawParams(rnd, s);
        float[] c = HybridProspector.drawJulia(rnd);
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
            float[] c = HybridProspector.drawJulia(rnd);
            double r = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
            assertTrue(r >= 0.3 - 1e-4 && r <= 1.1 + 1e-4, "seed radius " + r);
        }
    }

    @Test
    void edgeCoverageTellsACutObjectFromAFramedOne() {
        int w = 40, h = 24;
        float[] cut = new float[w * h];
        java.util.Arrays.fill(cut, 0.5f);                 // surface everywhere: cut on all four sides
        assertEquals(1.0, HybridProspector.edgeCoverage(cut, w, h), 1e-9);
        float[] framed = new float[w * h];
        for (int y = 6; y < h - 6; y++) for (int x = 10; x < w - 10; x++) framed[y * w + x] = 0.5f;
        assertEquals(0.0, HybridProspector.edgeCoverage(framed, w, h), 1e-9);
        float[] empty = new float[w * h];
        assertEquals(0.0, HybridProspector.edgeCoverage(empty, w, h), 1e-9);
    }
}
