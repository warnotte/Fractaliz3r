package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * One light transport (BIDIR): does the path tracer alone and the path tracer with the
 * photon pass, weighted against each other, converge to the same image.
 *
 * <p>Scene "panel": a glass ball on a matte slab under an emissive panel, no sun. The path
 * tracer alone reaches every path of it, the caustic under the ball by chance; with BIDIR
 * the photon pass makes the same paths from the other end and both are weighted by MIS,
 * so the converged images must agree (block means) and the noise must fall. Scene "beam":
 * the beam across the slab onto a block; the photons' direct landings weigh next to
 * nothing against the draw, and the images must agree.
 *
 * Usage: BidirProbe [panel | metal | beam | window | scene.frac] [outDir] [WxH] [referenceSamples] [testSamples]
 */
public class BidirProbe {

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "panel";
        // a scene given as a path names its images by its file name (a path made a directory of it, and the save died after the render)
        String tag = which.endsWith(".frac") ? new File(which).getName().replace(".frac", "") : which;
        File outDir = new File(args.length > 1 ? args[1] : "out/bidir");
        String[] res = (args.length > 2 ? args[2] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int refSamples = args.length > 3 ? Integer.parseInt(args[3]) : 2048;
        int testSamples = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(W, H);
        GLSLEngine engine = controller.getEngine();

        FractalConfig cfg;
        boolean noSun = false;
        switch (which) {
            case "panel" -> {
                cfg = SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.union(SceneBuilder.slab(2.6f), panel()), SceneBuilder.glassBall(0.45f, 1.5f, 0.02f)))
                        .camera(0f, 2.0f, -3.4f).lookAt(0f, 0.3f, 0f).fov(45)
                        .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(6)
                        .lightIntensity(0f).ambientIntensity(0f)
                        .caustics(3.0f).causticPhotons(512)
                        .build();
                noSun = true;
            }
            case "metal" -> {
                // a smooth metal block under the panel: its reflection of the panel onto the slab is
                // made by the path tracer's draw at the metal and by the photons alike, weighted
                org.fractalizer.graph.PrimitiveNode block = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
                block.setSizeX(0.35f); block.setSizeY(0.45f); block.setSizeZ(0.35f);
                org.fractalizer.graph.MaterialNode m = new org.fractalizer.graph.MaterialNode(SceneBuilder.translate(SceneBuilder.rotate(block, 0f, 30f, 0f), -0.4f, 0.45f, 0.2f));
                m.setMaterialType(1); m.setMetallic(1f); m.setRoughness(0.15f);
                m.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID); m.setColorR(0.9f); m.setColorG(0.8f); m.setColorB(0.6f);
                cfg = SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.union(SceneBuilder.slab(2.6f), panel()), m))
                        .camera(0f, 2.0f, -3.4f).lookAt(0f, 0.3f, 0f).fov(45)
                        .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(6)
                        .lightIntensity(0f).ambientIntensity(0f)
                        .caustics(3.0f).causticPhotons(512)
                        .build();
                noSun = true;
            }
            case "window" -> {
                // the panel scene seen through a pane of glass: the caustic under the ball is then
                // a path with glass on both sides of the floor, which only the gather makes; the
                // path tracer alone finds it by chance through the ball, slowly
                org.fractalizer.graph.PrimitiveNode pane = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
                pane.setSizeX(3.5f); pane.setSizeY(2.5f); pane.setSizeZ(0.02f);
                org.fractalizer.graph.MaterialNode glass = new org.fractalizer.graph.MaterialNode(SceneBuilder.translate(pane, 0f, 1.0f, -1.6f));
                glass.setMaterialType(2); glass.setIor(1.5f); glass.setRoughness(0.02f); glass.setMetallic(0f);
                glass.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID); glass.setColorR(1f); glass.setColorG(1f); glass.setColorB(1f);
                cfg = SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.union(SceneBuilder.union(SceneBuilder.slab(2.6f), panel()), SceneBuilder.glassBall(0.45f, 1.5f, 0.02f)), glass))
                        .camera(0f, 2.0f, -3.4f).lookAt(0f, 0.3f, 0f).fov(45)
                        .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(8)
                        .lightIntensity(0f).ambientIntensity(0f)
                        .caustics(3.0f).causticPhotons(512)
                        .build();
                noSun = true;
            }
            case "beam" -> {
                cfg = SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.slab(2.6f), PresetForge.matteBlock()))
                        .camera(0f, 1.6f, -3.2f).lookAt(0f, 0.3f, 0f).fov(42)
                        .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(4)
                        .lightIntensity(0f).ambientIntensity(0f)
                        .beam(-2.5f, 0.6f, 0.0f, 1.0f, -0.15f, 0.0f, 0.12f, 6.0f, 1.0f, 0.97f, 0.9f, 12.0f)
                        .caustics(3.0f).causticPhotons(512)
                        .build();
                noSun = true;
            }
            default -> cfg = org.fractalizer.config.FractalConfigManager.load(new File(which));
        }
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        params.setPathTracingEnabled(true);
        params.setAdaptiveSampling(false);
        if (noSun) { params.setSkyIntensity(0f); params.setAmbientIntensity(0f); params.setLightIntensity(0f); }
        engine.resize(W, H);

        System.out.printf("=== BidirProbe: %s (%dx%d, reference %d spp, test %d spp) ===%n", which, W, H, refSamples, testSamples);

        params.setCausticsEnabled(false);
        Map<String, Object> uPt = controller.buildUniformsForProbe();
        float[] ptRef = render(engine, uPt, refSamples);
        double msPt = timePerSample(engine, uPt, 16);
        float[] ptTest = render(engine, uPt, testSamples);

        params.setCausticsEnabled(true);
        Map<String, Object> uBd = controller.buildUniformsForProbe();
        boolean photonsOn = engine.getCausticPhotons() > 0;
        if (!photonsOn) System.out.println("the photon program is not active (a compile error above?)");
        float[] bdRef = render(engine, uBd, refSamples);
        double msBd = timePerSample(engine, uBd, 16);
        float[] bdTest = render(engine, uBd, testSamples);

        for (int band = 0; band < 6; band++) {
            double a = 0, b = 0; int n = 0;
            for (int y = band * H / 6; y < (band + 1) * H / 6; y++) for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a += ptRef[i] + ptRef[i + 1] + ptRef[i + 2];
                b += bdRef[i] + bdRef[i + 1] + bdRef[i + 2]; n++;
            }
            System.out.printf("  band %d: path tracer %.4f, with photons %.4f (%+.2f%%)%n", band, a / n, b / n, 100.0 * (b - a) / Math.max(a, 1e-9));
        }
        int bw = W / 8, bh = H / 8;
        double blockDiff = 0, blockSum = 0;
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            double a = 0, b = 0;
            for (int y = by * 8; y < by * 8 + 8; y++) for (int x = bx * 8; x < bx * 8 + 8; x++) {
                int i = (y * W + x) * 4;
                a += ptRef[i] + ptRef[i + 1] + ptRef[i + 2];
                b += bdRef[i] + bdRef[i + 1] + bdRef[i + 2];
            }
            blockDiff += Math.abs(a - b); blockSum += a;
        }
        double rel = blockDiff / Math.max(blockSum, 1e-9);
        double meanPt = mean(ptRef), meanBd = mean(bdRef);
        System.out.printf("converged mean: path tracer %.4f, with photons %.4f (%+.2f%%); block-mean difference %.2f%% of the image%n",
                meanPt, meanBd, 100.0 * (meanBd - meanPt) / Math.max(meanPt, 1e-9), 100.0 * rel);
        double rmsePt = rmse(ptTest, ptRef), rmseBd = rmse(bdTest, ptRef);
        System.out.printf("noise at %d spp (RMSE vs the path tracer's reference): path tracer %.4f, with photons %.4f, ratio %.2fx%n",
                testSamples, rmsePt, rmseBd, rmsePt / Math.max(rmseBd, 1e-9));
        System.out.printf("cost: %.1f ms per sample path tracer, %.1f with photons%n", msPt, msBd);

        savePng(ptRef, W, H, new File(outDir, tag + "_pt_ref.png"));
        savePng(bdRef, W, H, new File(outDir, tag + "_bidir_ref.png"));
        savePng(ptTest, W, H, new File(outDir, tag + "_pt_" + testSamples + ".png"));
        savePng(bdTest, W, H, new File(outDir, tag + "_bidir_" + testSamples + ".png"));
        BufferedImage sheet = new BufferedImage(W * 2 + 4, H * 2 + 4, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        String[] names = {tag + "_pt_ref", tag + "_bidir_ref", tag + "_pt_" + testSamples, tag + "_bidir_" + testSamples};
        for (int i = 0; i < 4; i++) g.drawImage(javax.imageio.ImageIO.read(new File(outDir, names[i] + ".png")), (i % 2) * (W + 4), (i / 2) * (H + 4), null);
        g.dispose();
        javax.imageio.ImageIO.write(sheet, "png", new File(outDir, "_" + tag + "_sheet.png"));

        boolean ok = photonsOn && rel < (which.equals("window") ? 0.08 : 0.04);   // the window's reference is the slowest to converge
        System.out.println(ok ? "BIDIR OK" : "BIDIR MISMATCH");
        System.out.println("DONE -> " + outDir.getAbsolutePath());
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    /** A dim emissive panel over the slab, off-centre. */
    static org.fractalizer.graph.GraphNode panel() {
        org.fractalizer.graph.PrimitiveNode p = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        p.setSizeX(0.6f); p.setSizeY(0.02f); p.setSizeZ(0.6f);
        org.fractalizer.graph.MaterialNode m = new org.fractalizer.graph.MaterialNode(SceneBuilder.translate(p, 0.6f, 1.9f, 0.3f));
        m.setMaterialType(0);
        m.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID);
        m.setColorR(1f); m.setColorG(0.96f); m.setColorB(0.9f);
        m.setEmission(3.0f);
        return m;
    }

    static float[] render(GLSLEngine engine, Map<String, Object> uniforms, int samples) {
        engine.resetAccumulation();
        engine.renderSamples(uniforms, samples);
        return engine.readRawImage();
    }

    static double timePerSample(GLSLEngine engine, Map<String, Object> uniforms, int samples) {
        engine.resetAccumulation();
        engine.renderSamples(uniforms, 2);
        engine.glSync();
        long t0 = System.nanoTime();
        engine.renderSamples(uniforms, samples);
        engine.glSync();
        return (System.nanoTime() - t0) / 1e6 / samples;
    }

    static double mean(float[] rgba) {
        double s = 0; int n = 0;
        for (int i = 0; i < rgba.length; i += 4) { s += rgba[i] + rgba[i + 1] + rgba[i + 2]; n++; }
        return s / n;
    }

    static double rmse(float[] a, float[] ref) {
        double s = 0; int n = 0;
        for (int i = 0; i < a.length; i += 4) {
            for (int c = 0; c < 3; c++) { double d = Math.min(a[i + c], 4f) - Math.min(ref[i + c], 4f); s += d * d; }
            n += 3;
        }
        return Math.sqrt(s / n);
    }

    static void savePng(float[] rgba, int w, int h, File file) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            int r = (int) (255 * Math.pow(Math.min(1, Math.max(0, rgba[i])), 1 / 2.2));
            int g = (int) (255 * Math.pow(Math.min(1, Math.max(0, rgba[i + 1])), 1 / 2.2));
            int b = (int) (255 * Math.pow(Math.min(1, Math.max(0, rgba[i + 2])), 1 / 2.2));
            img.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        javax.imageio.ImageIO.write(img, "png", file);
    }
}
