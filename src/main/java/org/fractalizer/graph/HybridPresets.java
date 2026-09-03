package org.fractalizer.graph;

import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;

import java.util.ArrayList;
import java.util.List;

/**
 * Named chains, ready to load into a {@link HybridNode}.
 *
 * Several entries on the IDEAS.md formula wish-list need no new shader at all — they are
 * sequences of steps that already exist. BoxBulb is a power map with a box fold, Buffalo a
 * power map with absolute-value folds, MarbleMarcher a Menger IFS with a rotation between
 * iterations. Making them selectable turns that part of the roadmap from shader work into
 * a dropdown.
 *
 * The first two are the controls from {@code test/HybridLab}: they reproduce the
 * stand-alone Mandelbulb and Mandelbox exactly (verified on the depth AOV), which makes
 * them the right starting point when building a chain of your own — begin from a shape you
 * recognise, then add a step.
 */
public final class HybridPresets {

    /** previewDist is the camera distance that frames the chain sensibly — these live in
     *  worlds of very different sizes, a Mandelbox being several times a Mandelbulb. */
    public record Preset(String name, String description, List<Step> steps,
                         int iterations, float bailout, DEMode deMode, float previewDist) {}

    private HybridPresets() {}

    private static Step bulb(float power) {
        Step s = new Step(StepType.BULB);
        s.setPower(power);
        return s;
    }

    private static Step boxFold(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.BOX_FOLD);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    private static Step folded(StepType t, float scale, float ox, float oy, float oz) {
        Step s = new Step(t);
        s.setScale(scale); s.setOffsetX(ox); s.setOffsetY(oy); s.setOffsetZ(oz);
        return s;
    }

    private static Step rotate(float rx, float ry, float rz) {
        Step s = new Step(StepType.ROTATE);
        s.setRotX(rx); s.setRotY(ry); s.setRotZ(rz);
        return s;
    }

    private static Step invert(float radius) {
        Step s = new Step(StepType.SPHERE_INVERT);
        s.setRadius(radius);
        return s;
    }

    private static Step plain(StepType t) { return new Step(t); }

    /** Every preset here has been rendered and checked; none is theoretical. */
    public static List<Preset> all() {
        List<Preset> p = new ArrayList<>();

        p.add(new Preset("Mandelbulb (control)",
                "Reproduces the stand-alone Mandelbulb exactly. A known starting point.",
                List.of(bulb(8f), plain(StepType.ADD_C)), 15, 2f, DEMode.LOG, 3f));

        p.add(new Preset("Mandelbox (control)",
                "Reproduces the stand-alone Mandelbox exactly.",
                List.of(boxFold(2f, 0.25f, 1f, 1f), plain(StepType.ADD_C)), 15, 1000f, DEMode.LINEAR, 12f));

        p.add(new Preset("BoxBulb",
                "IDEAS #14. A box fold nested inside a power map at every scale.",
                List.of(bulb(8f), boxFold(1.2f, 0.5f, 1f, 1f), plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Buffalo",
                "IDEAS #14. Power map with absolute-value folds; plate-like surfaces.",
                List.of(bulb(8f), plain(StepType.ABS_FOLD), plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("MarbleMarcher",
                "IDEAS #14. Menger IFS with a rotation between iterations.",
                List.of(folded(StepType.MENGER_FOLD, 2f, 1f, 1f, 1f), rotate(18f, 27f, 0f)),
                14, 1000f, DEMode.LINEAR, 4f));

        p.add(new Preset("BoxBulb, rotated",
                "A rotation between the two maps breaks the symmetry the pair would keep.",
                List.of(bulb(6f), rotate(24f, 37f, 0f), boxFold(1.3f, 0.5f, 1f, 1f),
                        plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Bulb + inversion",
                "Sphere inversion folded into the orbit; broad sweeping shells.",
                List.of(bulb(8f), invert(1.1f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Bulb + Menger",
                "Cubic sorting fold inside a power map; cut-outs on a rounded body.",
                List.of(bulb(4f), folded(StepType.MENGER_FOLD, 2f, 1f, 1f, 1f), plain(StepType.ADD_C)),
                10, 8f, DEMode.LOG, 3.5f));

        p.add(new Preset("Tetra, rotated",
                "Rotated tetrahedral fold — a KIFS the graph cannot build from CSG.",
                List.of(folded(StepType.SIERPINSKI_FOLD, 2f, 1f, 1f, 1f), rotate(12f, 0f, 18f)),
                14, 1000f, DEMode.LINEAR, 4f));

        return p;
    }

    /** Overwrite a node's chain in place, so the node keeps its identity in the graph. */
    public static void apply(HybridNode node, Preset preset) {
        node.getSteps().clear();
        for (Step s : preset.steps()) node.getSteps().add(s.copy());
        node.setMaxIterations(preset.iterations());
        node.setBailout(preset.bailout());
        node.setDeMode(preset.deMode());
    }
}
