package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.GradientPalette;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Renders one scene under every coloring mode, as a labelled contact sheet.
 *
 * The point is which modes can put more than one hue on a single object. Modes 0-5 build
 * their lookup from orbit traps, whose field varies faster than a pixel: sweep the palette
 * wide enough to cross several hues and neighbouring positions average to grey inside a
 * pixel, so those modes give one hue whatever gradient they are handed. Modes 9-12 read
 * geometry instead — surface orientation, world position, curvature, view angle — which
 * varies slowly and in large regions, so a multi-hue gradient survives.
 *
 * Usage:
 *   -Dexec.args="&lt;file.frac&gt; &lt;outDir&gt; &lt;WxH&gt; &lt;samples&gt; [colorStrength]"
 */
public class ColorDemo {

    static final String[] MODE_NAMES = {
        "0 Standard", "1 Bands", "2 Distance", "3 Angular", "4 Blend", "5 Contour",
        "6 HSV Direct", "7 Dual Palette", "8 Posterize",
        "9 Normal Map", "10 Triplanar", "11 Curvature", "12 Fresnel"
    };

    /** A ramp that actually crosses hues, so a mode capable of showing more than one does. */
    static GradientPalette spectrum() {
        return new GradientPalette(List.of(
            new GradientPalette.ColorStop(0.00, Color.rgb(20, 30, 120)),
            new GradientPalette.ColorStop(0.25, Color.rgb(20, 170, 190)),
            new GradientPalette.ColorStop(0.50, Color.rgb(240, 200, 60)),
            new GradientPalette.ColorStop(0.75, Color.rgb(220, 60, 60)),
            new GradientPalette.ColorStop(1.00, Color.rgb(150, 40, 170))));
    }

    /** Mean HSV saturation and hue spread over surface pixels: a mode that puts several
     *  hues on the object has a wide hue histogram, one that does not has a narrow one. */
    static double[] hueStats(File rgb, File depth) throws Exception {
        BufferedImage im = ImageIO.read(rgb);
        BufferedImage dp = ImageIO.read(depth);
        int[] hist = new int[36];
        double sat = 0;
        long n = 0;
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                if (dp.getRaster().getSample(x, y, 0) / 65535.0 <= 0.02) continue;
                int p = im.getRGB(x, y);
                float[] hsb = java.awt.Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, null);
                if (hsb[1] < 0.15) continue;            // grey pixels carry no hue
                hist[Math.min(35, (int) (hsb[0] * 36))]++;
                sat += hsb[1];
                n++;
            }
        }
        if (n == 0) return new double[]{0, 0};
        // How many 10-degree hue buckets hold at least 2% of the coloured pixels.
        int spread = 0;
        for (int h : hist) if (h > n * 0.02) spread++;
        return new double[]{sat / n, spread};
    }

    public static void main(String[] args) throws Exception {
        String spec = args[0];
        String outDir = args.length > 1 ? args[1] : "colordemo";
        String[] res = (args.length > 2 ? args[2] : "480x270").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 3 ? Integer.parseInt(args[3]) : 24;
        float strength = args.length > 4 ? Float.parseFloat(args[4]) : 1.0f;

        new File(outDir).mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        FractalConfig cfg = FractalConfigManager.load(new File(spec));
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        params.setCustomGradient(spectrum());
        params.setColorStrength(strength);
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== ColorDemo: %s (%dx%d, %d spp, colorStrength %.1f) ===%n",
                spec, W, H, samples, strength);
        System.out.printf("%-16s %8s %8s%n", "mode", "sat", "hues");

        BufferedImage[] tiles = new BufferedImage[MODE_NAMES.length];
        for (int mode = 0; mode < MODE_NAMES.length; mode++) {
            params.setColoringMode(mode);
            controller.setExportSize(W, H);
            File rgb = new File(outDir, String.format(Locale.ROOT, "mode%02d.png", mode));
            controller.exportToPNG(rgb, samples, p -> {}, () -> false).get();
            controller.setExportSize(W, H);
            File dep = new File(outDir, "d.png");
            controller.exportAOV(dep, 2);
            double[] st = hueStats(rgb, dep);
            System.out.printf(Locale.ROOT, "%-16s %8.3f %8.0f%n", MODE_NAMES[mode], st[0], st[1]);
            System.out.flush();
            tiles[mode] = ImageIO.read(rgb);
        }
        new File(outDir, "d.png").delete();

        int cols = 4, rows = (tiles.length + cols - 1) / cols, label = 22;
        BufferedImage sheet = new BufferedImage(W * cols, (H + label) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new java.awt.Color(18, 18, 18));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        for (int i = 0; i < tiles.length; i++) {
            int x = (i % cols) * W, y = (i / cols) * (H + label);
            g.drawImage(tiles[i], x, y + label, null);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(MODE_NAMES[i], x + 6, y + 16);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(outDir, "_sheet.png"));

        System.out.println();
        System.out.println("sheet -> " + new File(outDir, "_sheet.png").getAbsolutePath());
        System.exit(0);
    }
}
