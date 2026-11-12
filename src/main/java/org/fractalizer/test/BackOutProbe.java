package org.fractalizer.test;

import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The interactive viewport while the camera backs away from the surface: the real scheduler,
 * one request per frame as a held key gives, the camera moved back along its view by a
 * growing step; the image on screen at each frame is kept, and every so often the camera
 * stops and the refinement is left to run (the image a user sees when they let go).
 *
 * Usage: scene outDir WxH frames distance [pauseEvery] [refineMs] [auto]
 *   auto: the step of each frame comes from the auto speed (DiveSpeed, a probe per frame),
 *   distance being the speed dial
 */
public class BackOutProbe {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String spec = args[0];
        File outDir = new File(args[1]);
        String[] res = args[2].split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        int frames = Integer.parseInt(args[3]);
        double distance = Double.parseDouble(args[4]);
        int pauseEvery = args.length > 5 ? Integer.parseInt(args[5]) : 10;
        long refineMs = args.length > 6 ? Long.parseLong(args[6]) : 1500;
        boolean auto = args.length > 7 && args[7].equals("auto");
        outDir.mkdirs();

        GLSLFractalizerController controller = PrecisionLab.start(w, h);
        FocusPickProbe.loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        Camera cam = params.getCamera();
        controller.renderStill(32, 18, 1, () -> false);
        controller.restoreViewportSize();

        AtomicReference<ViewportEvent.ViewportImage> last = new AtomicReference<>();
        AtomicReference<ViewportEvent.Status> status = new AtomicReference<>();
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) { last.set(img); }
            @Override public void onStatus(ViewportEvent.Status st) { status.set(st); }
        });

        double[] e0 = cam.getPositionD();
        float[] f = cam.getForwardVector();
        System.out.printf(Locale.ROOT, "=== BackOutProbe: %s, %dx%d, %d frames back over %.3g ===%n", spec, w, h, frames, distance);
        org.fractalizer.ui.DiveSpeed speed = org.fractalizer.ui.DiveSpeed.over(controller);
        double[] back = {-f[0], -f[1], -f[2]};
        double t = 0;
        for (int k = 0; k <= frames; k++) {
            double x = (double) k / frames;
            if (auto) {                                       // as the app: a held key, 60 frames a second
                double[] at = cam.getPositionD();
                java.util.concurrent.CompletableFuture<Double> step = new java.util.concurrent.CompletableFuture<>();
                Platform.runLater(() -> step.complete(speed.step(distance, at, back)));
                t += step.get();
            } else {
                t = distance * x * x;                         // gently at first
            }
            cam.setPosition(e0[0] - f[0] * t, e0[1] - f[1] * t, e0[2] - f[2] * t);
            Platform.runLater(controller::requestRender);
            Thread.sleep(auto ? 16 : 33);
            boolean pause = k % pauseEvery == 0;
            save(last.get(), new File(outDir, String.format("move_%04d.png", k)));
            if (pause) {
                Thread.sleep(refineMs);
                ViewportEvent.Status st = status.get();
                ViewportEvent.ViewportImage img = last.get();
                save(img, new File(outDir, String.format("rest_%04d.png", k)));
                System.out.printf(Locale.ROOT, "  frame %4d  back %.4f  %s %d spp, image %dx%d%n", k, t,
                        st == null ? "?" : st.phase(), img == null ? 0 : img.samples(), img == null ? 0 : img.width(), img == null ? 0 : img.height());
            }
        }
        controller.close();
        Platform.exit();
        System.exit(0);
    }

    static void save(ViewportEvent.ViewportImage img, File f) throws Exception {
        if (img == null) return;
        BufferedImage b = new BufferedImage(img.width(), img.height(), BufferedImage.TYPE_INT_RGB);
        byte[] p = img.bgra();
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                int i = (y * img.width() + x) * 4;
                b.setRGB(x, y, ((p[i + 2] & 255) << 16) | ((p[i + 1] & 255) << 8) | (p[i] & 255));
            }
        }
        ImageIO.write(b, "png", f);
    }
}
