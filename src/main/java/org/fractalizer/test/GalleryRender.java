package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Renders every .frac in a directory at one size and sample count, exactly as the app
 * would show it: fractal params, gradient and post-processing chain all come from the
 * file. PresetForge rebuilds the presets from code before rendering; this renders what
 * is on disk, so a hand-tuned scene is rendered as saved. Used for the README gallery
 * and the release page.
 *
 * Usage:  -Dexec.args="&lt;fracDir&gt; &lt;outDir&gt; &lt;WxH&gt; &lt;samples&gt; [name-filter]"
 *         -Dexec.args="presets out/gallery 1280x720 128"
 */
public class GalleryRender {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "presets");
        File outDir = new File(args.length > 1 ? args[1] : "out/gallery");
        String[] res = (args.length > 2 ? args[2] : "1280x720").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 64;
        String filter = args.length > 4 ? args[4] : null;
        outDir.mkdirs();

        File[] files = dir.listFiles((d, n) -> n.endsWith(".frac") && (filter == null || n.contains(filter)));
        if (files == null || files.length == 0) {
            System.err.println("no .frac files in " + dir.getAbsolutePath());
            System.exit(2);
        }
        Arrays.sort(files);

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        System.out.printf("=== gallery (%dx%d, %d spp, %d scenes) ===%n", w, h, samples, files.length);
        for (File frac : files) {
            String name = frac.getName().replaceFirst("\\.frac$", "");
            FractalConfig cfg = FractalConfigManager.load(frac);
            FractalType type = cfg.getFractalTypeEnum();
            controller.setFractalType(type);
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
            // Same three steps the app performs on File > Load: params, gradient texture,
            // post-processing chain. Skipping any of them renders a different image.
            controller.updatePaletteTexture(params.getCustomGradient());
            if (cfg.postProcess != null) {
                controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
            }

            controller.setExportSize(w, h);
            long t0 = System.nanoTime();
            controller.exportToPNG(new File(outDir, name + ".png"), samples, p -> {}, () -> false).get();
            System.out.printf("  %-26s %-20s %7d ms%n", name, type, (System.nanoTime() - t0) / 1_000_000);
            System.out.flush();
        }
        System.out.println("gallery -> " + outDir.getAbsolutePath());
        System.exit(0);
    }
}
