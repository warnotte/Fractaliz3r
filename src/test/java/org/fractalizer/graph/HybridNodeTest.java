package org.fractalizer.graph;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hybrid node chains several formula steps inside one iteration loop. The chain
 * library is the way users reach the classic hybrids (Buffalo, BoxBulb, Marble Marcher)
 * without writing a formula, so a preset that fails to apply or a chain that fails to
 * compile is a missing feature, not a cosmetic bug.
 */
class HybridNodeTest {

    @Test
    void defaultChainIsBulbThenBoxFoldThenSeed() {
        HybridNode n = new HybridNode();
        assertEquals("Bulb Power -> Box Fold -> Add Seed", n.describeChain());
        assertTrue(n.getChildren().isEmpty(), "a hybrid is a leaf");
    }

    @Test
    void settersClampToTheRangesTheShaderAssumes() {
        HybridNode n = new HybridNode();
        n.setMaxIterations(500); assertEquals(64, n.getMaxIterations());
        n.setMaxIterations(0);   assertEquals(1, n.getMaxIterations());
        n.setBailout(0.1f);      assertEquals(1f, n.getBailout());
        n.setBailout(5000f);     assertEquals(1000f, n.getBailout());
        Step s = new Step(StepType.BULB);
        s.setPower(0f);  assertEquals(1f, s.getPower());
        s.setPower(99f); assertEquals(24f, s.getPower());
        s.setRotX(400f); assertEquals(180f, s.getRotX());
    }

    @Test
    void stepCopyIsIndependent() {
        Step a = new Step(StepType.BOX_FOLD);
        a.setScale(2.5f);
        a.setFoldLimit(1.2f);
        Step b = a.copy();
        b.setScale(-1.5f);
        assertEquals(2.5f, a.getScale());
        assertEquals(1.2f, b.getFoldLimit());
        assertEquals(StepType.BOX_FOLD, b.getType());
    }

    @Test
    void chainLibraryIsPopulatedWithUniqueNamesAndControlsFirst() {
        List<HybridPresets.Preset> all = HybridPresets.all();
        assertTrue(all.size() >= 5, "library has " + all.size() + " chains");
        Set<String> names = new HashSet<>();
        for (HybridPresets.Preset p : all) {
            assertTrue(names.add(p.name()), "duplicate chain name " + p.name());
            assertFalse(p.steps().isEmpty(), p.name() + " has no steps");
            assertFalse(p.description().isBlank(), p.name() + " has no description");
        }
        assertTrue(all.get(0).name().contains("Mandelbulb"), "first entry is the Mandelbulb control");
        assertTrue(all.get(1).name().contains("Mandelbox"), "second entry is the Mandelbox control");
    }

    @Test
    void applyingAPresetCopiesItsStepsSoTheLibraryStaysPristine() {
        HybridPresets.Preset preset = HybridPresets.all().get(2);
        HybridNode n = new HybridNode();
        HybridPresets.apply(n, preset);
        assertEquals(preset.steps().size(), n.getSteps().size());
        assertEquals(preset.iterations(), n.getMaxIterations());
        assertEquals(preset.bailout(), n.getBailout());
        assertEquals(preset.deMode(), n.getDeMode());

        float before = preset.steps().get(0).getScale();
        n.getSteps().get(0).setScale(before + 1f);
        assertEquals(before, preset.steps().get(0).getScale(), "the node holds copies, not the library's steps");
    }

    @Test
    void everyLibraryChainCompilesToGlsl() {
        for (HybridPresets.Preset p : HybridPresets.all()) {
            HybridNode n = new HybridNode();
            HybridPresets.apply(n, p);
            GraphCompiler compiler = new GraphCompiler();
            String glsl = assertDoesNotThrow(() -> compiler.compile(n), p.name());
            assertTrue(glsl.contains("float DE(vec3 pos, out OrbitTrap trap)"), p.name());
            assertTrue(glsl.contains("h0_chain("), p.name() + " has a shared chain body");
        }
    }

    @Test
    void compilerEmitsPerStepUniformsAndTheChosenEstimator() {
        HybridNode n = new HybridNode();               // Bulb, Box Fold, Add Seed
        n.getSteps().get(0).setPower(7f);
        n.setDeMode(DEMode.LOG);
        GraphCompiler compiler = new GraphCompiler();
        String glsl = compiler.compile(n);
        Map<String, Object> uniforms = compiler.getUniforms(n);

        assertTrue(glsl.contains("uniform int h0_maxIterations;"));
        assertTrue(glsl.contains("uniform float h0_bailout;"));
        assertTrue(glsl.contains("uniform vec3 h0_juliaC;"));
        assertTrue(glsl.contains("uniform float h0_s0_power;"), "bulb step uniform");
        assertTrue(glsl.contains("uniform float h0_s1_foldLimit;"), "box fold step uniform");
        assertTrue(glsl.contains("void h0_chain(inout vec3 z, inout float dr, vec3 c)"));
        assertTrue(glsl.contains("float h0_DE_simple(vec3 pos)"));
        assertTrue(glsl.contains("0.5 * log(r) * r / dr"), "escape-time estimator");
        assertFalse(glsl.contains("r / max(abs(dr), 1e-9)"), "not the IFS estimator");

        assertEquals(7f, ((Number) uniforms.get("h0_s0_power")).floatValue());
        assertEquals(n.getMaxIterations(), ((Number) uniforms.get("h0_maxIterations")).intValue());
        assertArrayEquals(new float[]{0, 0, 0}, (float[]) uniforms.get("h0_juliaC"), "Mandelbrot mode by default");

        n.setDeMode(DEMode.LINEAR);
        String linear = new GraphCompiler().compile(n);
        assertTrue(linear.contains("r / max(abs(dr), 1e-9)"), "IFS estimator");
    }

    @Test
    void hybridNodeSurvivesSerialization() {
        HybridNode original = new HybridNode();
        HybridPresets.apply(original, HybridPresets.all().get(3));
        original.setJuliaCx(0.42f);
        original.setJuliaCy(0.18f);
        original.setJuliaCz(-0.31f);
        original.getSteps().get(0).setRotY(33f);

        Map<String, Object> map = FractalConfig.serializeGraphNode(original);
        HybridNode back = assertInstanceOf(HybridNode.class, FractalConfig.deserializeGraphNode(map));

        assertEquals(original.describeChain(), back.describeChain());
        assertEquals(original.getMaxIterations(), back.getMaxIterations());
        assertEquals(original.getBailout(), back.getBailout());
        assertEquals(original.getDeMode(), back.getDeMode());
        assertEquals(0.42f, back.getJuliaCx()); assertEquals(0.18f, back.getJuliaCy()); assertEquals(-0.31f, back.getJuliaCz());
        for (int i = 0; i < original.getSteps().size(); i++) {
            Step a = original.getSteps().get(i), b = back.getSteps().get(i);
            assertEquals(a.getType(), b.getType(), "step " + i);
            assertEquals(a.getPower(), b.getPower(), "step " + i + " power");
            assertEquals(a.getScale(), b.getScale(), "step " + i + " scale");
            assertEquals(a.getRotY(), b.getRotY(), "step " + i + " rotY");
            assertEquals(a.getOffsetX(), b.getOffsetX(), "step " + i + " offsetX");
        }
    }
}
