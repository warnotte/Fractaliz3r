package org.fractalizer.test;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A flight through a scene as a film: the camera follows a smooth curve through waypoints
 * (eye and what it looks at, one per line of a text file) at an even, eased pace, while
 * parameters of the fractal leaves may go from one value to another and back (uniforms, so
 * nothing recompiles); every frame a still export, FFmpeg makes the MP4.
 *
 * Usage: scene outDir WxH samples seconds fps path.txt [key=from..to ...]
 *   path.txt: "ex ey ez tx ty tz" per line, '#' starts a comment.
 *   seconds = 0 renders one still per waypoint instead (to choose the path), the parameters
 *   going from their first value at the first waypoint to their second at the last (a sweep).
 */
public class FlightFilm {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {   // JavaFX keeps the JVM alive: a failure must end the harness
            t.printStackTrace();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String spec = args[0];
        File outDir = new File(args[1]);
        String[] res = args[2].split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = Integer.parseInt(args[3]);
        double seconds = Double.parseDouble(args[4]);
        int fps = Integer.parseInt(args[5]);
        List<double[]> way = new ArrayList<>();
        for (String line : Files.readAllLines(new File(args[6]).toPath())) {
            String s = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
            s = s.trim();
            if (s.isEmpty()) continue;
            String[] t = s.split("[\\s,]+");
            double[] p = new double[6];
            for (int i = 0; i < 6; i++) p[i] = Double.parseDouble(t[i]);
            way.add(p);
        }
        List<MorphFilm.Move> moves = new ArrayList<>();
        for (int k = 7; k < args.length; k++) {
            String[] kv = args[k].split("=", 2);
            String[] ft = kv[1].split("\\.\\.");
            moves.add(new MorphFilm.Move(kv[0], Double.parseDouble(ft[0]), Double.parseDouble(ft[1])));
        }
        outDir.mkdirs();

        GLSLFractalizerController controller = PrecisionLab.start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        List<AbstractFractalParams> leaves = new ArrayList<>();
        if (params instanceof NodeGraphParams ngp) MorphFilm.collect(ngp.getGraphRoot(), leaves);
        else leaves.add(params);
        Camera cam = params.getCamera();
        controller.renderStill(32, 18, 1, () -> false);   // compile once

        boolean stills = seconds <= 0;
        int frames = stills ? way.size() : Math.max(2, (int) Math.round(seconds * fps));
        double[] lengths = stills ? null : arcLengths(way);
        System.out.printf(Locale.ROOT, "=== FlightFilm: %s, %dx%d, %d spp, %d %s, %d waypoints, %s ===%n",
                spec, w, h, samples, frames, stills ? "stills" : "frames at " + fps + " fps", way.size(), moves);
        long t0 = System.nanoTime();
        for (int k = 0; k < frames; k++) {
            double x = frames > 1 ? (double) k / (frames - 1) : 0;
            double[] p = stills ? way.get(k) : at(way, lengths, x * x * (3 - 2 * x));
            float[] eye = {(float) p[0], (float) p[1], (float) p[2]};
            float[] q = CameraUtils.lookAt(eye, new float[]{(float) p[3], (float) p[4], (float) p[5]});
            cam.setPosition(eye[0], eye[1], eye[2]);
            cam.setQuaternion(q[0], q[1], q[2], q[3]);
            double u = stills ? x : 0.5 - 0.5 * Math.cos(2 * Math.PI * x);   // a film: 0 -> 1 -> 0; stills: a sweep
            for (MorphFilm.Move m : moves) MorphFilm.set(leaves, m.key(), m.from() + (m.to() - m.from()) * u);
            if (params instanceof NodeGraphParams ngp) ngp.updateUniforms();
            long f0 = System.nanoTime();
            controller.exportAnimationFrame(new File(outDir, String.format(stills ? "way_%02d.png" : "frame_%05d.png", k)), w, h, samples);
            if (stills || k % 10 == 0 || k == frames - 1) {
                double el = (System.nanoTime() - t0) / 1e9;
                System.out.printf(Locale.ROOT, "  %s %4d / %d  %5.1f s this one, %5.0f s so far, %.0f s left%n",
                        stills ? "still" : "frame", k, frames, (System.nanoTime() - f0) / 1e9, el, el / (k + 1) * (frames - k - 1));
            }
        }
        if (!stills) {
            File mp4 = new File(outDir, "flight.mp4");
            FFmpegExporter.ExportResult r = FFmpegExporter.createMP4(outDir, mp4, fps);
            System.out.println(r.success ? "video: " + mp4.getAbsolutePath() : "ffmpeg: " + r.message);
        }
        controller.close();
        Platform.exit();
        System.exit(0);
    }

    private static final int FINE = 64;   // samples per span of the curve, for its length

    /** Catmull-Rom through the waypoints, t in [0, n-1]; all six numbers together. */
    static double[] spline(List<double[]> way, double t) {
        int n = way.size();
        int i = Math.min((int) Math.floor(t), n - 2);
        double f = t - i;
        double[] a = way.get(Math.max(i - 1, 0)), b = way.get(i), c = way.get(i + 1), d = way.get(Math.min(i + 2, n - 1));
        double[] out = new double[6];
        for (int k = 0; k < 6; k++) {
            out[k] = 0.5 * (2 * b[k] + (c[k] - a[k]) * f + (2 * a[k] - 5 * b[k] + 4 * c[k] - d[k]) * f * f
                    + (3 * b[k] - a[k] - 3 * c[k] + d[k]) * f * f * f);
        }
        return out;
    }

    /** Length of the eye's curve up to each fine sample. */
    static double[] arcLengths(List<double[]> way) {
        int m = (way.size() - 1) * FINE;
        double[] len = new double[m + 1];
        double[] prev = spline(way, 0);
        for (int j = 1; j <= m; j++) {
            double[] p = spline(way, (double) j / FINE);
            len[j] = len[j - 1] + Math.sqrt(sq(p[0] - prev[0]) + sq(p[1] - prev[1]) + sq(p[2] - prev[2]));
            prev = p;
        }
        return len;
    }

    /** The pose at a fraction of the curve's length. */
    static double[] at(List<double[]> way, double[] len, double fraction) {
        double want = fraction * len[len.length - 1];
        int j = 1;
        while (j < len.length - 1 && len[j] < want) j++;
        double span = len[j] - len[j - 1];
        double f = span > 0 ? (want - len[j - 1]) / span : 0;
        return spline(way, (j - 1 + f) / FINE);
    }

    private static double sq(double v) { return v * v; }
}
