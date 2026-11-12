package org.fractalizer.test;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.render.ViewportScheduler;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Does the interactive viewport ever hand the GPU a long piece of work while the camera
 * dives?
 *
 * The viewport plans each step (a preview, a sample or a strip of the refinement) from what
 * the scene cost so far. A camera that jumps much deeper can make a step many times dearer
 * than planned: past ~1e-10 the deep estimate goes from float-float to double (several times
 * the cost) and past what double resolves the rays stop converging and take all their steps.
 * A step past the driver's two seconds resets it and takes the application with it (the
 * owner's Mandelbulb, 2026-11-12). This drives the real scheduler down a ladder of cameras
 * toward one surface point, as PrecisionLab places them: at each rung a request (the
 * preview), then the start of the refinement; and reports the longest step of each rung
 * against what it was planned to.
 *
 * Keep the viewport small when looking for the fault (320x180): an overrun there is a
 * fraction of what it is at full size, so it shows without resetting the driver.
 *
 * Usage: scene WxH ladder [eye,target] [key=value...]
 *   ladder: comma-separated distances, or "dive" for 1e-3 down to 1e-15, three a decade
 */
public class ViewportDiveProbe {

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
        String[] res = (args.length > 1 ? args[1] : "320x180").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        String ladderSpec = args.length > 2 ? args[2] : "dive";
        double[] ladder;
        if (ladderSpec.equals("dive")) {
            ladder = new double[37];
            for (int i = 0; i < ladder.length; i++) ladder[i] = Math.pow(10, -3 - i / 3.0);
        } else {
            String[] v = ladderSpec.split(",");
            ladder = new double[v.length];
            for (int i = 0; i < v.length; i++) ladder[i] = Double.parseDouble(v[i]);
        }

        GLSLFractalizerController controller = PrecisionLab.start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        Camera camera = params.getCamera();
        DeepZoomLab.params = params;
        for (int k = 3; k < args.length; k++) {
            String a = args[k];
            if (a.contains(",") && !a.contains("=")) {
                String[] v = a.split(",");
                float[] eye = {Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2])};
                float[] q = CameraUtils.lookAt(eye, new float[]{Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5])});
                camera.setPosition(eye[0], eye[1], eye[2]);
                camera.setQuaternion(q[0], q[1], q[2], q[3]);
            } else if (a.contains("=") && !a.startsWith("abort=")) {
                String[] kv = a.split("=", 2);
                DeepZoomLab.applyVariant(kv[0], kv[1]);
                System.out.println("set " + kv[0] + " = " + kv[1]);
            }
        }
        params.setDeepPrecision(true);
        controller.renderStill(32, 18, 1, () -> false);   // compile off the FX thread
        controller.restoreViewportSize();

        float[] f = camera.getForwardVector();
        double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        double[] fwd = {f[0] / fl, f[1] / fl, f[2] / fl};
        double t0 = PrecisionLab.centreDistance(controller);
        if (!(t0 > 0)) {
            System.out.println("The centre ray misses: nothing to dive into. RESULT: FAIL");
            return 1;
        }
        double[] s = PrecisionLab.along(camera.getPositionD(), fwd, t0);

        AtomicReference<ViewportEvent.Phase> phase = new AtomicReference<>();
        AtomicInteger refineImages = new AtomicInteger();
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) {
                if (phase.get() == ViewportEvent.Phase.REFINING || phase.get() == ViewportEvent.Phase.DONE) refineImages.incrementAndGet();
            }
            @Override public void onStatus(ViewportEvent.Status st) { phase.set(st.phase()); }
        });

        System.out.printf(Locale.ROOT, "=== ViewportDiveProbe: %s, viewport %dx%d, %d rungs ===%n", spec, w, h, ladder.length);
        System.out.printf("%-9s | %9s %9s %7s | %s%n", "distance", "step ms", "plan ms", "x plan", "the longest step");
        double worstRatio = 0;
        long worstNs = 0;
        // "abort=ms": the step that ends the dive (default 150 ms: 2.5 refinement slices)
        long abortNs = 150_000_000L;
        for (String a : args) if (a.startsWith("abort=")) abortNs = (long) (Double.parseDouble(a.substring(6)) * 1e6);
        controller.takeLongestViewportStep();
        for (double c : ladder) {
            // place the camera c before the surface point, the point refined from there
            double[] eye = PrecisionLab.along(s, fwd, -c);
            camera.setPosition(eye[0], eye[1], eye[2]);
            double t = PrecisionLab.centreDistance(controller);
            if (t > 0) s = PrecisionLab.along(eye, fwd, t);
            eye = PrecisionLab.along(s, fwd, -c);
            camera.setPosition(eye[0], eye[1], eye[2]);
            controller.takeLongestViewportStep();

            refineImages.set(0);
            phase.set(null);
            CountDownLatch posted = new CountDownLatch(1);
            Platform.runLater(() -> { controller.requestRender(); posted.countDown(); });
            posted.await(5, TimeUnit.SECONDS);
            // the preview, then the refinement's first steps (it starts 400 ms after the preview)
            long until = System.nanoTime() + 4_000_000_000L;
            while (System.nanoTime() < until && refineImages.get() < 2 && phase.get() != ViewportEvent.Phase.DONE) Thread.sleep(20);
            ViewportScheduler.StepRecord r = controller.takeLongestViewportStep();
            if (r == null) {
                System.out.printf(Locale.ROOT, "%-9.1e | no step%n", c);
                continue;
            }
            double ratio = (double) r.gpuNs() / r.planNs();
            worstRatio = Math.max(worstRatio, ratio);
            worstNs = Math.max(worstNs, r.gpuNs());
            System.out.printf(Locale.ROOT, "%-9.1e | %9.1f %9.1f %7.1f | %s%n", c, r.gpuNs() / 1e6, r.planNs() / 1e6, ratio, r.job());
            // Overruns grow down a dive: stop at the first clear one rather than go deeper
            // toward a step past the driver's two seconds.
            if (r.gpuNs() > abortNs) {
                System.out.printf(Locale.ROOT, "stopped: a step took over %.0f ms%n", abortNs / 1e6);
                worstRatio = Math.max(worstRatio, 1e9);
                break;
            }
        }
        // Planned at 30 ms (preview) or 60 ms (refinement); a step 30 times its plan is near the
        // driver's two seconds at full size.
        boolean ok = worstRatio <= 8;
        System.out.printf(Locale.ROOT, "longest step %.1f ms, %.1f times its plan%n", worstNs / 1e6, worstRatio);
        System.out.println(ok ? "RESULT: PASS (no step over 8 times its plan)" : "RESULT: FAIL (a step over 8 times its plan)");
        controller.close();
        Platform.exit();
        return ok ? 0 : 1;
    }
}
