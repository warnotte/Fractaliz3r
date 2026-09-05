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
 * The first entries are the controls from {@code test/HybridLab}: they reproduce the
 * stand-alone Mandelbulb, Mandelbox and Bristorbrot exactly (verified on the depth AOV),
 * which makes them the right starting point when building a chain of your own — begin
 * from a shape you recognise, then add a step.
 */
public final class HybridPresets {

    /** previewDist is the camera distance that frames the chain sensibly — these live in
     *  worlds of very different sizes, a Mandelbox being several times a Mandelbulb.
     *  julia is the seed constant, or null for Mandelbrot mode. */
    public record Preset(String name, String description, List<Step> steps,
                         int iterations, float bailout, DEMode deMode, float previewDist,
                         float[] julia) {
        public Preset(String name, String description, List<Step> steps,
                      int iterations, float bailout, DEMode deMode, float previewDist) {
            this(name, description, steps, iterations, bailout, deMode, previewDist, null);
        }
    }

    private HybridPresets() {}

    private static Step bulb(float power) {
        Step s = new Step(StepType.BULB);
        s.setPower(power);
        return s;
    }

    private static Step powered(StepType t, float power) {
        Step s = new Step(t);
        s.setPower(power);
        return s;
    }

    private static Step boxFold(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.BOX_FOLD);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    private static Step amazingSurf(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.AMAZING_SURF);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    private static Step sphereFold(float minR, float fixedR) {
        Step s = new Step(StepType.SPHERE_FOLD);
        s.setMinRadius(minR); s.setFixedRadius(fixedR);
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

    private static Step rotateIter(float rx, float ry, float rz) {
        Step s = new Step(StepType.ROTATE_ITER);
        s.setRotX(rx); s.setRotY(ry); s.setRotZ(rz);
        return s;
    }

    private static Step twist(int axis, float degPerUnit) {
        Step s = new Step(StepType.TWIST);
        s.setAxis(axis); s.setRotX(degPerUnit);
        return s;
    }

    private static Step kaleido(int axis, int count) {
        Step s = new Step(StepType.ROTATIONAL_FOLD);
        s.setAxis(axis); s.setCount(count);
        return s;
    }

    private static Step planeFold(float nx, float ny, float nz, float dist) {
        Step s = new Step(StepType.PLANE_FOLD);
        s.setOffsetX(nx); s.setOffsetY(ny); s.setOffsetZ(nz); s.setDist(dist);
        return s;
    }

    private static Step kleinian(float cx, float cy, float cz, float size) {
        Step s = new Step(StepType.KLEINIAN_FOLD);
        s.setOffsetX(cx); s.setOffsetY(cy); s.setOffsetZ(cz); s.setRadius(size);
        return s;
    }

    private static Step kali(float cx, float cy, float cz, float radius) {
        Step s = new Step(StepType.KALI_FOLD);
        s.setOffsetX(cx); s.setOffsetY(cy); s.setOffsetZ(cz); s.setRadius(radius);
        return s;
    }

    private static Step invert(float radius) {
        Step s = new Step(StepType.SPHERE_INVERT);
        s.setRadius(radius);
        return s;
    }

    private static Step plain(StepType t) { return new Step(t); }

    /** Restrict a step to iterations [from, to) taking every n-th. */
    private static Step gated(Step s, int from, int to, int every) {
        s.setIterStart(from); s.setIterEnd(to); s.setIterEvery(every);
        return s;
    }

    private static float[] julia(float x, float y, float z) { return new float[]{x, y, z}; }

    /** Every preset here has been rendered and checked; none is theoretical. */
    public static List<Preset> all() {
        List<Preset> p = new ArrayList<>();

        // --- Controls: chains that reproduce a stand-alone formula exactly ---

        p.add(new Preset("Mandelbulb (control)",
                "Reproduces the stand-alone Mandelbulb exactly. A known starting point.",
                List.of(bulb(8f), plain(StepType.ADD_C)), 15, 2f, DEMode.LOG, 3f));

        p.add(new Preset("Mandelbox (control)",
                "Reproduces the stand-alone Mandelbox exactly.",
                List.of(boxFold(2f, 0.25f, 1f, 1f), plain(StepType.ADD_C)), 15, 1000f, DEMode.LINEAR, 12f));

        p.add(new Preset("Bristorbrot (control)",
                "Reproduces the stand-alone Bristorbrot exactly.",
                List.of(plain(StepType.BRISTOR), plain(StepType.ADD_C)), 15, 4f, DEMode.LOG, 3f));

        // --- Classic hybrids from the IDEAS.md wish-list ---

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

        // --- The Mandelbulb3D / Mandelbulber catalogue, as chains ---

        p.add(new Preset("Amazing Surf",
                "Kali's Amazing Surf: the Mandelbox step with no fold on Z, negative scale. A plate of shelves.",
                List.of(amazingSurf(-1.5f, 0.5f, 1f, 1f), plain(StepType.ADD_C)),
                15, 1000f, DEMode.LINEAR, 7f));

        Step aboxFlat = new Step(StepType.ABOX_MOD);
        aboxFlat.setScale(2f); aboxFlat.setMinRadius(0.25f); aboxFlat.setFixedRadius(1f);
        aboxFlat.setOffsetX(1f); aboxFlat.setOffsetY(1f); aboxFlat.setOffsetZ(0.5f);
        p.add(new Preset("ABox Mod, flattened",
                "Mandelbox with a shorter fold limit on Z: the box squashed along one axis.",
                List.of(aboxFlat, plain(StepType.ADD_C)), 15, 1000f, DEMode.LINEAR, 18f));

        p.add(new Preset("Drifting bulb",
                "A slight contraction and shift after every power map pulls the bulb off centre.",
                List.of(bulb(8f), folded(StepType.SCALE, 0.9f, 0.15f, 0f, 0f), plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Benesi Pine Tree",
                "Benesi's T1 fold followed by his quadratic mag transform.",
                List.of(folded(StepType.BENESI_FOLD, 2f, 2f, 0f, 0f), plain(StepType.BENESI_MAG),
                        plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 4f));

        p.add(new Preset("Benesi Mag bulb",
                "The mag transform alone: a power-2 bulb in Benesi's convention.",
                List.of(plain(StepType.BENESI_MAG), plain(StepType.ADD_C)), 15, 4f, DEMode.LOG, 3f));

        p.add(new Preset("Cosine bulb",
                "The power map in Nylander's cosine convention — same power, another bulb.",
                List.of(powered(StepType.BULB_COSINE, 8f), plain(StepType.ADD_C)), 15, 2f, DEMode.LOG, 3f));

        p.add(new Preset("Quaternion Julia",
                "z^2 + c on the quaternion slice; the Tetrabrot's 3D section in Julia mode.",
                List.of(plain(StepType.QUAT_SQUARE), plain(StepType.ADD_C)),
                15, 4f, DEMode.LOG, 3f, julia(-0.2f, 0.6f, 0.2f)));

        p.add(new Preset("Riemann Sphere",
                "msltoe's Riemann sphere: a sine tiling seen through stereographic projection.",
                List.of(powered(StepType.RIEMANN, 2f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 4.5f));

        p.add(new Preset("Kali bulb",
                "The Kaliset step (abs, invert, subtract c) inside a power map: a coral ball.",
                List.of(bulb(8f), kali(0.5f, 0.5f, 0.5f, 1f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Kali shell",
                "The Kaliset step with a seed, escape-time: a smooth ribbed shell.",
                List.of(kali(0.5f, 0.5f, 0.5f, 1f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Pseudo-Kleinian",
                "Knighty's pseudo-Kleinian: per-axis box fold, interior inversion, plane-trap estimator.",
                List.of(kleinian(1f, 1f, 1.3f, 1f), plain(StepType.ADD_C)),
                7, 1000f, DEMode.PLANE, 1.2f, julia(-0.62f, -0.015f, -0.025f)));

        p.add(new Preset("Octa KIFS",
                "Octahedral fold IFS.",
                List.of(folded(StepType.OCTA_FOLD, 2f, 1f, 0f, 0f)), 14, 1000f, DEMode.LINEAR, 2.5f));

        p.add(new Preset("Icosa KIFS",
                "Icosahedral fold IFS, golden-ratio planes; the offset points at a vertex.",
                List.of(folded(StepType.ICOSA_FOLD, 2f, 0f, 0.5257f, 0.8507f)), 14, 1000f, DEMode.LINEAR, 2.5f));

        // --- Steps that only exist inside a loop ---

        p.add(new Preset("Kaleido bulb",
                "A 6-fold kaleidoscope around Z before every power map.",
                List.of(kaleido(2, 6), bulb(4f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Spiral bulb",
                "The n-th iteration is turned n times further: a rotation that grows with depth.",
                List.of(bulb(8f), rotateIter(0f, 0f, 20f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        Step morphPower = powered(StepType.COMPLEX_POWER, 2f);
        morphPower.setAxis(1);
        p.add(new Preset("JuliaMorph",
                "IDEAS #14. A 2D Julia set in the ZX plane, twisted along Y inside the loop, so every slice differs.",
                List.of(morphPower, twist(1, 60f), plain(StepType.ADD_C)),
                15, 3f, DEMode.LOG, 7f, julia(-0.4f, 0.6f, 0f)));

        p.add(new Preset("Plane-folded bulb",
                "One reflection plane inside the power map.",
                List.of(bulb(8f), planeFold(1f, 1f, 0f, 0.3f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Sphere-folded bulb",
                "The Mandelbox sphere fold alone, inside a power map.",
                List.of(bulb(8f), sphereFold(0.5f, 1f), plain(StepType.ADD_C)), 12, 8f, DEMode.LOG, 3f));

        p.add(new Preset("Bristor box",
                "Bristorbrot square with a plain box fold.",
                List.of(plain(StepType.BRISTOR), plain(StepType.BOX_FOLD_ONLY), plain(StepType.ADD_C)),
                12, 4f, DEMode.LOG, 6f));

        // --- Iteration-gated chains: formula A on some passes, formula B on the others ---

        p.add(new Preset("Box / bulb, alternating",
                "Box fold on even iterations, power map on odd ones — the Mandelbulber-style interleave.",
                List.of(gated(plain(StepType.BOX_FOLD_ONLY), 0, HybridNode.ITER_ALL, 2),
                        gated(bulb(8f), 1, HybridNode.ITER_ALL, 2), plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 1.6f));

        p.add(new Preset("Bulb, boxed early",
                "Power map on every pass, box fold only on the first three: coarse box, fine bulb.",
                List.of(bulb(8f), gated(plain(StepType.BOX_FOLD_ONLY), 0, 3, 1), plain(StepType.ADD_C)),
                12, 8f, DEMode.LOG, 3f));

        return p;
    }

    /** Overwrite a node's chain in place, so the node keeps its identity in the graph. */
    public static void apply(HybridNode node, Preset preset) {
        node.getSteps().clear();
        for (Step s : preset.steps()) node.getSteps().add(s.copy());
        node.setMaxIterations(preset.iterations());
        node.setBailout(preset.bailout());
        node.setDeMode(preset.deMode());
        float[] j = preset.julia();
        node.setJuliaCx(j == null ? 0f : j[0]);
        node.setJuliaCy(j == null ? 0f : j[1]);
        node.setJuliaCz(j == null ? 0f : j[2]);
    }
}
