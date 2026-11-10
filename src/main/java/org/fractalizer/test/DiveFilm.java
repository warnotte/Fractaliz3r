package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A dive as a film: the camera goes straight at the surface point under the centre of the
 * view, from as far back as the free space behind it allows down to a given distance, the
 * distance shrinking geometrically (the same number of frames per decade), eased in and out,
 * with a slow roll. Deep precision on. Every frame is an export (the scene's own shading and
 * post-processing); FFmpeg makes the MP4.
 *
 * <p>The surface point is refined by the probe from each frame's camera, as PrecisionLab
 * places its ladder: from a distance c the probe is exact to c times a float step, so the
 * point is always as exact as the frame needs. A refinement that lands far from where the
 * point was expected (the centre ray passing through a gap the closer view opens) is ignored.
 *
 * <p>Usage: scene outDir WxH samples seconds fps endDistance [rollDegrees]
 */
public class DiveFilm {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/KIFS_DIVE.frac";
        File outDir = new File(args.length > 1 ? args[1] : "out/dive_film");
        String[] res = (args.length > 2 ? args[2] : "960x540").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 12;
        double seconds = args.length > 4 ? Double.parseDouble(args[4]) : 24;
        int fps = args.length > 5 ? Integer.parseInt(args[5]) : 30;
        double end = args.length > 6 ? Double.parseDouble(args[6]) : 2e-9;
        double rollDegrees = args.length > 7 ? Double.parseDouble(args[7]) : 60;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        params.setDeepPrecision(true);
        params.setDofEnabled(false);
        controller.renderStill(64, 36, 1, () -> false);      // compile, once
        controller.restoreViewportSize();
        Camera cam = params.getCamera();

        float[] f = cam.getForwardVector();
        double fl = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        double[] fwd = {f[0] / fl, f[1] / fl, f[2] / fl};
        double[] eye0 = cam.getPositionD();
        double t0 = centre(controller);
        if (!(t0 > 0)) {
            System.out.println("The centre ray misses: nothing to dive into.");
            exit(controller, 1);
        }
        double[] S = PrecisionLab.along(eye0, fwd, t0);
        // Back out along the line as far as the free space behind the camera allows.
        double behind = along(controller, new double[]{-fwd[0], -fwd[1], -fwd[2]});
        double start = t0 + 0.8 * Math.min(behind > 0 ? behind : 3.0, 3.0);

        int frames = Math.max(2, (int) Math.round(seconds * fps));
        double[] ease = easedProgress(frames, 0.12);
        System.out.printf(Locale.ROOT, "=== DiveFilm: %s, %dx%d, %d spp, %d frames at %d fps, %.3g -> %.3g (%.1f decades), roll %.0f deg ===%n",
                spec, w, h, samples, frames, fps, start, end, Math.log10(start / end), rollDegrees);

        float rollStep = (float) (Math.toRadians(rollDegrees) / (frames - 1) / (cam.getRotateSpeed() * 10));
        long tStart = System.nanoTime();
        int refined = 0, ignored = 0;
        for (int k = 0; k < frames; k++) {
            double c = Math.exp(Math.log(start) + (Math.log(end) - Math.log(start)) * ease[k]);
            double[] eye = PrecisionLab.along(S, fwd, -c);
            cam.setPosition(eye[0], eye[1], eye[2]);
            // Refine the point from here; keep it only where the surface was expected.
            double t = centre(controller);
            if (t > 0 && Math.abs(t - c) < 0.25 * c) {
                S = PrecisionLab.along(eye, fwd, t);
                eye = PrecisionLab.along(S, fwd, -c);
                cam.setPosition(eye[0], eye[1], eye[2]);
                refined++;
            } else ignored++;
            if (k > 0) cam.roll(rollStep);

            controller.exportAnimationFrame(new File(outDir, String.format("frame_%05d.png", k)), w, h, samples);
            if (k % 10 == 0 || k == frames - 1) {
                double elapsed = (System.nanoTime() - tStart) / 1e9;
                System.out.printf(Locale.ROOT, "  frame %4d / %d  distance %.3e  %5.0f s, %.0f s left%n",
                        k, frames, c, elapsed, elapsed / (k + 1) * (frames - k - 1));
            }
        }
        System.out.printf("  point refined on %d frames, kept on %d (the centre ray found another surface)%n", refined, ignored);

        File mp4 = new File(outDir, "dive.mp4");
        FFmpegExporter.ExportResult r = FFmpegExporter.createMP4(outDir, mp4, fps);
        System.out.println(r.success ? "video: " + mp4.getAbsolutePath() : "ffmpeg: " + r.message);
        System.out.printf("done in %d s%n", (System.nanoTime() - tStart) / 1_000_000_000L);
        exit(controller, r.success ? 0 : 1);
    }

    /** Progress 0..1 over the frames at a constant pace, the first and last share of the film
     *  easing in and out of it (the pace ramps with a smoothstep). */
    static double[] easedProgress(int frames, double rampShare) {
        double[] v = new double[frames];
        double sum = 0;
        for (int k = 0; k < frames; k++) {
            double u = (k + 0.5) / frames;
            v[k] = smooth(Math.min(u, 1 - u) / rampShare);
            sum += v[k];
        }
        double[] p = new double[frames];
        double acc = 0;
        for (int k = 0; k < frames; k++) {
            p[k] = acc / sum;
            acc += v[k];
        }
        p[frames - 1] = 1.0;
        return p;
    }

    private static double smooth(double x) {
        double t = Math.max(0, Math.min(1, x));
        return 0.05 + 0.95 * t * t * (3 - 2 * t);
    }

    private static double centre(GLSLFractalizerController controller) throws Exception {
        return PrecisionLab.centreDistance(controller);
    }

    private static double along(GLSLFractalizerController controller, double[] dir) throws Exception {
        CompletableFuture<GLSLEngine.ProbeHit[]> fut = new CompletableFuture<>();
        controller.probeAlong(dir, fut::complete);
        GLSLEngine.ProbeHit[] hits = fut.get(60, TimeUnit.SECONDS);
        return hits == null || !hits[0].hit() ? -1 : hits[0].distance();
    }

    private static void exit(GLSLFractalizerController controller, int code) {
        controller.close();
        Platform.exit();
        System.exit(code);
    }
}
