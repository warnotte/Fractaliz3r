package org.fractalizer.test;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.ExportSampler;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.util.Locale;

/**
 * Does an export ever hand the GPU a long piece of work at once?
 *
 * Windows resets the display driver when the GPU does not answer within two seconds, and an
 * application drawing at that moment may go with it. Exports draw through ExportSampler,
 * which cuts the work into steps of ~300 ms (whole samples, or strips of a dearer sample).
 * This exports a scene, colour and then the normals AOV, and prints what the sampler did:
 * steps, strips, the longest step and the GPU time in all. PASS when no step took more than
 * twice the step it aims at.
 *
 * Do not point it at something whose whole sample would take seconds while testing a change
 * to the sampler: if the cutting is broken, that is exactly what resets the driver. A deep
 * Mandelbulb in classic shading at 960x540 (~0.8 s a sample whole) is heavy enough to be cut
 * and light enough to be harmless uncut.
 *
 * Usage: scene(.frac or FractalType) outDir WxH samples [at=distance] [eye,target] [key=value...]
 *   at=1e-6       the camera that far before the surface point under the view's centre
 *                 (the probe, deep precision on), as PrecisionLab places its rungs
 *   eye,target    six comma-separated numbers: the camera before that
 *   key=value     a parameter (DeepZoomLab's keys), e.g. pathTracingEnabled=false
 */
public class ExportWatchdogProbe {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }

    static int run(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBULB";
        String outDir = args.length > 1 ? args[1] : "out/watchdog";
        String[] res = (args.length > 2 ? args[2] : "960x540").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        new File(outDir).mkdirs();

        GLSLFractalizerController controller = PrecisionLab.start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        Camera camera = params.getCamera();
        DeepZoomLab.params = params;
        double at = -1;
        for (int k = 4; k < args.length; k++) {
            String a = args[k];
            if (a.startsWith("at=")) {
                at = Double.parseDouble(a.substring(3));
            } else if (a.contains(",") && !a.contains("=")) {
                String[] v = a.split(",");
                float[] eye = {Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2])};
                float[] q = CameraUtils.lookAt(eye, new float[]{Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5])});
                camera.setPosition(eye[0], eye[1], eye[2]);
                camera.setQuaternion(q[0], q[1], q[2], q[3]);
            } else if (a.contains("=")) {
                String[] kv = a.split("=", 2);
                DeepZoomLab.applyVariant(kv[0], kv[1]);
                System.out.println("set " + kv[0] + " = " + kv[1]);
            }
        }
        if (at > 0) {
            params.setDeepPrecision(true);
            controller.renderStill(32, 18, 1, () -> false);
            controller.restoreViewportSize();
            float[] f = camera.getForwardVector();
            double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
            double[] fwd = {f[0] / fl, f[1] / fl, f[2] / fl};
            double[] eye = camera.getPositionD();
            double t = PrecisionLab.centreDistance(controller);
            if (!(t > 0)) {
                System.out.println("The centre ray misses: nothing to approach. RESULT: FAIL");
                return 1;
            }
            double[] s = PrecisionLab.along(eye, fwd, t);
            for (int pass = 0; pass < 2; pass++) {   // closer, then the point refined from there
                eye = PrecisionLab.along(s, fwd, -at);
                camera.setPosition(eye[0], eye[1], eye[2]);
                double t2 = PrecisionLab.centreDistance(controller);
                if (t2 > 0) s = PrecisionLab.along(eye, fwd, t2);
            }
            eye = PrecisionLab.along(s, fwd, -at);
            camera.setPosition(eye[0], eye[1], eye[2]);
        }

        System.out.printf(Locale.ROOT, "=== ExportWatchdogProbe: %s, %dx%d, %d spp%s ===%n", spec, w, h, samples,
                at > 0 ? String.format(Locale.ROOT, ", %.0e before the surface", at) : "");
        controller.renderStill(32, 18, 1, () -> false);   // compile outside the timing
        controller.restoreViewportSize();

        boolean ok = true;
        String name = new File(spec).getName().replace(".frac", "");
        controller.setExportSize(w, h);
        long t0 = System.nanoTime();
        controller.exportToPNG(new File(outDir, name + ".png"), samples, p -> {}, () -> false).get();
        ok &= report("colour", controller.lastRenderStats(), System.nanoTime() - t0);

        controller.setExportSize(w, h);
        t0 = System.nanoTime();
        controller.exportAOV(new File(outDir, name + "_normals.png"), 1);
        ok &= report("normals", controller.lastRenderStats(), System.nanoTime() - t0);

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL (a step ran longer than twice the step)");
        controller.close();
        Platform.exit();
        return ok ? 0 : 1;
    }

    static boolean report(String what, ExportSampler.Stats s, long wallNs) {
        System.out.printf(Locale.ROOT, "  %-8s %d samples in %d steps (%d strips): longest step %.0f ms, mean %.0f ms; GPU %.2f s, wall %.2f s%n",
                what, s.samples(), s.steps(), s.strips(), s.maxStepNs() / 1e6, s.totalNs() / 1e6 / Math.max(1, s.steps()),
                s.totalNs() / 1e9, wallNs / 1e9);
        return s.maxStepNs() <= 2 * ExportSampler.STEP_NS;
    }
}
