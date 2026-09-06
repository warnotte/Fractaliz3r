package org.fractalizer.explore;

import org.fractalizer.explore.ParamExplorer.Variant;
import org.fractalizer.explore.ParamKnobs.Knob;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridPresets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ParamExplorerTest {

    @Test
    void knobsOfAMandelbulbAreItsFloatsNotItsIterationCount() {
        List<Knob> knobs = ParamKnobs.of(new NodeGraphParams(FractalType.MANDELBULB));
        List<String> names = knobs.stream().map(Knob::name).toList();
        assertTrue(names.contains("Power"), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.startsWith("Julia")), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.toLowerCase().contains("iteration")), "ints are not knobs: " + names);

        Knob power = knobs.stream().filter(k -> k.name().equals("Power")).findFirst().orElseThrow();
        assertEquals(8.0, power.value(), 1e-6);
        power.set().accept(6.5);
        assertEquals(6.5, power.value(), 1e-6, "the knob writes through to the params");
        assertTrue(power.interestingByDefault());
        assertFalse(knobs.stream().filter(k -> k.name().equals("Bailout")).findFirst().orElseThrow().interestingByDefault());
    }

    @Test
    void knobsOfAHybridChainAreItsJuliaConstantAndTheParametersItsStepsRead() {
        NodeGraphParams ngp = new NodeGraphParams();
        HybridNode hn = new HybridNode();
        HybridPresets.apply(hn, HybridPresets.all().get(0));   // Bulb Power -> Add Seed
        ngp.setGraphRoot(hn);
        List<String> names = ParamKnobs.of(ngp).stream().map(Knob::name).toList();
        assertTrue(names.contains("Julia Cx"), names.toString());
        assertTrue(names.contains("step 1 Bulb Power: power"), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.contains("Add Seed")), "a seed has no parameters: " + names);
        assertEquals(4, names.size(), names.toString());
    }

    /** Array-backed knobs and the sphere renderer: the logic, without a fractal. */
    static List<Knob> arrayKnobs(double[] store, double... scales) {
        List<Knob> knobs = new ArrayList<>();
        for (int i = 0; i < store.length; i++) {
            final int k = i;
            knobs.add(new Knob("k" + k, () -> store[k], v -> store[k] = v, scales[k]));
        }
        return knobs;
    }

    @Test
    void rendersTheCurrentSceneFirstThenBoundedVariantsAndPutsEveryKnobBack() {
        double[] store = {8.0, 0.0, -1.5};
        List<Knob> knobs = arrayKnobs(store, 8.0, 0.5, 1.5);
        CameraExplorerTest.SphereRenderer r = new CameraExplorerTest.SphereRenderer(48, 27);
        List<Variant> seen = new ArrayList<>();
        ParamExplorer.Listener l = new ParamExplorer.Listener() {
            @Override public void variant(Variant v) {
                seen.add(v);
                // while a variant is being reported, the knobs are already back at base
                assertEquals(8.0, store[0], 1e-9);
            }
            @Override public void status(double p, String m) { assertTrue(p >= 0 && p <= 1); }
        };
        List<Variant> out = new ParamExplorer(r, l, () -> false)
                .explore(knobs, 6, 0.2, 42L, new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50f, 1);

        assertEquals(7, out.size(), "current + 6 variants");
        assertEquals("Current", out.get(0).label());
        assertArrayEquals(new double[]{8.0, 0.0, -1.5}, out.get(0).values(), 1e-9);
        for (int i = 1; i < out.size(); i++) {
            Variant v = out.get(i);
            assertTrue(v.label().startsWith("Variant " + i + ":"), v.label());
            for (int k = 0; k < 3; k++) {
                double dev = Math.abs(v.values()[k] - store[k]) / knobs.get(k).scale();
                assertTrue(dev <= 0.2 * 3 + 1e-9, "within 3 sigma of the amplitude: " + dev);
            }
            assertFalse(Double.isNaN(v.aesthetic()));
        }
        assertArrayEquals(new double[]{8.0, 0.0, -1.5}, store, 1e-9, "knobs restored after the run");
        assertEquals(out.size(), seen.size());
    }

    @Test
    void theSameSeedGivesTheSameVariantsAndCancellingStopsEarly() {
        double[] a = {1.0, 2.0}, b = {1.0, 2.0};
        CameraExplorerTest.SphereRenderer r = new CameraExplorerTest.SphereRenderer(32, 18);
        ParamExplorer.Listener quiet = new ParamExplorer.Listener() {
            @Override public void variant(Variant v) { }
            @Override public void status(double p, String m) { }
        };
        List<Variant> va = new ParamExplorer(r, quiet, () -> false)
                .explore(arrayKnobs(a, 1, 1), 4, 0.3, 7L, new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50f, 1);
        List<Variant> vb = new ParamExplorer(r, quiet, () -> false)
                .explore(arrayKnobs(b, 1, 1), 4, 0.3, 7L, new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50f, 1);
        for (int i = 0; i < va.size(); i++) assertArrayEquals(va.get(i).values(), vb.get(i).values(), 1e-12);

        AtomicBoolean cancel = new AtomicBoolean();
        List<Variant> got = new ArrayList<>();
        ParamExplorer.Listener stopAt2 = new ParamExplorer.Listener() {
            @Override public void variant(Variant v) { got.add(v); if (got.size() == 2) cancel.set(true); }
            @Override public void status(double p, String m) { }
        };
        List<Variant> vc = new ParamExplorer(r, stopAt2, cancel::get)
                .explore(arrayKnobs(a, 1, 1), 10, 0.3, 7L, new float[]{0, 0, -3}, new float[]{0, 0, 0}, 50f, 1);
        assertEquals(2, vc.size());
        assertArrayEquals(new double[]{1.0, 2.0}, a, 1e-12, "restored even when cancelled");
    }

    @Test
    void theLabelNamesTheTwoBiggestChanges() {
        double[] store = {1, 1, 1};
        List<Knob> knobs = arrayKnobs(store, 1, 1, 1);
        String s = ParamExplorer.describe(knobs, new double[]{1, 1, 1}, new double[]{1.5, 1.0, 0.2}, 4);
        assertEquals("Variant 4: k2 1.00→0.20, k0 1.00→1.50", s);
        assertEquals("Variant 1", ParamExplorer.describe(knobs, new double[]{1, 1, 1}, new double[]{1, 1, 1}, 1));
    }
}
