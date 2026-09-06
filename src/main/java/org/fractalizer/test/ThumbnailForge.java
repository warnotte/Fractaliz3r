package org.fractalizer.test;

import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * Renders the thumbnails the app's "Presets &amp; Chains" browser shows: one per chain in
 * {@link HybridPresets} (framed at its previewDist, as HybridLab frames it) and one per
 * {@code presets/*.frac} (rendered as the app would show it, like GalleryRender). Also
 * writes {@code presets/index.txt}, the list the browser reads, since a jar cannot list
 * its own resources.
 *
 * Output goes under out/ like every harness; {@code install} as the last argument copies
 * the result into src/main/resources/thumbs, which is where the app reads it from. A JUnit
 * test fails when a chain in the library has no shipped thumbnail, so this is rerun
 * whenever the library grows.
 *
 * Usage:
 *   -Dexec.args="out/thumbs 320x180 16"           render only
 *   -Dexec.args="out/thumbs 320x180 16 install"   render and copy into the resources
 */
public class ThumbnailForge {

    static final File RESOURCES = new File("src/main/resources/thumbs");

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "out/thumbs");
        String[] res = (args.length > 1 ? args[1] : "320x180").split("x");
        int W = Integer.parseInt(res[0]), H = Integer.parseInt(res[1]);
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        boolean install = args.length > 3 && args[3].equalsIgnoreCase("install");
        File chainsDir = new File(outDir, "chains"), presetsDir = new File(outDir, "presets");
        chainsDir.mkdirs();
        presetsDir.mkdirs();

        CountDownLatch latch = new CountDownLatch(1);
        Platform.startup(latch::countDown);
        latch.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});

        // --- Chains, each on a fresh scene: the same object the browser loads. The first
        // version of this rendered them on the cached Mandelbulb params and left a chain
        // as that cache's graph root, so every Julia preset rendered afterwards silently
        // lost its constant and showed the last chain under the preset's camera.
        List<HybridPresets.Preset> chains = HybridPresets.all();
        System.out.printf("=== thumbnails (%dx%d, %d spp): %d chains ===%n", W, H, samples, chains.size());
        for (HybridPresets.Preset p : chains) {
            NodeGraphParams ngp = HybridPresets.toFreshParams(p);
            controller.replaceParams(ngp);
            controller.getEngine().getPostProcessParams().reset();
            controller.updatePaletteTexture(ngp.getCustomGradient());
            long t0 = System.nanoTime();
            BufferedImage img = controller.renderStill(W, H, samples, () -> false);
            writeJpeg(img, new File(chainsDir, HybridPresets.key(p) + ".jpg"));
            System.out.printf(Locale.ROOT, "  chain  %-28s %5d ms%n", HybridPresets.key(p), (System.nanoTime() - t0) / 1_000_000);
        }

        // --- Presets from disk, each exactly as File > Load shows it: on fresh params.
        File[] files = new File("presets").listFiles((d, n) -> n.endsWith(".frac"));
        if (files == null) files = new File[0];
        Arrays.sort(files);
        List<String> index = new ArrayList<>();
        System.out.printf("=== %d presets ===%n", files.length);
        for (File frac : files) {
            String name = frac.getName().replaceFirst("\\.frac$", "");
            FractalConfig cfg = FractalConfigManager.load(frac);
            AbstractFractalParams pp = cfg.toFreshParams();
            controller.replaceParams(pp);
            controller.updatePaletteTexture(pp.getCustomGradient());
            if (cfg.postProcess != null) controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
            else controller.getEngine().getPostProcessParams().reset();
            long t0 = System.nanoTime();
            BufferedImage img = controller.renderStill(W, H, samples, () -> false);
            writeJpeg(img, new File(presetsDir, name + ".jpg"));
            String desc = cfg.description == null ? "" : cfg.description.replace('\n', ' ').replace('|', '/');
            index.add(name + "|" + cfg.getFractalTypeEnum().name() + "|" + desc);
            System.out.printf(Locale.ROOT, "  preset %-28s %5d ms%n", name, (System.nanoTime() - t0) / 1_000_000);
        }
        Files.write(new File(presetsDir, "index.txt").toPath(), index, StandardCharsets.UTF_8);
        controller.restoreViewportSize();

        if (install) {
            copyDir(chainsDir, new File(RESOURCES, "chains"));
            copyDir(presetsDir, new File(RESOURCES, "presets"));
            System.out.println("installed into " + RESOURCES.getAbsolutePath());
        }
        System.out.println("thumbnails -> " + outDir.getAbsolutePath());
        System.exit(0);
    }

    static void aim(AbstractFractalParams params, float[] eye, float[] target, float fovDeg) {
        params.getCamera().setPosition(eye[0], eye[1], eye[2]);
        float[] q = CameraUtils.lookAt(eye, target);
        params.getCamera().setQuaternion(q[0], q[1], q[2], q[3]);
        params.setFovDegrees(fovDeg);
    }

    static void writeJpeg(BufferedImage img, File file) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam prm = writer.getDefaultWriteParam();
        prm.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        prm.setCompressionQuality(0.85f);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), prm);
        } finally {
            writer.dispose();
        }
    }

    static void copyDir(File from, File to) throws Exception {
        to.mkdirs();
        File[] files = from.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) Files.copy(f.toPath(), new File(to, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
