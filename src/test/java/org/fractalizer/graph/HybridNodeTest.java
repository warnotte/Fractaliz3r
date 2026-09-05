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
        assertTrue(all.size() >= 20, "library has " + all.size() + " chains");
        Set<String> names = new HashSet<>();
        for (HybridPresets.Preset p : all) {
            assertTrue(names.add(p.name()), "duplicate chain name " + p.name());
            assertFalse(p.steps().isEmpty(), p.name() + " has no steps");
            assertFalse(p.description().isBlank(), p.name() + " has no description");
        }
        assertTrue(all.get(0).name().contains("Mandelbulb"), "first entry is the Mandelbulb control");
        assertTrue(all.get(1).name().contains("Mandelbox"), "second entry is the Mandelbox control");
        assertTrue(all.get(2).name().contains("Bristorbrot"), "third entry is the Bristorbrot control");
    }

    /** A step type nobody can reach from the library is a step type nobody has rendered. */
    @Test
    void everyStepTypeAppearsInAtLeastOneLibraryChain() {
        Set<StepType> used = new HashSet<>();
        for (HybridPresets.Preset p : HybridPresets.all()) {
            for (Step s : p.steps()) used.add(s.getType());
        }
        for (StepType t : StepType.values()) {
            assertTrue(used.contains(t), t + " is in no library chain");
        }
    }

    @Test
    void stepTypesHaveFamiliesHintsAndDistinctDisplayNames() {
        Set<String> names = new HashSet<>();
        for (StepType t : StepType.values()) {
            assertNotNull(t.getFamily(), t.name());
            assertFalse(t.getHint().isBlank(), t + " has no hint");
            assertTrue(names.add(t.getDisplayName()), "duplicate display name " + t.getDisplayName());
        }
        assertTrue(StepType.values().length >= 25, "the library grew to " + StepType.values().length + " steps");
    }

    /** Julia constants belong to the preset, so loading a chain must set or clear them. */
    @Test
    void applyingAPresetSetsItsJuliaConstantAndClearsItOtherwise() {
        HybridPresets.Preset withJulia = HybridPresets.all().stream()
                .filter(p -> p.julia() != null).findFirst().orElseThrow();
        HybridNode n = new HybridNode();
        HybridPresets.apply(n, withJulia);
        assertEquals(withJulia.julia()[0], n.getJuliaCx());
        assertEquals(withJulia.julia()[1], n.getJuliaCy());
        assertEquals(withJulia.julia()[2], n.getJuliaCz());

        HybridPresets.apply(n, HybridPresets.all().get(0));
        assertEquals(0f, n.getJuliaCx()); assertEquals(0f, n.getJuliaCy()); assertEquals(0f, n.getJuliaCz());
    }

    /** Every step type, on its own and inside a chain, in every estimator family: the
     *  GLSL has to come out and every uniform it declares has to receive a value. */
    @Test
    void everyStepTypeCompilesAndDeclaresOnlyUniformsItIsGivenValuesFor() {
        for (StepType t : StepType.values()) {
            for (DEMode mode : DEMode.values()) {
                HybridNode n = new HybridNode();
                n.getSteps().clear();
                n.getSteps().add(new Step(t));
                n.getSteps().add(new Step(StepType.ADD_C));
                n.setDeMode(mode);
                GraphCompiler compiler = new GraphCompiler();
                String glsl = assertDoesNotThrow(() -> compiler.compile(n), t + " / " + mode);
                Map<String, Object> uniforms = compiler.getUniforms(n);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("uniform \\w+ (h0_s0_\\w+);").matcher(glsl);
                int declared = 0;
                while (m.find()) {
                    declared++;
                    assertTrue(uniforms.containsKey(m.group(1)),
                            t + " declares " + m.group(1) + " but never sets it");
                }
                assertEquals(GraphCompiler.stepUniforms(t).size(), declared, t + " uniform count");
            }
        }
    }

    /** A gated step is wrapped in an iteration test; an ungated one is not, so the
     *  control chains keep the exact code they were validated with. */
    @Test
    void iterationGatingIsBakedIntoTheChainBody() {
        HybridNode n = new HybridNode();               // Bulb, Box Fold, Add Seed
        String plain = new GraphCompiler().compile(n);
        assertFalse(plain.contains("if (i >="), "no gate on an ungated chain");
        assertTrue(plain.contains("void h0_chain(inout vec3 z, inout float dr, vec3 c, int i)"));

        Step box = n.getSteps().get(1);
        box.setIterStart(1); box.setIterEnd(4); box.setIterEvery(1);
        assertTrue(box.isGated());
        assertEquals("1-3", box.describeGate());
        String gated = new GraphCompiler().compile(n);
        assertTrue(gated.contains("if (i >= 1 && i < 4)"), gated);
        assertFalse(gated.contains("% 1"), "no modulo for every = 1");

        box.setIterEvery(2);
        assertEquals("1-3, every 2nd", box.describeGate());
        String every = new GraphCompiler().compile(n);
        assertTrue(every.contains("if (i >= 1 && i < 4 && ((i - 1) % 2) == 0)"), every);
        assertTrue(n.describeChain().contains("Box Fold [1-3, every 2nd]"));
    }

    @Test
    void axisAndPlaneTrapEstimatorChangeTheEmittedCode() {
        HybridNode n = new HybridNode();
        n.getSteps().clear();
        Step k = new Step(StepType.ROTATIONAL_FOLD);
        n.getSteps().add(k);
        k.setAxis(0);
        assertTrue(new GraphCompiler().compile(n).contains("vec2 _pq = z.yz;"));
        k.setAxis(2);
        assertTrue(new GraphCompiler().compile(n).contains("vec2 _pq = z.xy;"));

        n.setDeMode(DEMode.PLANE);
        String glsl = new GraphCompiler().compile(n);
        assertTrue(glsl.contains("abs(z.z + 0.1) / (3.0 * max(abs(dr), 1e-9))"), "Kleinian plane-trap estimator");
        assertTrue(glsl.contains("r = length(z);\n    float de ="), "final radius recomputed after the loop");
    }

    @Test
    void newStepTypesStartFromSensibleValuesAndTheOriginalOnesDoNot() {
        Step benesi = new Step(StepType.BENESI_FOLD);
        assertEquals(2f, benesi.getOffsetX()); assertEquals(0f, benesi.getOffsetY());
        Step bulb = new Step(StepType.BULB);
        assertEquals(8f, bulb.getPower()); assertEquals(1f, bulb.getOffsetX());
        Step box = new Step(StepType.BOX_FOLD);
        assertEquals(2f, box.getScale()); assertEquals(0.25f, box.getMinRadius());
        assertFalse(box.isGated(), "runs on every iteration by default");
        assertEquals("", box.describeGate());
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
        assertTrue(glsl.contains("void h0_chain(inout vec3 z, inout float dr, vec3 c, int i)"));
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
        Step twist = new Step(StepType.TWIST);
        twist.setAxis(1); twist.setRotX(45f); twist.setDist(0.7f); twist.setCount(7);
        twist.setIterStart(2); twist.setIterEnd(9); twist.setIterEvery(3);
        original.getSteps().add(twist);

        Map<String, Object> map = FractalConfig.serializeGraphNode(original);
        HybridNode back = assertInstanceOf(HybridNode.class, FractalConfig.deserializeGraphNode(map));

        assertEquals(original.describeChain(), back.describeChain());
        Step t = back.getSteps().get(back.getSteps().size() - 1);
        assertEquals(1, t.getAxis()); assertEquals(7, t.getCount()); assertEquals(0.7f, t.getDist());
        assertEquals(2, t.getIterStart()); assertEquals(9, t.getIterEnd()); assertEquals(3, t.getIterEvery());
        assertTrue(t.isGated());
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
