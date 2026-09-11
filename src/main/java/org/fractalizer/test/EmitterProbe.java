package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.LightList;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Emitter sampling (lights.glsl, HAS_EMITTERS): does drawing points on the node graph's
 * emissive primitives change the converged image, and how much noise does it remove.
 *
 * <p>The scene is rendered twice at a high sample count: emitters found by chance only, as
 * before the light list, and emitters sampled directly with the hit weighted by MIS. The
 * two must converge to the same image (the mean over 8x8 blocks, compared block by block).
 * Then both at a low sample count against the sampled reference: the RMSE ratio is the
 * noise the sampling removes. Also the time per sample each way.
 *
 * <p>The verdict comes from the built-in "slab" scene (a matte slab and a box under a dim
 * panel, nothing reaches the firefly clamp); any .frac is rendered for its pictures, noise
 * ratio and cost, without a verdict, because a bright emitter found by chance is clamped
 * far more than one drawn, and the two references legitimately differ.
 *
 * Usage: EmitterProbe [slab | scene.frac] [outDir] [WxH] [referenceSamples] [testSamples]
 */
public class EmitterProbe {

    public static void main(String[] args) throws Exception {
        File frac = new File(args.length > 0 ? args[0] : "presets/NODE_CORNEL.frac");
        File outDir = new File(args.length > 1 ? args[1] : "out/emitter");
        String[] res = (args.length > 2 ? args[2] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int refSamples = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
        int testSamples = args.length > 4 ? Integer.parseInt(args[4]) : 32;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(W, H);
        GLSLEngine engine = controller.getEngine();

        // "slab": a matte slab and a box under a dim, large panel, no sun: no contribution
        // reaches the firefly clamp, so the two estimators must agree tightly.
        FractalConfig cfg = frac.getName().equals("slab") ? slabScene() : FractalConfigManager.load(frac);
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        params.setPathTracingEnabled(true);
        params.setAdaptiveSampling(false);
        if (frac.getName().equals("slab")) { params.setSkyIntensity(0f); params.setAmbientIntensity(0f); params.setLightIntensity(0f); }
        engine.resize(W, H);

        System.out.printf("=== EmitterProbe: %s (%dx%d, reference %d spp, test %d spp) ===%n", frac.getName(), W, H, refSamples, testSamples);

        // by chance, as before the list
        LightList.emittersDisabled = true;
        Map<String, Object> uChance = controller.buildUniformsForProbe();
        float[] chanceRef = render(engine, uChance, refSamples);
        double msChance = timePerSample(engine, uChance, 16);
        float[] chanceTest = render(engine, uChance, testSamples);

        // sampled
        LightList.emittersDisabled = false;
        Map<String, Object> uSampled = controller.buildUniformsForProbe();
        int lights = (Integer) uSampled.getOrDefault("lightCount", 0);
        {
            LightList.Table t = LightList.emitters(((org.fractalizer.fractals.NodeGraphParams) controller.getParams()).getGraphRoot());
            for (int i = 0; i < t.count(); i++) {
                float[] e = java.util.Arrays.copyOfRange(t.data(), i * LightList.FLOATS, (i + 1) * LightList.FLOATS);
                System.out.printf("  light %d: type %.0f centre (%.2f %.2f %.2f) colour (%.2f %.2f %.2f) x %.2f quat (%.2f %.2f %.2f %.2f) size (%.2f %.2f %.2f) power %.2f matId %.0f area %.2f%n",
                        i, e[0], e[1], e[2], e[3], e[7], e[8], e[9], e[10], e[11], e[12], e[13], e[14], e[15], e[16], e[17], e[18], e[19], e[20]);
            }
        }
        float[] sampledRef = render(engine, uSampled, refSamples);
        {   // the two terms alone, unweighted: each must match the by-chance image on its own
            Map<String, Object> u1 = new java.util.HashMap<>(uSampled); u1.put("emitterDebug", 1);
            Map<String, Object> u2 = new java.util.HashMap<>(uSampled); u2.put("emitterDebug", 2);
            float[] neeOnly = render(engine, u1, refSamples / 4);
            float[] hitsOnly = render(engine, u2, refSamples / 4);
            System.out.printf("terms alone: by chance %.4f, direct draw alone %.4f, hits alone %.4f%n", mean(chanceRef), mean(neeOnly), mean(hitsOnly));
            savePng(neeOnly, W, H, new File(outDir, "nee_only.png"));
            savePng(hitsOnly, W, H, new File(outDir, "hits_only.png"));
        }
        double msSampled = timePerSample(engine, uSampled, 16);
        float[] sampledTest = render(engine, uSampled, testSamples);

        // convergence: block means
        int bw = W / 8, bh = H / 8;
        double blockDiff = 0, blockSum = 0; int blocks = 0;
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            double a = 0, b = 0;
            for (int y = by * 8; y < by * 8 + 8; y++) for (int x = bx * 8; x < bx * 8 + 8; x++) {
                int i = (y * W + x) * 4;
                a += chanceRef[i] + chanceRef[i + 1] + chanceRef[i + 2];
                b += sampledRef[i] + sampledRef[i + 1] + sampledRef[i + 2];
            }
            blockDiff += Math.abs(a - b); blockSum += b; blocks++;
        }
        double meanChance = mean(chanceRef), meanSampled = mean(sampledRef);
        double rel = blockDiff / Math.max(blockSum, 1e-9);
        System.out.printf("lights listed: %d%n", lights);
        for (int band = 0; band < 6; band++) {
            double a = 0, b = 0; int n = 0;
            for (int y = band * H / 6; y < (band + 1) * H / 6; y++) for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a += chanceRef[i] + chanceRef[i + 1] + chanceRef[i + 2];
                b += sampledRef[i] + sampledRef[i + 1] + sampledRef[i + 2]; n++;
            }
            System.out.printf("  band %d: by chance %.4f, sampled %.4f (%+.2f%%)%n", band, a / n, b / n, 100.0 * (b - a) / Math.max(a, 1e-9));
        }
        System.out.printf("converged mean: by chance %.4f, sampled %.4f (%.2f%%); block-mean difference %.2f%% of the image%n",
                meanChance, meanSampled, 100.0 * (meanSampled - meanChance) / Math.max(meanChance, 1e-9), 100.0 * rel);

        // noise at the test count, against the sampled reference
        double rmseChance = rmse(chanceTest, sampledRef), rmseSampled = rmse(sampledTest, sampledRef);
        System.out.printf("noise at %d spp (RMSE vs reference): by chance %.4f, sampled %.4f, ratio %.1fx%n",
                testSamples, rmseChance, rmseSampled, rmseChance / Math.max(rmseSampled, 1e-9));
        System.out.printf("cost: %.1f ms per sample by chance, %.1f sampled%n", msChance, msSampled);

        savePng(chanceRef, W, H, new File(outDir, "chance_ref.png"));
        savePng(sampledRef, W, H, new File(outDir, "sampled_ref.png"));
        savePng(chanceTest, W, H, new File(outDir, "chance_" + testSamples + ".png"));
        savePng(sampledTest, W, H, new File(outDir, "sampled_" + testSamples + ".png"));
        BufferedImage sheet = new BufferedImage(W * 2 + 4, H * 2 + 4, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = sheet.createGraphics();
        String[] names = {"chance_ref", "sampled_ref", "chance_" + testSamples, "sampled_" + testSamples};
        for (int i = 0; i < 4; i++) g.drawImage(javax.imageio.ImageIO.read(new File(outDir, names[i] + ".png")), (i % 2) * (W + 4), (i / 2) * (H + 4), null);
        g.dispose();
        javax.imageio.ImageIO.write(sheet, "png", new File(outDir, "_sheet.png"));

        // The verdict is the slab's: a scene whose emitter is bright enough to reach the firefly
        // clamp (the Cornell box, emission 15) is clamped far more when found by chance than
        // when drawn, and the two references differ for that reason, the drawn one being the
        // less wrong. At 320x180 the by-chance reference also sees the emitter inflated by the
        // marcher's cone epsilon (about 2% of its area); at 1280x720 the two agree to 0.1%.
        boolean slab = frac.getName().equals("slab");
        boolean ok = lights > 0 && rmseSampled < rmseChance && (!slab || rel < 0.03);
        System.out.println(ok ? (slab ? "EMITTERS OK" : "EMITTERS RENDERED (no verdict on a clamped scene; run the slab)") : "EMITTERS MISMATCH");
        System.out.println("DONE -> " + outDir.getAbsolutePath());
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    static FractalConfig slabScene() {
        org.fractalizer.graph.PrimitiveNode panel = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        panel.setSizeX(0.8f); panel.setSizeY(0.02f); panel.setSizeZ(0.8f);
        org.fractalizer.graph.MaterialNode lamp = new org.fractalizer.graph.MaterialNode(SceneBuilder.translate(panel, 0.3f, 1.8f, 0.2f));
        lamp.setMaterialType(0);
        lamp.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID);
        lamp.setColorR(1f); lamp.setColorG(0.95f); lamp.setColorB(0.9f);
        lamp.setEmission(2.5f);
        org.fractalizer.graph.PrimitiveNode block = new org.fractalizer.graph.PrimitiveNode(org.fractalizer.graph.PrimitiveNode.PrimitiveType.BOX);
        block.setSizeX(0.35f); block.setSizeY(0.5f); block.setSizeZ(0.35f);
        org.fractalizer.graph.MaterialNode matte = new org.fractalizer.graph.MaterialNode(SceneBuilder.translate(SceneBuilder.rotate(block, 0f, 25f, 0f), -0.5f, 0.5f, 0.3f));
        matte.setMaterialType(0);
        matte.setColorMode(org.fractalizer.graph.MaterialNode.COLOR_SOLID);
        matte.setColorR(0.8f); matte.setColorG(0.3f); matte.setColorB(0.25f);
        matte.setRoughness(0.6f); matte.setMetallic(0f);
        return SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.union(SceneBuilder.slab(2.6f), lamp), matte))
                .camera(0f, 2.2f, -3.6f).lookAt(0f, 0.3f, 0f).fov(45)
                .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(4)
                .lightIntensity(0f).ambientIntensity(0f)
                .build();
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
