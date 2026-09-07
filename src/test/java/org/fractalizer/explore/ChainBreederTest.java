package org.fractalizer.explore;

import org.fractalizer.explore.ChainBreeder.Parent;
import org.fractalizer.explore.ChainBreeder.Settings;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Result;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphCompiler;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Family;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Breeding without a GPU. The operators: a parameter mutation changes values and stays
 * inside the clamps, a structural mutation keeps the chain a hybrid and compiles, a
 * crossover carries steps from both parents, keeps one seed at the end of an escape-time
 * child and compiles. A generation, judged against the analytic sphere: nine children,
 * most sharing the parent's shader, each with a look nudged from the parent's, a parent
 * taken from the scene when its graph is a chain.
 */
class ChainBreederTest {

    private static HybridNode randomChain(Random rnd) {
        HybridNode n = ChainProspector.nodeFor(ChainProspector.randomStructure(rnd));
        for (Step s : n.getSteps()) ChainProspector.drawParams(rnd, s);
        return n;
    }

    private static long shapeCount(HybridNode n) {
        return n.getSteps().stream().filter(s -> s.getType().getFamily() == Family.POWER || s.getType().getFamily() == Family.FOLD).count();
    }

    @Test
    void parameterMutationChangesValuesAndStaysInsideTheClamps() {
        Random rnd = new Random(2);
        int changed = 0;
        for (int i = 0; i < 40; i++) {
            HybridNode parent = randomChain(rnd);
            HybridNode child = ChainProspector.snapshot(parent);
            ChainBreeder.mutateParams(child, rnd, 0.25f);
            assertEquals(parent.describeChain(), child.describeChain(), "structure untouched");
            assertEquals(new GraphCompiler().compile(parent), new GraphCompiler().compile(child), "same shader");
            for (Step s : child.getSteps()) {
                assertTrue(s.getPower() >= 1f && s.getPower() <= 24f);
                assertTrue(Math.abs(s.getScale()) <= 5f);
                assertTrue(s.getMinRadius() >= 0.01f && s.getFixedRadius() >= 0.05f);
                assertTrue(s.getCount() >= 1 && s.getCount() <= 24);
            }
            if (!GraphCompiler.collectUniformsStatic(parent).toString().equals(GraphCompiler.collectUniformsStatic(child).toString())) changed++;
        }
        assertTrue(changed >= 36, "a mutation should nearly always move something: " + changed + "/40");
    }

    @Test
    void structuralMutationKeepsAHybridThatCompiles() {
        Random rnd = new Random(5);
        Set<String> moves = new HashSet<>();
        for (int i = 0; i < 80; i++) {
            HybridNode parent = randomChain(rnd);
            HybridNode child = ChainBreeder.mutateStructure(parent, rnd);
            assertNotSame(parent, child);
            assertTrue(shapeCount(child) >= 2, "still a hybrid: " + child.describeChain());
            assertDoesNotThrow(() -> new GraphCompiler().compile(child), child.describeChain());
            if (parent.getDeMode() == DEMode.LOG) {
                assertEquals(StepType.ADD_C, child.getSteps().get(child.getSteps().size() - 1).getType(), "escape-time keeps its seed last");
            }
            if (!parent.describeChain().equals(child.describeChain())) moves.add("changed");
        }
        assertTrue(moves.contains("changed"), "structural moves change the chain's description");
    }

    @Test
    void crossoverCarriesStepsFromBothParentsAndKeepsOneSeed() {
        Random rnd = new Random(9);
        int carriedBoth = 0;
        for (int i = 0; i < 80; i++) {
            HybridNode a = randomChain(rnd), b = randomChain(rnd);
            HybridNode child = ChainBreeder.crossover(a, b, rnd);
            assertTrue(shapeCount(child) >= 2, "a hybrid: " + child.describeChain());
            assertDoesNotThrow(() -> new GraphCompiler().compile(child), child.describeChain());
            long seeds = child.getSteps().stream().filter(s -> s.getType() == StepType.ADD_C).count();
            assertTrue(seeds <= 1, "one seed at most");
            if (child.getDeMode() == DEMode.LOG) {
                assertEquals(1, seeds, "escape-time needs a seed");
                assertEquals(StepType.ADD_C, child.getSteps().get(child.getSteps().size() - 1).getType(), "and it is last");
            }
            Set<StepType> ta = new HashSet<>(), tb = new HashSet<>();
            for (Step s : a.getSteps()) ta.add(s.getType());
            for (Step s : b.getSteps()) tb.add(s.getType());
            boolean fromA = false, fromB = false;
            for (Step s : child.getSteps()) { if (ta.contains(s.getType())) fromA = true; if (tb.contains(s.getType())) fromB = true; }
            if (fromA && fromB) carriedBoth++;
        }
        assertTrue(carriedBoth > 40, "most crossovers carry steps of both parents: " + carriedBoth + "/80");
    }

    @Test
    void aGenerationIsMostlyMutantsOnTheParentsShaderPlusAFewStructuralChildren() {
        ChainProspectorTest.SphereRenderer renderer = new ChainProspectorTest.SphereRenderer();
        List<Discovery> reported = new ArrayList<>();
        List<Double> progress = new ArrayList<>();
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { reported.add(d); }
            @Override public void status(double p, String message) { progress.add(p); }
        };
        Random rnd = new Random(1);
        Parent a = new Parent(randomChain(rnd), Look.showcase(), new float[]{1.3f, 0.9f, -2.6f});
        Parent b = new Parent(randomChain(rnd), Look.draw(rnd), new float[]{2.9f, 2.1f, -5.9f});
        Result r = new ChainBreeder(renderer, listener, () -> false).breed(a, b, new Settings(9, 3, 1, 4L, 64, 36, 0.25f));

        assertEquals(9, r.discoveries().size(), "a sphere renders every child");
        assertEquals(reported, r.discoveries());
        assertEquals(5, renderer.chainsSet, "two parents' structures and three structural children: five compiles");
        assertEquals(9, renderer.looks, "every child wears a look");
        long mutantsOfA = r.discoveries().stream().filter(d -> d.recipe().equals("mutant")).count();
        long mutantsOfB = r.discoveries().stream().filter(d -> d.recipe().equals("mutant of the second parent")).count();
        long structural = r.discoveries().stream().filter(d -> d.recipe().equals("crossover") || d.recipe().equals("restructured")).count();
        assertEquals(4, mutantsOfA);
        assertEquals(2, mutantsOfB);
        assertEquals(3, structural);
        String canonA = ChainProspector.canonical(a.chain().getSteps());
        for (Discovery d : r.discoveries()) {
            if (d.recipe().equals("mutant")) {
                assertEquals(canonA, ChainProspector.canonical(d.chain().getSteps()), "a mutant keeps the parent's shape steps");
                assertEquals(a.chain().describeChain(), d.chain().describeChain());
            }
            assertNotNull(d.look());
            assertEquals(64, d.thumbnail().getWidth());
            assertTrue(d.score() > 0);
        }
        assertTrue(r.discoveries().stream().filter(d -> d.recipe().equals("mutant"))
                .anyMatch(d -> d.look().paletteOffset() != a.look().paletteOffset()), "looks drift from the parent's");
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);
        assertEquals(5, r.structures());
    }

    @Test
    void aSingleParentBreedsAloneAndCancelStopsTheGeneration() {
        ChainProspectorTest.SphereRenderer renderer = new ChainProspectorTest.SphereRenderer();
        List<Discovery> reported = new ArrayList<>();
        boolean[] stop = {false};
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { reported.add(d); if (reported.size() == 2) stop[0] = true; }
            @Override public void status(double p, String message) { }
        };
        Parent a = new Parent(randomChain(new Random(3)), Look.showcase(), new float[]{1.3f, 0.9f, -2.6f});
        Result r = new ChainBreeder(renderer, listener, () -> stop[0]).breed(a, null, new Settings(9, 3, 1, 4L, 48, 27, 0.25f));
        assertEquals(2, r.discoveries().size(), "stopped after the second child");
        assertEquals(1, renderer.chainsSet, "the parent's structure only; no structural child was compiled");
    }

    @Test
    void theSceneIsAParentWhenItsGraphIsAChainAndNotOtherwise() {
        NodeGraphParams scene = HybridPresets.toFreshParams(HybridPresets.all().get(6));
        Parent p = Parent.of(scene);
        assertNotNull(p);
        assertEquals(((HybridNode) scene.getGraphRoot()).describeChain(), p.label());
        assertNotSame(scene.getGraphRoot(), p.chain(), "a copy");
        assertArrayEquals(scene.getCamera().getPosition(), p.eye(), 1e-6f);
        assertEquals(scene.getColoringMode(), p.look().coloringMode(), "the look the scene wears");
        assertEquals("scene palette, scene light, scene sky", p.look().name());
        assertNull(Parent.of(new NodeGraphParams(FractalType.MANDELBULB)), "a Mandelbulb node graph is not a chain");
    }

    @Test
    void aLookReadFromASceneComesBackTheSame() {
        Look drawn = Look.draw(new Random(6));
        NodeGraphParams p = new NodeGraphParams(FractalType.MANDELBULB);
        drawn.apply(p);
        Look read = Look.of(p);
        assertEquals(drawn.coloringMode(), read.coloringMode());
        assertEquals(drawn.skyType(), read.skyType());
        assertEquals(drawn.stops().length, read.stops().length);
        assertEquals(drawn.lightIntensity(), read.lightIntensity(), 1e-6f);
        assertArrayEquals(drawn.lightDir(), read.lightDir(), 1e-6f);
        assertEquals(drawn.metalness(), read.metalness(), 1e-6f);
        Look mutated = read.mutate(new Random(1));
        assertTrue(mutated.lightIntensity() >= 1.0f && mutated.lightIntensity() <= 1.7f);
        assertTrue(mutated.metalness() >= 0f && mutated.metalness() <= 0.6f);
        assertTrue(mutated.paletteOffset() >= 0f && mutated.paletteOffset() < 1f);
    }
}
