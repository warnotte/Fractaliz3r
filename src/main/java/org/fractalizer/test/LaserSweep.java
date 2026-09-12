package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.TransformNode;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The laser sweep: a mirror of the optical table turns a few degrees back and forth, and
 * the beam it sends sweeps across the prism, the spectrum sliding over the table and
 * vanishing when the beam misses the glass. Every frame is the still export of the scene
 * with the mirror's yaw set for that frame (the transform's rotation is a uniform, nothing
 * recompiles), then FFmpeg makes the MP4.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.LaserSweep" \
 *       -Dexec.args="presets/LASER_TABLE.frac out/sweep 1280x720 96 96 24 45 3.5"
 *   (scene outDir WxH samples frames fps mirrorYaw amplitudeDeg): the mirror whose yaw is
 *   mirrorYaw degrees is the one that turns, by amplitudeDeg either side over one period.
 */
public class LaserSweep {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/LASER_TABLE.frac";
        File outDir = new File(args.length > 1 ? args[1] : "out/sweep");
        String[] res = (args.length > 2 ? args[2] : "1280x720").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 96;
        int frames = args.length > 4 ? Integer.parseInt(args[4]) : 96;
        int fps = args.length > 5 ? Integer.parseInt(args[5]) : 24;
        float mirrorYaw = args.length > 6 ? Float.parseFloat(args[6]) : 45f;
        float amplitude = args.length > 7 ? Float.parseFloat(args[7]) : 3.5f;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        AbstractFractalParams params = cfg.toFreshParams();
        controller.replaceParams(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        if (cfg.postProcess != null) controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
        controller.applyEnvironmentMap(cfg.effects.envMap);

        if (!(params instanceof NodeGraphParams ng)) throw new IllegalArgumentException("a node graph scene is needed");
        TransformNode mirror = null;
        for (TransformNode t : transforms(ng.getGraphRoot(), new ArrayList<>())) {
            if (Math.abs(t.getRotation()[1] - mirrorYaw) < 0.01f) { mirror = t; break; }
        }
        if (mirror == null) throw new IllegalArgumentException("no transform turned by " + mirrorYaw + " degrees about Y in the graph");
        float[] rot = mirror.getRotation().clone();

        System.out.printf("=== LaserSweep: %s at %dx%d, %d spp, %d frames at %d fps, the mirror at %.1f deg swinging %.1f deg ===%n",
                spec, w, h, samples, frames, fps, mirrorYaw, amplitude);
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            double phase = 2.0 * Math.PI * i / frames;
            float yaw = mirrorYaw + amplitude * (float) Math.sin(phase);
            mirror.setRotation(new float[]{rot[0], yaw, rot[2]});
            File frame = new File(outDir, String.format("frame_%05d.png", i));
            controller.exportAnimationFrame(frame, w, h, samples);
            if (i % 8 == 0) System.out.printf("  frame %3d / %d  yaw %6.2f  (%d s)%n", i, frames, yaw, (System.nanoTime() - t0) / 1_000_000_000L);
        }
        mirror.setRotation(rot);

        File mp4 = new File(outDir, "laser_sweep.mp4");
        FFmpegExporter.ExportResult r = FFmpegExporter.createMP4(outDir, mp4, fps);
        System.out.println(r.success ? "video: " + mp4 : "ffmpeg: " + r.message);
        System.out.printf("done in %d s%n", (System.nanoTime() - t0) / 1_000_000_000L);

        controller.close();
        Platform.exit();
        System.exit(r.success ? 0 : 1);
    }

    private static List<TransformNode> transforms(GraphNode node, List<TransformNode> out) {
        if (node == null) return out;
        if (node instanceof TransformNode t) out.add(t);
        for (GraphNode c : node.getChildren()) transforms(c, out);
        return out;
    }
}
