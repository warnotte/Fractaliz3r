package org.fractalizer.test;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.FractalNode;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A scene as a film in which the formula itself moves: the camera swings around the scene's
 * centre while parameters of its fractal leaves go from one value to another and back
 * (uniforms, so nothing recompiles), every frame a still export, FFmpeg makes the MP4.
 *
 * Usage: scene outDir WxH samples seconds fps swingDegrees [key=from..to ...]
 *   key is a setter of the fractal leaf's parameters (power, juliaCx, scale...). The swing
 *   and the parameters follow one cosine over the film, so the last frame meets the first.
 */
public class MorphFilm {

    record Move(String key, double from, double to) {}

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/PRISM_GEM.frac";
        File outDir = new File(args.length > 1 ? args[1] : "out/morph");
        String[] res = (args.length > 2 ? args[2] : "960x540").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        double seconds = args.length > 4 ? Double.parseDouble(args[4]) : 10;
        int fps = args.length > 5 ? Integer.parseInt(args[5]) : 30;
        double swing = Math.toRadians(args.length > 6 ? Double.parseDouble(args[6]) : 30);
        List<Move> moves = new ArrayList<>();
        for (int k = 7; k < args.length; k++) {
            String[] kv = args[k].split("=", 2);
            String[] ft = kv[1].split("\\.\\.");
            moves.add(new Move(kv[0], Double.parseDouble(ft[0]), Double.parseDouble(ft[1])));
        }
        outDir.mkdirs();

        GLSLFractalizerController controller = PrecisionLab.start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        List<AbstractFractalParams> leaves = new ArrayList<>();
        if (params instanceof NodeGraphParams ngp) collect(ngp.getGraphRoot(), leaves);
        else leaves.add(params);
        Camera cam = params.getCamera();
        double[] eye0 = cam.getPositionD();
        double radius = Math.hypot(eye0[0], eye0[2]), angle0 = Math.atan2(eye0[0], eye0[2]);
        controller.renderStill(32, 18, 1, () -> false);   // compile once

        int frames = Math.max(2, (int) Math.round(seconds * fps));
        System.out.printf(Locale.ROOT, "=== MorphFilm: %s, %dx%d, %d spp, %d frames at %d fps, swing %.0f deg, %s ===%n",
                spec, w, h, samples, frames, fps, Math.toDegrees(swing), moves);
        long t0 = System.nanoTime();
        for (int k = 0; k < frames; k++) {
            double phase = 2 * Math.PI * k / frames;
            double u = 0.5 - 0.5 * Math.cos(phase);            // 0 -> 1 -> 0, eased at both ends
            double a = angle0 + swing * Math.sin(phase);       // left, right and back
            float[] eye = {(float) (radius * Math.sin(a)), (float) eye0[1], (float) (radius * Math.cos(a))};
            float[] q = CameraUtils.lookAt(eye, new float[]{0f, 0f, 0f});
            cam.setPosition(eye[0], eye[1], eye[2]);
            cam.setQuaternion(q[0], q[1], q[2], q[3]);
            for (Move m : moves) set(leaves, m.key(), m.from() + (m.to() - m.from()) * u);
            if (params instanceof NodeGraphParams ngp) ngp.updateUniforms();
            controller.exportAnimationFrame(new File(outDir, String.format("frame_%05d.png", k)), w, h, samples);
            if (k % 10 == 0 || k == frames - 1) {
                double el = (System.nanoTime() - t0) / 1e9;
                System.out.printf(Locale.ROOT, "  frame %4d / %d  %5.0f s, %.0f s left%n", k, frames, el, el / (k + 1) * (frames - k - 1));
            }
        }
        File mp4 = new File(outDir, "morph.mp4");
        FFmpegExporter.ExportResult r = FFmpegExporter.createMP4(outDir, mp4, fps);
        System.out.println(r.success ? "video: " + mp4.getAbsolutePath() : "ffmpeg: " + r.message);
        controller.close();
        Platform.exit();
        System.exit(0);
    }

    static void collect(GraphNode n, List<AbstractFractalParams> out) {
        if (n == null) return;
        if (n instanceof FractalNode f) out.add(f.getFractalParams());
        for (GraphNode c : n.getChildren()) collect(c, out);
    }

    /** Set a float parameter on every leaf that has it. */
    static void set(List<AbstractFractalParams> leaves, String key, double value) throws Exception {
        String name = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        boolean found = false;
        for (AbstractFractalParams p : leaves) {
            for (Method m : p.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                Class<?> t = m.getParameterTypes()[0];
                if (t == float.class) { m.invoke(p, (float) value); found = true; }
                else if (t == double.class) { m.invoke(p, value); found = true; }
                else if (t == int.class) { m.invoke(p, (int) Math.round(value)); found = true; }
            }
        }
        if (!found) throw new IllegalArgumentException("no fractal leaf has a parameter " + key);
    }
}
