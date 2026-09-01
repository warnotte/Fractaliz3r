package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.GradientPalette;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Finds where saturation is lost between the palette and the final image.
 *
 * The palette pipeline itself checks out — gradient stops sample into 256 RGB floats,
 * upload as RGB32F with linear filtering, and the shader reads them back with
 * texture(paletteTexture, vec2(fract(t), 0.5)). So if renders come out looking like one
 * washed hue, the colour is being lost downstream, and the render passes bracket exactly
 * where: ORBIT_TRAP shows the raw factors before any palette lookup, DIFFUSE shows
 * baseColor * NdotL — the palette colour with nothing added — and FINAL shows what the
 * full shading and post-processing chain does to it.
 *
 * A deliberately hard red/green/blue gradient is forced on, so anything less than vivid
 * output in a given pass is that pass destroying saturation, not a subtle scene palette.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac|TYPE&gt; &lt;outDir&gt; &lt;WxH&gt; &lt;samples&gt;"
 */
public class ColorProbe {

    /** Fully saturated primaries with no white and no black: any desaturation in the
     *  output is introduced by the pipeline, not inherited from the gradient. */
    static GradientPalette hardGradient() {
        // getStops() is unmodifiable and removeStop refuses to go below two, so the
        // gradient is built from the list constructor instead.
        return new GradientPalette(java.util.List.of(
            new GradientPalette.ColorStop(0.00, Color.rgb(255, 0, 0)),
            new GradientPalette.ColorStop(0.33, Color.rgb(0, 255, 0)),
            new GradientPalette.ColorStop(0.66, Color.rgb(0, 0, 255)),
            new GradientPalette.ColorStop(1.00, Color.rgb(255, 0, 0))));
    }

    record Stats(double sat, double r, double g, double b, double lum) {}

    /** Mean HSV saturation and mean channels over surface pixels only. */
    static Stats measure(File rgb, File depth) throws Exception {
        BufferedImage im = ImageIO.read(rgb);
        BufferedImage dp = ImageIO.read(depth);
        double sat = 0, sr = 0, sg = 0, sb = 0, sl = 0;
        long n = 0;
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                if (dp.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue;
                int p = im.getRGB(x, y);
                double r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                sat += (mx < 1e-6) ? 0 : (mx - mn) / mx;
                sr += r; sg += g; sb += b;
                sl += 0.299 * r + 0.587 * g + 0.114 * b;
                n++;
            }
        }
        if (n == 0) return new Stats(0, 0, 0, 0, 0);
        return new Stats(sat / n, sr / n, sg / n, sb / n, sl / n);
    }

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBULB";
        String outDir = args.length > 1 ? args[1] : "colorprobe";
        String[] res = (args.length > 2 ? args[2] : "480x270").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 12;

        new File(outDir).mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        AbstractFractalParams params;
        if (spec.toLowerCase().endsWith(".frac")) {
            FractalConfig cfg = FractalConfigManager.load(new File(spec));
            controller.setFractalType(cfg.getFractalTypeEnum());
            params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
        } else {
            controller.setFractalType(FractalType.valueOf(spec));
            params = (AbstractFractalParams) controller.getParams();
        }

        params.setCustomGradient(hardGradient());
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== ColorProbe: %s (%dx%d, %d spp) ===%n", spec, W, H, samples);
        System.out.println("gradient forced to pure red / green / blue");
        System.out.printf("%-28s %6s %7s %7s %7s %7s%n", "pass", "sat", "R", "G", "B", "lum");

        // pass name -> {renderMode, pathTracing, specular, rimStrength...}
        Map<String, Runnable> passes = new LinkedHashMap<>();
        float specBase = params.getSpecularIntensity();
        float ambBase = params.getAmbientIntensity();

        passes.put("ORBIT_TRAP (raw factors)", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_ORBIT_TRAP);
        });
        passes.put("DIFFUSE (palette * NdotL)", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_DIFFUSE);
        });
        passes.put("SPECULAR", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_SPECULAR);
        });
        passes.put("FINAL classic", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
            params.setSpecularIntensity(specBase);
            params.setAmbientIntensity(ambBase);
        });
        passes.put("FINAL classic, no specular", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
            params.setSpecularIntensity(0f);
        });
        passes.put("FINAL classic, no spec+amb", () -> {
            params.setPathTracingEnabled(false);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
            params.setSpecularIntensity(0f);
            params.setAmbientIntensity(0f);
        });
        passes.put("FINAL path traced", () -> {
            params.setPathTracingEnabled(true);
            params.setRenderMode(AbstractFractalParams.RENDER_FINAL);
            params.setSpecularIntensity(specBase);
            params.setAmbientIntensity(ambBase);
        });

        int i = 0;
        for (var e : passes.entrySet()) {
            e.getValue().run();
            String tag = String.format(Locale.ROOT, "p%d", i++);
            controller.setExportSize(W, H);
            File rgb = new File(outDir, tag + ".png");
            controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
            controller.setExportSize(W, H);
            File dep = new File(outDir, tag + "_d.png");
            controller.exportAOV(dep, 2);
            Stats s = measure(rgb, dep);
            System.out.printf(Locale.ROOT, "%-28s %6.3f %7.1f %7.1f %7.1f %7.1f%n",
                    e.getKey(), s.sat(), s.r(), s.g(), s.b(), s.lum());
            System.out.flush();
        }

        System.out.println();
        System.out.println("images -> " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }
}
