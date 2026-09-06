package org.fractalizer.test;

import org.fractalizer.explore.CameraExplorer;
import org.fractalizer.explore.CameraExplorer.Candidate;
import org.fractalizer.explore.CameraExplorer.Settings;
import org.fractalizer.explore.ControllerViewRenderer;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Runs exactly what the app's Explore button runs — {@link CameraExplorer} over the GPU
 * controller at thumbnail size — from a fractal's default camera or a .frac, and writes
 * the scored views as a sheet, best first. Also reports the time per view, which is what
 * the dialog's responsiveness is made of.
 *
 * Usage:
 *   -Dexec.args="MANDELBULB out/explore 320x180 4 3 4 0.6"
 *                (TYPE-or-.frac outDir WxH samples targets steps shrink)
 *   -Dexec.args="MANDELBULB out/explore_var 320x180 4 variations 12 0.2"
 *                (... samples variations count amplitude) — the Variations tab instead:
 *                the scene's default-selected knobs nudged, same camera, scored.
 */
public class ExploreProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBULB";
        String outDir = args.length > 1 ? args[1] : "out/explore";
        String[] res = (args.length > 2 ? args[2] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        boolean variations = args.length > 4 && args[4].equalsIgnoreCase("variations");
        int targets = !variations && args.length > 4 ? Integer.parseInt(args[4]) : 3;
        int steps = !variations && args.length > 5 ? Integer.parseInt(args[5]) : 4;
        float shrink = !variations && args.length > 6 ? Float.parseFloat(args[6]) : 0.6f;
        int count = variations && args.length > 5 ? Integer.parseInt(args[5]) : 12;
        double amplitude = variations && args.length > 6 ? Double.parseDouble(args[6]) : 0.2;
        new File(outDir).mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        AbstractFractalParams params;
        if (spec.toLowerCase(Locale.ROOT).endsWith(".frac")) {
            org.fractalizer.config.FractalConfig cfg = org.fractalizer.config.FractalConfigManager.load(new File(spec));
            controller.setFractalType(cfg.getFractalTypeEnum());
            params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
        } else {
            controller.setFractalType(FractalType.valueOf(spec));
            params = (AbstractFractalParams) controller.getParams();
        }
        params.setPathTracingEnabled(false);
        controller.updatePaletteTexture(params.getCustomGradient());

        float[] eye0 = params.getCamera().getPosition().clone();
        float[] fwd0 = params.getCamera().getForwardVector().clone();
        float fovDeg = (float) Math.toDegrees(params.getFov());
        if (variations) {
            runVariations(controller, params, spec, outDir, W, H, samples, count, amplitude);
            System.exit(0);
        }
        System.out.printf(Locale.ROOT, "=== ExploreProbe %s from eye=(%.2f,%.2f,%.2f) %dx%d %d spp targets=%d steps=%d shrink=%.2f ===%n",
                spec, eye0[0], eye0[1], eye0[2], W, H, samples, targets, steps, shrink);

        List<Candidate> found = new ArrayList<>();
        long[] last = {System.nanoTime()};
        CameraExplorer.Listener listener = new CameraExplorer.Listener() {
            @Override public void candidate(Candidate c) {
                long ms = (System.nanoTime() - last[0]) / 1_000_000;
                last[0] = System.nanoTime();
                found.add(c);
                System.out.printf(Locale.ROOT, "  %-18s score=%8.0f detail=%8.0f cov=%3d%% centroid=%.2f dist=%.3f  (%d ms since last)%n",
                        c.label(), c.aesthetic(), c.score().detail(), Math.round(c.score().coverage() * 100),
                        c.score().centroidDist(), c.camDist(), ms);
            }
            @Override public void status(double p, String message) {
                System.out.printf(Locale.ROOT, "  [%3.0f%%] %s%n", p * 100, message);
            }
        };

        long t0 = System.nanoTime();
        ControllerViewRenderer renderer = new ControllerViewRenderer(controller, params, W, H, () -> false);
        new CameraExplorer(renderer, listener, () -> false).explore(eye0, fwd0, fovDeg, new Settings(targets, steps, shrink, samples));
        long total = (System.nanoTime() - t0) / 1_000_000;
        controller.restoreViewportSize();

        found.sort(Comparator.comparingDouble(Candidate::aesthetic).reversed());
        int cols = 3, rows = (found.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(W * cols, H * Math.max(rows, 1), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        for (int i = 0; i < found.size(); i++) {
            Candidate c = found.get(i);
            int x = (i % cols) * W, y = (i / cols) * H;
            g.drawImage(c.thumbnail(), x, y, null);
            g.drawString(String.format(Locale.ROOT, "#%d %s  %.0f", i + 1, c.label(), c.aesthetic()), x + 6, y + 14);
        }
        g.dispose();
        File out = new File(outDir, "_sheet.png");
        ImageIO.write(sheet, "png", out);

        System.out.printf(Locale.ROOT, "%n%d views in %d ms (%.0f ms per view incl. probes)%n", found.size(), total,
                found.isEmpty() ? 0.0 : (double) total / found.size());
        System.out.println("best first -> " + out.getAbsolutePath());
        System.exit(0);
    }

    /** The Variations tab: default-selected knobs, nudged, rendered from the scene's camera. */
    static void runVariations(GLSLFractalizerController controller, AbstractFractalParams params, String spec,
                              String outDir, int W, int H, int samples, int count, double amplitude) throws Exception {
        List<org.fractalizer.explore.ParamKnobs.Knob> knobs = new ArrayList<>();
        for (var k : org.fractalizer.explore.ParamKnobs.of(params)) if (k.interestingByDefault()) knobs.add(k);
        System.out.printf(Locale.ROOT, "=== ExploreProbe variations %s: %d knobs, %d variants, amplitude %.2f, %dx%d %d spp ===%n",
                spec, knobs.size(), count, amplitude, W, H, samples);
        for (var k : knobs) System.out.printf(Locale.ROOT, "  knob %-40s = %.4g (scale %.3g)%n", k.name(), k.value(), k.scale());

        float[] eye = params.getCamera().getPosition().clone();
        float[] target = params.getCamera().getTarget().clone();
        float fovDeg = (float) Math.toDegrees(params.getFov());
        List<org.fractalizer.explore.ParamExplorer.Variant> found = new ArrayList<>();
        long[] last = {System.nanoTime()};
        org.fractalizer.explore.ParamExplorer.Listener listener = new org.fractalizer.explore.ParamExplorer.Listener() {
            @Override public void variant(org.fractalizer.explore.ParamExplorer.Variant v) {
                long ms = (System.nanoTime() - last[0]) / 1_000_000;
                last[0] = System.nanoTime();
                found.add(v);
                System.out.printf(Locale.ROOT, "  %-48s score=%8.0f detail=%8.0f cov=%3d%%  (%d ms)%n",
                        v.label(), v.aesthetic(), v.score().detail(), Math.round(v.score().coverage() * 100), ms);
            }
            @Override public void status(double p, String message) { }
        };
        long t0 = System.nanoTime();
        ControllerViewRenderer renderer = new ControllerViewRenderer(controller, params, W, H, () -> false);
        new org.fractalizer.explore.ParamExplorer(renderer, listener, () -> false)
                .explore(knobs, count, amplitude, 12345L, eye, target, fovDeg, samples);
        long total = (System.nanoTime() - t0) / 1_000_000;
        controller.restoreViewportSize();

        found.sort(Comparator.comparingDouble(org.fractalizer.explore.ParamExplorer.Variant::aesthetic).reversed());
        int cols = 3, rows = (found.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(W * cols, H * Math.max(rows, 1), BufferedImage.TYPE_INT_RGB);
        var g = sheet.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        for (int i = 0; i < found.size(); i++) {
            var v = found.get(i);
            int x = (i % cols) * W, y = (i / cols) * H;
            g.drawImage(v.thumbnail(), x, y, null);
            g.drawString(String.format(Locale.ROOT, "#%d %s  %.0f", i + 1, v.label(), v.aesthetic()), x + 6, y + 14);
        }
        g.dispose();
        File out = new File(outDir, "_sheet.png");
        ImageIO.write(sheet, "png", out);
        System.out.printf(Locale.ROOT, "%n%d variants in %d ms%n", found.size(), total);
        System.out.println("best first -> " + out.getAbsolutePath());
    }
}
