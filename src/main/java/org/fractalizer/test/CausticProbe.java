package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Caustics (photon.glsl): is the photon pass calibrated, what does it cost, what does it
 * look like.
 *
 * <ol>
 * <li><b>Energy.</b> A matte slab under the sun and a black sky. Rendered without caustics
 *     the image is the sun's direct light through NEE. Rendered with caustics and
 *     {@code causticDebug} on, photons that met nothing specular land too, so the image is
 *     the same direct light twice: once by NEE, once by photons. The ratio of what the
 *     photons added to what NEE computed must be 1 over the lit slab; it is the check that
 *     the flux per photon, the camera connection and the splat are scaled right.</li>
 * <li><b>Cost.</b> Milliseconds per sample with and without the photon pass, at the
 *     default 512 x 512 photons.</li>
 * <li><b>Pictures.</b> A glass sphere on the slab with dispersion, and a smooth metal
 *     Mandelbulb over it, each with and without caustics, as PNGs and a sheet.</li>
 * </ol>
 *
 * Usage: CausticProbe [outDir] [WxH] [samples]
 */
public class CausticProbe {

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "out/caustic");
        String[] res = (args.length > 1 ? args[1] : "640x360").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 64;
        outDir.mkdirs();

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(W, H);
        GLSLEngine engine = controller.getEngine();

        // ---- 1. energy --------------------------------------------------------------------
        System.out.printf("=== CausticProbe (%dx%d, %d spp) ===%n", W, H, samples);
        AbstractFractalParams params = apply(controller, SceneBuilder.nodeGraph(SceneBuilder.slab(2.6f))
                .camera(0f, 2.2f, -3.6f).lookAt(0f, 0f, 0f).fov(45)
                .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(4)
                .lightDir(0.35f, 1.0f, -0.25f).lightColor(1f, 1f, 1f).lightIntensity(2.0f)
                .ambientIntensity(0f).causticPhotons(512).causticCenter(0f, 0f, 0f)
                .build());
        params.setSkyIntensity(0f);
        params.setAmbientIntensity(0f);
        params.setCausticsEnabled(false);
        engine.resize(W, H);
        int probeSamples = Math.max(16, samples);

        Map<String, Object> uOff = controller.buildUniformsForProbe();
        float[] direct = render(engine, uOff, probeSamples);
        Map<String, Object> uDark = controller.buildUniformsForProbe();
        uDark.put("lightIntensity", 0f);
        float[] dark = render(engine, uDark, probeSamples);

        params.setCausticsEnabled(true);
        params.setCausticRadius(4.0f);
        Map<String, Object> uOn = controller.buildUniformsForProbe();
        uOn.put("causticDebug", 1);
        float[] both = render(engine, uOn, probeSamples);

        double sumDirect = 0, sumDark = 0, sumPhotons = 0; int lit = 0;
        float[] photonsOnly = new float[direct.length];
        for (int i = 0; i < direct.length; i += 4) {
            float d = direct[i] + direct[i + 1] + direct[i + 2];
            for (int c = 0; c < 3; c++) photonsOnly[i + c] = Math.max(0f, both[i + c] - direct[i + c]);
            photonsOnly[i + 3] = 1f;
            if (d > 0.03f) {
                lit++;
                sumDirect += d;
                sumDark += dark[i] + dark[i + 1] + dark[i + 2];
                sumPhotons += (both[i] - direct[i]) + (both[i + 1] - direct[i + 1]) + (both[i + 2] - direct[i + 2]);
            }
        }
        double ratio = sumPhotons / sumDirect;
        System.out.printf("energy: %d lit pixels, sky leak %.4f of direct, photons / NEE = %.3f (expect 1.00)%n",
                lit, sumDark / Math.max(sumDirect, 1e-9), ratio);
        savePng(direct, W, H, new File(outDir, "energy_nee.png"));
        savePng(photonsOnly, W, H, new File(outDir, "energy_photons.png"));
        boolean energyOk = Math.abs(ratio - 1.0) < 0.08;

        // The same check with a glass ball on the slab: the emission map fills with the
        // cells over the ball and most photons are drawn there, each carrying the flux
        // its draw stands for; the direct light on the slab must still come out equal
        // wherever the ball changes nothing (not seen through it, not in its shadow), so
        // the comparison is against the first scene's NEE image on those pixels.
        params = apply(controller, SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.glassBall(0.5f, 1.5f, 0.02f), SceneBuilder.slab(2.6f)))
                .camera(0f, 2.2f, -3.6f).lookAt(0f, 0f, 0f).fov(45)
                .materialType(0).roughness(0.6f).pathTracing(true).maxBounces(4)
                .lightDir(0.35f, 1.0f, -0.25f).lightColor(1f, 1f, 1f).lightIntensity(2.0f)
                .ambientIntensity(0f).causticPhotons(512).causticCenter(0f, 0f, 0f)
                .build());
        params.setSkyIntensity(0f);
        params.setAmbientIntensity(0f);
        params.setCausticsEnabled(false);
        float[] direct2 = render(engine, controller.buildUniformsForProbe(), probeSamples);
        params.setCausticsEnabled(true);
        params.setCausticRadius(4.0f);
        Map<String, Object> uOn2 = controller.buildUniformsForProbe();
        uOn2.put("causticDebug", 1);
        float[] both2 = render(engine, uOn2, probeSamples);
        double sumD2 = 0, sumP2 = 0; int lit2 = 0;
        for (int i = 0; i < direct2.length; i += 4) {
            float d = direct[i] + direct[i + 1] + direct[i + 2];
            float d2 = direct2[i] + direct2[i + 1] + direct2[i + 2];
            if (d > 0.03f && Math.abs(d2 - d) < 0.02f * Math.max(d, 1f)) {
                lit2++;
                sumD2 += d;
                sumP2 += (both2[i] - direct2[i]) + (both2[i + 1] - direct2[i + 1]) + (both2[i + 2] - direct2[i + 2]);
            }
        }
        double ratio2 = sumP2 / sumD2;
        System.out.printf("energy with the emission map (glass ball on the slab): %d lit pixels, photons / NEE = %.3f (expect 1.00)%n", lit2, ratio2);
        energyOk &= Math.abs(ratio2 - 1.0) < 0.08;

        // ---- 2. cost ------------------------------------------------------------------------
        params.setCausticsEnabled(false);
        Map<String, Object> uCost = controller.buildUniformsForProbe();
        double msOff = timePerSample(engine, uCost, 16);
        params.setCausticsEnabled(true);
        Map<String, Object> uCostOn = controller.buildUniformsForProbe();
        double msOn = timePerSample(engine, uCostOn, 16);
        System.out.printf("cost: %.1f ms per sample without photons, %.1f with 512x512 photons (+%.1f ms per pass)%n",
                msOff, msOn, msOn - msOff);

        // ---- 3. pictures ----------------------------------------------------------------------
        FractalConfig sphere = SceneBuilder.nodeGraph(SceneBuilder.union(SceneBuilder.glassBall(0.5f, 1.5f, 0.02f), SceneBuilder.slab(2.6f)))
                .camera(0f, 1.7f, -3.4f).lookAt(0f, 0.35f, 0f).fov(42)
                .materialType(0).ior(1.5f).dispersion(0.03f).roughness(0.6f)
                .pathTracing(true).maxBounces(6).skyType(3)
                .lightDir(0.45f, 1.0f, -0.35f).lightColor(1f, 0.98f, 0.95f).lightIntensity(3.0f)
                .ambientIntensity(0.15f).caustics(3.0f).causticPhotons(512)
                .build();
        FractalConfig metal = SceneBuilder.nodeGraph(SceneBuilder.union(
                        SceneBuilder.translate(SceneBuilder.scale(SceneBuilder.fractal(FractalType.MANDELBULB), 0.55f), 0f, 0.95f, 0f), SceneBuilder.slab(2.6f)))
                .camera(0f, 1.9f, -3.4f).lookAt(0f, 0.5f, 0f).fov(45)
                .materialType(1).metalness(1.0f).roughness(0.05f)
                .pathTracing(true).maxBounces(6).skyType(3)
                .lightDir(0.3f, 1.0f, -0.6f).lightColor(1f, 0.98f, 0.95f).lightIntensity(3.0f)
                .ambientIntensity(0.15f).caustics(2.5f).causticPhotons(512)
                .build();
        BufferedImage[] sheet = new BufferedImage[4];
        String[] names = {"sphere_off", "sphere_on", "metal_off", "metal_on"};
        FractalConfig[] cfgs = {sphere, sphere, metal, metal};
        for (int i = 0; i < 4; i++) {
            AbstractFractalParams p = apply(controller, cfgs[i]);
            p.setCausticsEnabled(i % 2 == 1);
            controller.setExportSize(W, H);
            long t0 = System.nanoTime();
            controller.exportToPNG(new File(outDir, names[i] + ".png"), samples, pr -> {}, () -> false).get();
            System.out.printf("  %-11s %6d ms%n", names[i], (System.nanoTime() - t0) / 1_000_000);
            sheet[i] = javax.imageio.ImageIO.read(new File(outDir, names[i] + ".png"));
        }
        BufferedImage all = new BufferedImage(W * 2 + 4, H * 2 + 4, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = all.createGraphics();
        for (int i = 0; i < 4; i++) g.drawImage(sheet[i], (i % 2) * (W + 4), (i / 2) * (H + 4), null);
        g.dispose();
        javax.imageio.ImageIO.write(all, "png", new File(outDir, "_sheet.png"));

        System.out.println(energyOk ? "ENERGY OK" : "ENERGY MISMATCH");
        System.out.println("DONE -> " + outDir.getAbsolutePath());
        Platform.exit();
        System.exit(energyOk ? 0 : 1);
    }

    static AbstractFractalParams apply(GLSLFractalizerController controller, FractalConfig cfg) {
        controller.setFractalType(cfg.getFractalTypeEnum());
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        cfg.applyTo(params);
        controller.updatePaletteTexture(params.getCustomGradient());
        return params;
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

    static void savePng(float[] rgba, int w, int h, File file) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            int r = (int) (255 * Math.min(1, Math.max(0, rgba[i])));
            int g = (int) (255 * Math.min(1, Math.max(0, rgba[i + 1])));
            int b = (int) (255 * Math.min(1, Math.max(0, rgba[i + 2])));
            img.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        javax.imageio.ImageIO.write(img, "png", file);
    }
}
