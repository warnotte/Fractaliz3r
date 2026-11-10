package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.engine.Camera;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.DiveSpeed;
import org.fractalizer.ui.GLSLFractalizerController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A dive with the auto speed, headless, on any scene. The camera holds the forward key at 60
 * frames a second, as the app's animation timer does. Each frame moves by what
 * {@link DiveSpeed} gives: a fraction of the free distance ahead, which the probe measures
 * with the scene's own program. What the probe measured is logged, and a still is rendered
 * each time the distance ahead crosses a decade, so the dive can be looked at as well as read.
 *
 * What must hold: the camera never crosses the surface (the distance ahead and the estimate at
 * the camera stay positive) and the dive descends at the pace the dial sets (at 0.1, a decade
 * in about 60 frames). Then the back key: the zoom out climbs at the same pace, without a leap. Where the float march stops resolving the surface the readings turn to
 * noise. That depth is reported, not failed: double precision in the shader is a later step.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DiveProbe" \
 *       -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac out/dive 480x270 16 0.1 600"
 *   (scene-or-TYPE outDir WxH samplesPerStill dial maxFrames [eyeX,eyeY,eyeZ,targetX,targetY,targetZ])
 *   The optional camera is a detail view's (out/nav/detail_scenes.txt), for a scene whose
 *   own camera is not worth diving from.
 *
 * Exit code 1 when the camera reaches or crosses the surface above 1e-5, the dive descends
 * fewer than four decades, or backing out climbs fewer than three.
 */
public class DiveProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        File outDir = new File(args.length > 1 ? args[1] : "out/dive");
        String[] res = (args.length > 2 ? args[2] : "480x270").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 16;
        double dial = args.length > 4 ? Double.parseDouble(args[4]) : 0.1;
        int maxFrames = args.length > 5 ? Integer.parseInt(args[5]) : 600;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        params.setDofEnabled(false);
        if (args.length > 6) {
            String[] v = args[6].split(",");
            float[] eye = {Float.parseFloat(v[0]), Float.parseFloat(v[1]), Float.parseFloat(v[2])};
            float[] q = CameraUtils.lookAt(eye, new float[]{Float.parseFloat(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5])});
            params.getCamera().setPosition(eye[0], eye[1], eye[2]);
            params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
        }
        controller.renderStill(64, 36, 1, () -> false);      // compile, once
        controller.restoreViewportSize();
        Camera cam = params.getCamera();

        System.out.printf(Locale.ROOT, "=== DiveProbe: %s, dial %.3g, %d frames at most ===%n", spec, dial, maxFrames);
        GLSLEngine.ProbeHit[] view = probe(controller, new float[]{0f, 0f, -0.9f, -0.9f, 0f, -0.9f, 0.9f, -0.9f, -0.9f, 0f, 0.9f, 0f, -0.9f, 0.9f, 0.9f, 0.9f});
        GLSLEngine.ProbeHit centre = view[0];
        double nearest = Double.MAX_VALUE;
        for (GLSLEngine.ProbeHit hit : view) if (hit.hit()) nearest = Math.min(nearest, hit.distance());
        // The estimate at the camera is a lower bound of the distance in every direction; the
        // nearest surface along eight rays across the view shows how loose it is here.
        System.out.printf(Locale.ROOT, "  start: surface ahead at %.6g; estimate at the camera %.6g, nearest surface across the view %.6g%n",
                centre.distance(), centre.clearance(), nearest);
        if (!centre.hit()) {
            System.out.println("  the centre ray misses: nothing ahead to dive into. RESULT: FAIL");
            exit(controller, false);
        }

        // The probe DiveSpeed measures with, logging what it measures.
        double[] seen = {centre.distance(), centre.clearance(), centre.distance()};
        DiveSpeed speed = new DiveSpeed((dir, onReading) -> controller.probeRays(DiveSpeed.rays(dir), hits -> {
            DiveSpeed.Reading r = DiveSpeed.reading(hits);
            seen[0] = r.distance();
            seen[1] = r.clearance();
            seen[2] = r.view();
            onReading.accept(r);
        }), System::nanoTime);

        double start = centre.distance(), lowest = start;
        int nextDecade = (int) Math.floor(Math.log10(start));
        int frames = 0, held = 0, crossed = -1;
        double lastAhead = start, lastClearance = centre.clearance();
        long stepNs = 0;
        still(controller, outDir, w, h, samples, String.format(Locale.ROOT, "dive_%03d_start.png", 0));
        // Frames at 60 per second: the probe answers between them, as in the app. Faster than
        // real time, every frame would run on a measure several frames old (the auto speed
        // copes by slowing down, safely, but that is not the dive the app shows).
        final long frameNs = 16_666_667L;
        long next = System.nanoTime();
        while (frames < maxFrames) {
            next += frameNs;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            frames++;
            float[] f = cam.getForwardVector();
            double[] forward = {f[0], f[1], f[2]};
            long t0 = System.nanoTime();
            double step = speed.step(dial, cam.getPositionD(), forward);
            stepNs += System.nanoTime() - t0;
            if (!(step > 0)) { held++; continue; }                    // waiting for a measure: this frame holds
            float keep = cam.getMoveSpeed();
            cam.setMoveSpeed((float) step);
            cam.moveForward(1);
            cam.setMoveSpeed(keep);

            double ahead = seen[0], clearance = seen[1];
            // The surface ahead receding by orders of magnitude in one frame is either the
            // geometry (a sheet seen from afar dissolving into copies with gaps between them:
            // the ray now passes through) or the camera stepping through a wall. The estimate
            // at the camera tells them apart: through a gap it stays as small as it was.
            if (ahead > 100 * lastAhead && lastAhead > 0) {
                System.out.printf(Locale.ROOT, "  frame %4d: the surface ahead receded %.3e -> %.3e; estimate at the camera %.3e -> %.3e%n",
                        frames, lastAhead, ahead, lastClearance, clearance);
            }
            lastAhead = ahead;
            lastClearance = clearance;
            if (ahead > 0) lowest = Math.min(lowest, ahead);
            if (!(ahead > 0 && clearance > 0) && crossed < 0) {
                crossed = frames;
                System.out.printf(Locale.ROOT, "  frame %d: ahead %.3g, estimate %.3g: the camera is at the surface or the float march no longer resolves it (last good %.3g)%n",
                        frames, ahead, clearance, lowest);
            }
            if (frames % 60 == 0) {
                System.out.printf(Locale.ROOT, "  frame %4d: surface ahead %.3e, estimate at the camera %.3e, step %.3e%n", frames, ahead, clearance, step);
            }
            if (ahead > 0 && Math.log10(ahead) < nextDecade) {
                System.out.printf(Locale.ROOT, "  frame %4d: below 1e%d ahead, %d frames held so far%n", frames, nextDecade, held);
                still(controller, outDir, w, h, samples, String.format(Locale.ROOT, "dive_%03d_1e%d.png", frames, nextDecade));
                nextDecade--;
                next = System.nanoTime();                         // the still is not dive time
            }
        }
        double decades = Math.log10(start / lowest);
        System.out.printf(Locale.ROOT, "  %d frames (%d held waiting for a measure), %.1f decades down to %.3e ahead (%.0f frames a decade), DiveSpeed.step %.3f ms a frame%n",
                frames, held, decades, lowest, frames / Math.max(decades, 1e-9), stepNs / 1e6 / Math.max(frames, 1));

        // Then the back key, as long: the zoom out must open the gap in front at the pace the
        // zoom in closed it (a decade in about 60 frames at 0.1), not leap by the free space
        // behind the camera.
        System.out.println("  --- backing out ---");
        double from = seen[2], biggestLeap = 1;
        int back = 0, backHeld = 0;
        next = System.nanoTime();
        double lastView = from;
        while (back < Math.min(maxFrames, 480)) {
            next += frameNs;
            long wait = next - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            back++;
            float[] f = cam.getForwardVector();
            double[] backward = {-f[0], -f[1], -f[2]};
            double step = speed.step(dial, cam.getPositionD(), backward);
            if (!(step > 0)) { backHeld++; continue; }
            float keep = cam.getMoveSpeed();
            cam.setMoveSpeed((float) step);
            cam.moveForward(-1);
            cam.setMoveSpeed(keep);
            double front = seen[2];
            if (front > 0 && lastView > 0 && Double.isFinite(front)) biggestLeap = Math.max(biggestLeap, front / lastView);
            if (Double.isFinite(front) && front > 0) lastView = front;
            if (back % 60 == 0) System.out.printf(Locale.ROOT, "  frame %4d: surface in front %.3e, step %.3e%n", back, front, step);
        }
        double opened = Math.log10(lastView / from);
        System.out.printf(Locale.ROOT, "  %d frames back (%d held): %.1f decades out, %.3e -> %.3e in front (%.0f frames a decade); largest jump in one frame x%.2f%n",
                back, backHeld, opened, from, lastView, back / Math.max(opened, 1e-9), biggestLeap);
        boolean ok = decades >= 4 && (crossed < 0 || lowest < 1e-5) && opened >= 3;
        System.out.println("  stills in " + outDir.getPath());
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        exit(controller, ok);
    }

    private static GLSLEngine.ProbeHit[] probe(GLSLFractalizerController controller, float[] uv) throws Exception {
        CompletableFuture<GLSLEngine.ProbeHit[]> f = new CompletableFuture<>();
        controller.probe(uv, f::complete);
        GLSLEngine.ProbeHit[] hits = f.get(30, TimeUnit.SECONDS);
        if (hits == null) throw new IllegalStateException("probe: no program for the scene");
        return hits;
    }

    private static void still(GLSLFractalizerController controller, File dir, int w, int h, int samples, String name) throws Exception {
        BufferedImage img = controller.renderStill(w, h, samples, () -> false);
        controller.restoreViewportSize();
        ImageIO.write(img, "png", new File(dir, name));
    }

    private static void exit(GLSLFractalizerController controller, boolean ok) {
        controller.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }
}
