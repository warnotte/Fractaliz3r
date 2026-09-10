package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * The Droste post-process, rendered so it can be judged.
 *
 * Renders a preset once without the effect and then with it under a few settings (inner
 * radius, twist, phase), all from the same accumulation, to out/droste/. The effect is a
 * remap of the final frame: the same samples, read at Escher's spiral of the annulus.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DrosteProbe" \
 *       -Dexec.args="presets/JULIA_BULB_OVERVIEW.frac out/droste 960x540 32"
 */
public class DrosteProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        File outDir = new File(args.length > 1 ? args[1] : "out/droste");
        String[] res = (args.length > 2 ? args[2] : "960x540").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 32;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        GLSLEngine.PostProcessParams pp = controller.getEngine().getPostProcessParams();
        if (cfg.postProcess != null) pp.copyFrom(cfg.postProcess);
        controller.setExportSize(w, h);

        String base = new File(spec).getName().replaceAll("\\.frac$", "");
        System.out.printf("=== DrosteProbe: %s at %dx%d, %d samples -> %s ===%n", spec, w, h, samples, outDir);

        // the frame once; every Droste image below is a re-read of the same accumulation
        pp.drosteEnabled = false;
        controller.exportToPNG(new File(outDir, base + "_00_off.png"), samples, p -> {}, () -> false).get();

        float[][] settings = {
                // inner, outer, twist, phase
                {0.30f, 1.00f, 1f, 0.0f},
                {0.30f, 1.00f, 1f, 0.5f},
                {0.15f, 1.00f, 1f, 0.0f},
                {0.30f, 1.00f, 2f, 0.0f},
                {0.30f, 1.00f, 0f, 0.0f},
                {0.45f, 0.90f, -1f, 0.0f},
        };
        int i = 1;
        for (float[] s : settings) {
            pp.drosteEnabled = true;
            pp.drosteInner = s[0]; pp.drosteOuter = s[1]; pp.drostePeriodicity = s[2]; pp.drostePhase = s[3];
            String name = String.format("%s_%02d_in%.2f_out%.2f_twist%+.0f_phase%.2f.png", base, i++, s[0], s[1], s[2], s[3]);
            // the accumulation is already there: reread it through the post-process only
            controller.rereadExport(new File(outDir, name));
            System.out.println("  " + name);
        }
        pp.drosteEnabled = false;
        controller.close();
        Platform.exit();
        System.exit(0);
    }
}
