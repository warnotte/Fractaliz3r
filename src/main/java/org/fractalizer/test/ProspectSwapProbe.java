package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.explore.ChainProspector;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Settings;
import org.fractalizer.explore.ControllerChainRenderer;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * The Discoveries tab searches on a throw-away scene while the user's scene waits, then
 * puts the user's scene back. This proves the swap leaves nothing behind: the scene is
 * rendered before the search, the search runs exactly as the browser runs it (a fresh
 * NodeGraphParams made current by the renderer's first setChain, the original marked
 * dirty and replaced afterwards), and the scene is rendered again. The two frames must be
 * identical pixel for pixel; the search must have set as many chains as structures asked
 * and found something.
 *
 * Why a probe and not a unit test: the failure it looks for lives in the GPU program
 * table (the node-graph program holding the last chain compiled when the user's scene
 * comes back), which no stub reproduces.
 *
 * Usage: -Dexec.args="[WxH] [structures] [outDir]"   e.g. "320x180 2 out/prospect_swap"
 * Exit code 1 on a mismatch.
 */
public class ProspectSwapProbe {

    public static void main(String[] args) throws Exception {
        String[] res = (args.length > 0 ? args[0] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int structures = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        File outDir = new File(args.length > 2 ? args[2] : "out/prospect_swap");
        outDir.mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        // The user's scene: a Mandelbulb through the node-graph pipeline, as the app has it.
        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.setFractalType(FractalType.MANDELBULB);
        AbstractFractalParams original = (AbstractFractalParams) controller.getParams();
        original.setPathTracingEnabled(false);
        controller.updatePaletteTexture(original.getCustomGradient());
        BufferedImage before = controller.renderStill(W, H, 4, () -> false);
        ImageIO.write(before, "png", new File(outDir, "before.png"));

        // The search, exactly as SceneBrowser.startProspecting runs it.
        NodeGraphParams search = new NodeGraphParams();
        HybridPresets.showcaseLook(search);
        int[] found = {0};
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { found[0]++; }
            @Override public void status(double p, String message) { System.out.println("  " + message); }
        };
        long t0 = System.nanoTime();
        ControllerChainRenderer renderer = new ControllerChainRenderer(controller, search, () -> false);
        ChainProspector.Result r = new ChainProspector(renderer, listener, () -> false)
                .prospect(new Settings(structures, 3, 4, 7L, 160, 90));
        boolean swappedIn = controller.getParams() == search;
        if (original instanceof NodeGraphParams ngp) ngp.markDirty();
        controller.replaceParams(original);
        controller.updatePaletteTexture(original.getCustomGradient());
        controller.restoreViewportSize();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        BufferedImage after = controller.renderStill(W, H, 4, () -> false);
        ImageIO.write(after, "png", new File(outDir, "after.png"));
        long differing = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (before.getRGB(x, y) != after.getRGB(x, y)) differing++;
        }

        System.out.printf("=== ProspectSwapProbe (%dx%d, %d structures) ===%n", W, H, structures);
        System.out.printf("search made its scene current: %s; %d structures compiled, %d found, in %d ms%n",
                swappedIn, r.structures(), found[0], ms);
        System.out.printf("user's scene back: %s; %d of %d pixels differ%n", controller.getParams() == original, differing, (long) W * H);
        boolean ok = swappedIn && controller.getParams() == original && differing == 0 && r.structures() == structures && found[0] > 0;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }
}
