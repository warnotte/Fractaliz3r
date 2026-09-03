package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Proves that the cheap interactive preview cannot leak into an export.
 *
 * The preview renders at a fraction of the viewport and overrides path tracing, DoF, step
 * counts and the deep-zoom LOD. Both are things an export must never inherit: the resize
 * leaves the engine at preview size, and the overrides would quietly downgrade the output.
 * Export paths resize explicitly and rebuild their uniforms from the scene, so neither
 * should carry over — this exports the same scene cold and again straight after a preview
 * and compares the two pixel for pixel.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac&gt; &lt;WxH&gt; &lt;samples&gt;"
 */
public class ExportAfterPreviewProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "presets/JULIA_BULB_OVERVIEW.frac";
        String[] res = (args.length > 1 ? args[1] : "640x360").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        File outDir = new File("out/export_probe");
        outDir.mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== ExportAfterPreviewProbe: %s, export %dx%d ===%n", spec, W, H);

        // 1. Cold export, nothing has touched the engine size.
        controller.setExportSize(W, H);
        File cold = new File(outDir, "cold.png");
        controller.exportToPNG(cold, samples, p -> {}, () -> false).get();

        // 2. Run a preview so the engine is left small and the fast overrides have been
        //    used, then export again with no intervening reset.
        params.setPreviewScale(0.35f);
        params.setPreviewFastShading(true);
        controller.setViewportSize(1280, 720);
        CountDownLatch previewed = new CountDownLatch(1);
        controller.renderPreview(img -> previewed.countDown(), p -> {});
        previewed.await(30, java.util.concurrent.TimeUnit.SECONDS);
        controller.cancelRender();
        System.out.printf("  preview ran at %dx%d (engine now %dx%d)%n",
                Math.round(1280 * 0.35f), Math.round(720 * 0.35f),
                controller.getEngine().getWidth(), controller.getEngine().getHeight());

        controller.setExportSize(W, H);
        File after = new File(outDir, "after_preview.png");
        controller.exportToPNG(after, samples, p -> {}, () -> false).get();

        // 3. Compare.
        BufferedImage a = ImageIO.read(cold), b = ImageIO.read(after);
        System.out.printf("  cold export          %dx%d%n", a.getWidth(), a.getHeight());
        System.out.printf("  export after preview %dx%d%n", b.getWidth(), b.getHeight());

        boolean sizeOk = a.getWidth() == W && a.getHeight() == H
                      && b.getWidth() == W && b.getHeight() == H;
        long differing = 0;
        int max = 0;
        if (a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight()) {
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                    if (pa == pb) continue;
                    differing++;
                    for (int sh : new int[]{16, 8, 0}) {
                        max = Math.max(max, Math.abs(((pa >> sh) & 0xFF) - ((pb >> sh) & 0xFF)));
                    }
                }
            }
        }
        double pct = 100.0 * differing / (a.getWidth() * (double) a.getHeight());
        System.out.printf(Locale.ROOT, "  differing pixels     %.4f%%  (max channel delta %d)%n", pct, max);
        System.out.println(sizeOk && differing == 0
                ? "  RESULT: identical — the preview does not leak into an export"
                : "  RESULT: MISMATCH — the preview state is reaching the export");
        System.exit(sizeOk && differing == 0 ? 0 : 1);
    }
}
