package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * How long each shader takes to compile, and which one never returns.
 *
 * The app compiles every built-in fractal at startup, then the scene's own shader
 * when a node graph is loaded. A GLSL compiler inlines everything, so a function that
 * grows by a factor of two in a place called from ten sites grows the program by twenty
 * times its size, and past a cliff the driver either fails with a fatal error (NVIDIA
 * C9999 "Unhandled expr op ... in CreateDag") or compiles for minutes. Neither symptom
 * names the shader. This prints the name before each compile and the time after it.
 *
 * Usage:
 *   ShaderCompileProbe [scene.frac ...]
 *       the built-in fractals first (7-10 s each on the dev machine), then each .frac
 *       given, compiled by rendering one 64x36 frame at 1 spp.
 *   ShaderCompileProbe --render WxH spp outDir scene.frac ...
 *       skips the built-ins: each scene is rendered to outDir/<name>.png at that size
 *       and sample count, with its compile-plus-render time. A node graph or a custom
 *       shader compiles on load, so this is the quickest way to see one scene change:
 *       ~15 s instead of the two and a half minutes every other harness spends first.
 */
public class ShaderCompileProbe {

    public static void main(String[] args) throws Exception {
        boolean renderOnly = args.length > 0 && args[0].equals("--render");
        int W = 64, H = 36, samples = 1;
        File outDir = null;
        List<String> scenes = new ArrayList<>();
        if (renderOnly) {
            String[] res = args[1].split("x");
            W = Integer.parseInt(res[0]);
            H = Integer.parseInt(res[1]);
            samples = Integer.parseInt(args[2]);
            outDir = new File(args[3]);
            outDir.mkdirs();
            for (int i = 4; i < args.length; i++) scenes.add(args[i]);
        } else {
            for (String a : args) scenes.add(a);
        }

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        if (!renderOnly) {
            final long[] t0 = {System.nanoTime()};
            final String[] current = {null};
            System.out.println("=== built-in fractal shaders ===");
            controller.loadAllShaders((msg, p) -> {
                long now = System.nanoTime();
                if (current[0] != null) {
                    System.out.printf("  %-28s %6d ms%n", current[0], (now - t0[0]) / 1_000_000);
                }
                current[0] = msg;
                t0[0] = now;
                System.out.flush();
            });
        }

        if (!scenes.isEmpty()) {
            System.out.println("=== scenes ===");
            for (String path : scenes) {
                File f = new File(path);
                FractalConfig cfg = FractalConfigManager.load(f);
                FractalType type = cfg.getFractalTypeEnum();
                controller.setFractalType(type);
                AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
                cfg.applyTo(params);
                controller.updatePaletteTexture(params.getCustomGradient());
                if (cfg.postProcess != null) controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
                controller.setExportSize(W, H);
                System.out.printf("  compiling %s ...%n", f.getName());
                System.out.flush();
                long start = System.nanoTime();
                File png;
                if (renderOnly) {
                    png = new File(outDir, f.getName().replaceAll("\\.frac$", "") + ".png");
                } else {
                    png = File.createTempFile("shaderprobe", ".png");
                    png.deleteOnExit();
                }
                controller.exportToPNG(png, samples, pr -> {}, () -> false).get();
                System.out.printf("  %-28s %6d ms (compile + %dx%d at %d spp)%s%n", f.getName(),
                        (System.nanoTime() - start) / 1_000_000, W, H, samples,
                        renderOnly ? " -> " + png.getPath() : "");
                System.out.flush();
            }
        }
        System.exit(0);
    }
}
