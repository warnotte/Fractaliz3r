package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.explore.ChainProspector;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Result;
import org.fractalizer.explore.ChainProspector.Settings;
import org.fractalizer.explore.ControllerChainRenderer;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The command line over {@link ChainProspector}: autonomous discovery of new fractals in
 * hybrid-chain space, with a labelled contact sheet, a ranking file and the best finds
 * written as presets. The search itself (recipes, draws, framing, scoring, novelty) lives
 * in the explore package, where the app's Discoveries tab runs the same code on the same
 * GPU controller; this harness is how a change to it is measured before it ships.
 *
 * Usage:
 *   -Dexec.args="&lt;outDir&gt; &lt;structures&gt; &lt;drawsPerStructure&gt; &lt;WxH&gt; &lt;samples&gt; [keep] [seed]"
 *   -Dexec.args="out/prospect_hybrid 20 8 320x180 6 8 1"
 * Output under outDir: one PNG per candidate that rendered, _top.png (the twelve best as a
 * labelled sheet, two per structure at most, known chains in orange), ranking.txt, and
 * FOUND_NN.frac for the best `keep` new ones, in the showcase look with the camera the
 * search settled on. A structure costs a shader compile (~12 s here, once per machine:
 * the driver caches it); a draw costs milliseconds, so 20 x 8 is about four minutes.
 * No loadAllShaders: a node graph compiles its own program on the first render.
 */
public class HybridProspector {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "out/prospect_hybrid";
        int nStructures = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int draws = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        String[] res = (args.length > 3 ? args[3] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 6;
        int keep = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        long seed = args.length > 6 ? Long.parseLong(args[6]) : 1L;
        new File(outDir).mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.setFractalType(FractalType.NODE_GRAPH);
        NodeGraphParams params = (NodeGraphParams) controller.getParams();
        HybridPresets.showcaseLook(params);
        controller.updatePaletteTexture(params.getCustomGradient());

        System.out.printf("=== Hybrid prospector: %d structures x %d draws, %dx%d, %d spp, seed %d ===%n",
                nStructures, draws, W, H, samples, seed);
        long tAll = System.nanoTime();
        final int[] lastStructure = {-1};
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) {
                if (d.structureIndex() != lastStructure[0]) {
                    lastStructure[0] = d.structureIndex();
                    System.out.printf("%n[%02d] %-8s %s%s%n", d.structureIndex(), d.recipe(), d.label(),
                            d.known() != null ? "  (known: " + d.known() + ")" : "");
                }
                String tag = tag(d);
                try {
                    ImageIO.write(d.thumbnail(), "png", new File(outDir, tag + ".png"));
                } catch (Exception e) {
                    System.err.println("could not write " + tag + ": " + e.getMessage());
                }
                System.out.printf(Locale.ROOT, "     %s  score %8.0f  detail %8.0f  cov %3.0f%%  solid %.2f  d=%.2f  %s%n",
                        tag, d.score(), d.frame().detail(), 100 * d.frame().coverage(), d.solidity(), length(d.eye()), d.look().name());
                System.out.flush();
            }
            @Override public void status(double p, String message) { }
        };
        ControllerChainRenderer renderer = new ControllerChainRenderer(controller, params, () -> false);
        Result r = new ChainProspector(renderer, listener, () -> false)
                .prospect(new Settings(nStructures, draws, samples, seed, W, H));

        double totalS = (System.nanoTime() - tAll) / 1e9;
        System.out.printf(Locale.ROOT, "%n%d candidates rendered, %d empty, %d solid, %d flat, %d cut by the frame, in %.0f s (%.0f s of it compiling %d structures)%n",
                r.discoveries().size(), r.empty(), r.solid(), r.flat(), r.cut(), totalS, r.compileNanos() / 1e9, r.structures());

        List<Discovery> scored = new ArrayList<>(r.discoveries());
        scored.sort(Comparator.comparingDouble(Discovery::score).reversed());
        List<Discovery> diverse = ChainProspector.diverse(scored, 2);

        // Which recipes pay: structures drawn, candidates that rendered, share of the top twelve.
        Map<String, Integer> top = new HashMap<>();
        for (int i = 0; i < Math.min(12, diverse.size()); i++) top.merge(diverse.get(i).recipe(), 1, Integer::sum);
        System.out.printf("%n%-8s %10s %10s %6s%n", "recipe", "structures", "rendered", "top12");
        for (var e : r.perRecipe().entrySet()) {
            System.out.printf("%-8s %10d %10d %6d%n", e.getKey(), e.getValue()[0], e.getValue()[1], top.getOrDefault(e.getKey(), 0));
        }

        try (PrintWriter pw = new PrintWriter(new File(outDir, "ranking.txt"))) {
            pw.println("# rank tag score detail coverage solidity known chain | look");
            for (int i = 0; i < scored.size(); i++) {
                Discovery d = scored.get(i);
                pw.printf(Locale.ROOT, "%3d %s %10.0f %10.0f %5.2f %5.2f %-16s %s | %s%n", i + 1, tag(d), d.score(),
                        d.frame().detail(), d.frame().coverage(), d.solidity(), d.known() == null ? "-" : d.known(), d.label(), d.look().name());
            }
        }

        System.out.println();
        System.out.println("top 12 (at most two per structure):");
        for (int i = 0; i < Math.min(12, diverse.size()); i++) {
            Discovery d = diverse.get(i);
            System.out.printf(Locale.ROOT, "%3d %-8s %8.0f  solid %.2f  %s%s%n", i + 1, tag(d), d.score(), d.solidity(), d.label(),
                    d.known() != null ? "   [known: " + d.known() + "]" : "");
        }

        int sheetN = Math.min(12, diverse.size());
        if (sheetN > 0) {
            int cols = 4, rows = (sheetN + cols - 1) / cols;
            BufferedImage sheet = new BufferedImage(W * cols, H * rows, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, H / 14)));
            for (int i = 0; i < sheetN; i++) {
                Discovery d = diverse.get(i);
                int x = (i % cols) * W, y = (i / cols) * H;
                g.drawImage(d.thumbnail(), x, y, null);
                String label = String.format(Locale.ROOT, "%d  %s", i + 1, d.label());
                if (label.length() > 46) label = label.substring(0, 45) + "…";
                int fh = g.getFontMetrics().getHeight();
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRect(x, y + H - fh - 4, W, fh + 4);
                g.setColor(d.known() != null ? new Color(255, 200, 120) : Color.WHITE);
                g.drawString(label, x + 4, y + H - 6);
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(outDir, "_top.png"));
        }

        int written = 0;
        for (int i = 0; i < diverse.size() && written < keep; i++) {
            Discovery d = diverse.get(i);
            if (d.known() != null) continue;
            File f = new File(outDir, String.format(Locale.ROOT, "FOUND_%02d.frac", ++written));
            FractalConfigManager.save(FractalConfig.fromParams(ChainProspector.toParams(d)), f);
        }
        System.out.println();
        System.out.println("contact sheet -> " + new File(outDir, "_top.png").getAbsolutePath());
        System.out.println("presets       -> " + written + " FOUND_NN.frac in " + new File(outDir).getAbsolutePath());
        System.exit(0);
    }

    private static final Map<Discovery, String> TAGS = new HashMap<>();
    private static final Map<Integer, Integer> PER_STRUCTURE = new HashMap<>();

    /** sNN_MM: structure index and the rank of this find within it, in search order. */
    private static String tag(Discovery d) {
        return TAGS.computeIfAbsent(d, k -> {
            int n = PER_STRUCTURE.merge(k.structureIndex(), 1, Integer::sum) - 1;
            return String.format(Locale.ROOT, "s%02d_%02d", k.structureIndex(), n);
        });
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
