package org.fractalizer.test;

import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridNode.DEMode;
import org.fractalizer.graph.HybridNode.Step;
import org.fractalizer.graph.HybridNode.StepType;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Renders a set of hybrid chains so the composed maps can be checked visually.
 *
 * The first two entries are controls, and they are the point: a chain of BULB then
 * ADD_C has to reproduce the plain Mandelbulb, and BOX_FOLD then ADD_C the plain
 * Mandelbox. If those two do not come out right, the step bodies or their derivative
 * updates are wrong and nothing further can be trusted.
 *
 * Usage:
 *   -Dexec.args="hybrid 480x270 12"
 */
public class HybridLab {

    record Chain(HybridNode node, float camDist, String note) {}

    static HybridNode chain(int iters, float bailout, DEMode mode, Step... steps) {
        return new HybridNode(List.of(steps), iters, bailout, mode);
    }

    static Step step(StepType t) { return new Step(t); }

    static Step bulb(float power) {
        Step s = new Step(StepType.BULB);
        s.setPower(power);
        return s;
    }

    static Step boxFold(float scale, float minR, float fixedR, float limit) {
        Step s = new Step(StepType.BOX_FOLD);
        s.setScale(scale); s.setMinRadius(minR); s.setFixedRadius(fixedR); s.setFoldLimit(limit);
        return s;
    }

    static Step folded(StepType t, float scale, float ox, float oy, float oz) {
        Step s = new Step(t);
        s.setScale(scale); s.setOffsetX(ox); s.setOffsetY(oy); s.setOffsetZ(oz);
        return s;
    }

    static Step rotate(float rx, float ry, float rz) {
        Step s = new Step(StepType.ROTATE);
        s.setRotX(rx); s.setRotY(ry); s.setRotZ(rz);
        return s;
    }

    static Step invert(float radius) {
        Step s = new Step(StepType.SPHERE_INVERT);
        s.setRadius(radius);
        return s;
    }

    /** The shipped chain library, rendered from the same source the editor offers, so the
     *  dropdown cannot drift away from what actually renders. */
    static Map<String, Chain> presetChains() {
        Map<String, Chain> m = new LinkedHashMap<>();
        for (org.fractalizer.graph.HybridPresets.Preset p : org.fractalizer.graph.HybridPresets.all()) {
            HybridNode n = new HybridNode();
            org.fractalizer.graph.HybridPresets.apply(n, p);
            m.put(p.name().replaceAll("[^A-Za-z0-9]+", "_"),
                    new Chain(n, p.previewDist(), p.description()));
        }
        return m;
    }

    static Map<String, Chain> chains() {
        Map<String, Chain> m = new LinkedHashMap<>();

        // --- Controls: these must reproduce the stand-alone formulas ---
        m.put("ctrl_bulb", new Chain(
                chain(15, 2f, DEMode.LOG, bulb(8), step(StepType.ADD_C)),
                3.0f, "must look like the plain Mandelbulb"));
        m.put("ctrl_box", new Chain(
                chain(15, 1000f, DEMode.LINEAR, boxFold(2f, 0.25f, 1f, 1f), step(StepType.ADD_C)),
                12.0f, "must look like the plain Mandelbox"));

        // --- Genuine hybrids: no CSG combination of two formulas can produce these ---
        m.put("bulb_box", new Chain(
                chain(12, 8f, DEMode.LOG, bulb(8), boxFold(1.2f, 0.5f, 1f, 1f), step(StepType.ADD_C)),
                3.0f, "power map with a box fold nested at every scale"));
        m.put("box_bulb", new Chain(
                chain(12, 8f, DEMode.LOG, boxFold(1.5f, 0.5f, 1f, 1f), bulb(4), step(StepType.ADD_C)),
                3.0f, "same two maps, opposite order"));
        m.put("bulb_menger", new Chain(
                chain(10, 8f, DEMode.LOG, bulb(4), folded(StepType.MENGER_FOLD, 2f, 1f, 1f, 1f), step(StepType.ADD_C)),
                3.5f, "cubic sorting fold inside a power map"));
        m.put("bulb_rot_box", new Chain(
                chain(12, 8f, DEMode.LOG, bulb(6), rotate(24, 37, 0),
                        boxFold(1.3f, 0.5f, 1f, 1f), step(StepType.ADD_C)),
                3.0f, "a rotation between the two maps breaks the symmetry"));
        m.put("tetra_rot", new Chain(
                chain(14, 1000f, DEMode.LINEAR, folded(StepType.SIERPINSKI_FOLD, 2f, 1f, 1f, 1f), rotate(12, 0, 18)),
                4.0f, "rotated tetra fold — a KIFS the graph cannot build"));
        // Two entries from IDEAS.md #14 that need no new formula, only a chain:
        // Buffalo is a Mandelbulb with absolute-value folds, MarbleMarcher a Menger IFS
        // with rotation between iterations.
        m.put("buffalo", new Chain(
                chain(12, 8f, DEMode.LOG, bulb(8), step(StepType.ABS_FOLD), step(StepType.ADD_C)),
                3.0f, "IDEAS #14 Buffalo: power map plus abs folds"));
        m.put("marblemarcher", new Chain(
                chain(14, 1000f, DEMode.LINEAR, folded(StepType.MENGER_FOLD, 2f, 1f, 1f, 1f),
                        rotate(18, 27, 0)),
                4.0f, "IDEAS #14 MarbleMarcher: Menger IFS with rotation"));

        m.put("bulb_invert", new Chain(
                chain(12, 8f, DEMode.LOG, bulb(8), invert(1.1f), step(StepType.ADD_C)),
                3.0f, "sphere inversion folded into the orbit"));

        return m;
    }

    /** Render each control chain and the stand-alone formula it should reproduce, at
     *  the same camera and parameters, and report the pixel difference. A chain of
     *  BULB then ADD_C is the Mandelbulb by construction; if the images disagree, a
     *  step body or a derivative update is wrong. */
    static void verifyControls(GLSLFractalizerController controller, NodeGraphParams ngp,
                               AbstractFractalParams params, String outDir,
                               int W, int H, int samples) throws Exception {
        System.out.println();
        System.out.println("=== controls vs the stand-alone formulas ===");

        record Ctrl(String name, HybridNode hybrid, FractalType ref, float camDist) {}
        List<Ctrl> ctrls = List.of(
            new Ctrl("bulb", chain(15, 2f, DEMode.LOG, bulb(8), step(StepType.ADD_C)),
                     FractalType.MANDELBULB, 3.0f),
            new Ctrl("box", chain(15, 1000f, DEMode.LINEAR, boxFold(2f, 0.25f, 1f, 1f), step(StepType.ADD_C)),
                     FractalType.MANDELBOX, 12.0f));

        for (Ctrl c : ctrls) {
            float d = c.camDist();
            float[] eye = {d * 0.42f, d * 0.30f, -d * 0.85f};
            float[] q = CameraUtils.lookAt(eye, new float[]{0, 0, 0});

            ngp.setGraphRoot(c.hybrid());
            ngp.markDirty();
            params.getCamera().setPosition(eye[0], eye[1], eye[2]);
            params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
            params.setFovDegrees(50);
            controller.setExportSize(W, H);
            File a = new File(outDir, "cmp_" + c.name() + "_hybrid.png");
            controller.exportToPNG(a, samples, x -> {}, () -> false).get();
            controller.setExportSize(W, H);
            File ad = new File(outDir, "cmp_" + c.name() + "_hybrid_d.png");
            controller.exportAOV(ad, 2);

            ngp.setGraphRoot(new org.fractalizer.graph.FractalNode(c.ref()));
            ngp.markDirty();
            params.getCamera().setPosition(eye[0], eye[1], eye[2]);
            params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
            params.setFovDegrees(50);
            controller.setExportSize(W, H);
            File b = new File(outDir, "cmp_" + c.name() + "_ref.png");
            controller.exportToPNG(b, samples, x -> {}, () -> false).get();
            controller.setExportSize(W, H);
            File bd = new File(outDir, "cmp_" + c.name() + "_ref_d.png");
            controller.exportAOV(bd, 2);

            BufferedImage ia = ImageIO.read(ad), ib = ImageIO.read(bd);
            double sum = 0; long n = 0; double max = 0; long differing = 0;
            for (int y = 0; y < ia.getHeight(); y++) {
                for (int x = 0; x < ia.getWidth(); x++) {
                    double va = ia.getRaster().getSample(x, y, 0) / 65535.0;
                    double vb = ib.getRaster().getSample(x, y, 0) / 65535.0;
                    double diff = Math.abs(va - vb);
                    sum += diff; n++;
                    if (diff > max) max = diff;
                    if (diff > 0.01) differing++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "  %-5s vs %-12s  depth meanDiff=%.5f  maxDiff=%.4f  pixels>1%%: %.2f%%%n",
                    c.name(), c.ref(), sum / n, max, 100.0 * differing / n);
            System.out.flush();
        }
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "hybrid";
        String[] res = (args.length > 1 ? args[1] : "480x270").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        new File(outDir).mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setFractalType(FractalType.MANDELBULB);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        NodeGraphParams ngp = (NodeGraphParams) params;
        params.setPathTracingEnabled(false);
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== HybridLab (%dx%d, %d spp) ===%n", W, H, samples);
        List<String> names = new ArrayList<>();
        boolean libraryMode = args.length > 3 && args[3].equalsIgnoreCase("presets");
        for (var e : (libraryMode ? presetChains() : chains()).entrySet()) {
            String name = e.getKey();
            Chain ch = e.getValue();

            ngp.setGraphRoot(ch.node());
            ngp.markDirty();

            float d = ch.camDist();
            float[] eye = {d * 0.42f, d * 0.30f, -d * 0.85f};
            float[] tgt = {0, 0, 0};
            params.getCamera().setPosition(eye[0], eye[1], eye[2]);
            float[] q = CameraUtils.lookAt(eye, tgt);
            params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
            params.setFovDegrees(50);

            controller.setExportSize(W, H);
            File rgb = new File(outDir, name + ".png");
            long t0 = System.nanoTime();
            controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            controller.setExportSize(W, H);
            File dep = new File(outDir, name + "_d.png");
            controller.exportAOV(dep, 2);
            var score = FractalNavigator.scoreFrame(rgb, dep);
            dep.delete();

            System.out.printf(Locale.ROOT, "  %-14s %-52s detail=%8.0f cov=%3.0f%% %6d ms%n",
                    name, ch.node().describeChain(), score.detail(), 100 * score.coverage(), ms);
            System.out.flush();
            names.add(name);
        }

        if (!libraryMode) verifyControls(controller, ngp, params, outDir, W, H, samples);

        int cols = 2, rows = (names.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(W * cols, H * rows, BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        for (int i = 0; i < names.size(); i++) {
            g.drawImage(ImageIO.read(new File(outDir, names.get(i) + ".png")),
                    (i % cols) * W, (i / cols) * H, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(outDir, "_sheet.png"));

        System.out.println();
        System.out.println("sheet -> " + new File(outDir, "_sheet.png").getAbsolutePath());
        System.exit(0);
    }
}
