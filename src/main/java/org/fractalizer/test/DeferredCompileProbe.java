package org.fractalizer.test;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Does a scene shader compile block the JavaFX thread?
 *
 * The app asks for a preview from the JavaFX thread; when the scene needs a new program the
 * controller must hand the compile to its SceneCompile thread, return at once, keep pumping
 * the JavaFX thread, report through the compile listener, and deliver the image afterwards.
 * Off the JavaFX thread (exports, harnesses) the same request must compile synchronously and
 * return the image in the call.
 *
 * Run with the NVIDIA disk shader cache off so the compile really takes seconds:
 *   set __GL_SHADER_DISK_CACHE=0
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DeferredCompileProbe"
 *
 * Exit code 1 when the JavaFX-thread call blocked for more than {@link #MAX_CALL_MS}, when
 * the JavaFX thread stalled for more than {@link #MAX_STALL_MS} while compiling, or when no
 * image arrived.
 */
public class DeferredCompileProbe {

    private static final long MAX_CALL_MS = 500;
    private static final long MAX_STALL_MS = 1000;

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.setViewportSize(640, 360);
        long t0 = System.nanoTime();
        controller.loadAllShaders((m, p) -> {});
        System.out.printf("startup scene (sync, main thread): %d ms%n", ms(t0));

        List<String> events = new ArrayList<>();
        long tRef = System.nanoTime();
        controller.setCompileListener(msg ->
                events.add(String.format("%6d ms  listener: %s", (System.nanoTime() - tRef) / 1_000_000, msg)));

        // ---- 1. From the JavaFX thread: must not block -------------------------------------
        CountDownLatch imageArrived = new CountDownLatch(1);
        AtomicLong callMs = new AtomicLong(-1);
        AtomicLong deferred = new AtomicLong(0);
        AtomicReference<Image> image = new AtomicReference<>();
        CountDownLatch issued = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.replaceParams(new NodeGraphParams(FractalType.MANDELBOX));   // fresh graph: needs a compile
            long c0 = System.nanoTime();
            controller.renderPreview(img -> { image.set(img); imageArrived.countDown(); }, p -> {});
            callMs.set(ms(c0));
            deferred.set(controller.isCompiling() ? 1 : 0);
            issued.countDown();
        });
        issued.await(30, TimeUnit.SECONDS);

        // Ping the JavaFX thread while the compile runs: the longest gap is how long the UI froze.
        AtomicLong worstStall = new AtomicLong(0);
        AtomicLong lastPing = new AtomicLong(System.nanoTime());
        Thread pinger = new Thread(() -> {
            while (imageArrived.getCount() > 0) {
                CountDownLatch pong = new CountDownLatch(1);
                long sent = System.nanoTime();
                Platform.runLater(pong::countDown);
                try { pong.await(); } catch (InterruptedException e) { return; }
                long gap = (System.nanoTime() - sent) / 1_000_000;
                worstStall.accumulateAndGet(gap, Math::max);
                lastPing.set(System.nanoTime());
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "fx-pinger");
        pinger.setDaemon(true);
        pinger.start();

        boolean gotImage = imageArrived.await(180, TimeUnit.SECONDS);
        long totalMs = (System.nanoTime() - tRef) / 1_000_000;
        pinger.interrupt();

        System.out.println("=== 1. renderPreview from the JavaFX thread, scene not compiled ===");
        System.out.printf("  call returned in     %6d ms   (limit %d)%n", callMs.get(), MAX_CALL_MS);
        System.out.printf("  deferred to worker   %s%n", deferred.get() == 1 ? "yes" : "NO");
        System.out.printf("  worst FX stall       %6d ms   (limit %d)%n", worstStall.get(), MAX_STALL_MS);
        System.out.printf("  image arrived        %s after %d ms%s%n", gotImage ? "yes" : "NO", totalMs,
                gotImage ? String.format(" (%dx%d)", (int) image.get().getWidth(), (int) image.get().getHeight()) : "");
        for (String e : events) System.out.println("  " + e);

        // ---- 2. Off the JavaFX thread: must compile in the call and return the image ---------
        controller.replaceParams(new NodeGraphParams(FractalType.MENGER_SPONGE));
        long s0 = System.nanoTime();
        java.awt.image.BufferedImage still = controller.renderStill(320, 180, 2, () -> false);
        long stillMs = ms(s0);
        System.out.println("=== 2. renderStill from the main thread, scene not compiled ===");
        System.out.printf("  call took            %6d ms   (includes the compile)%n", stillMs);
        System.out.printf("  image                %s%n", still != null ? still.getWidth() + "x" + still.getHeight() : "NONE");
        System.out.printf("  compiling flag       %s%n", controller.isCompiling() ? "SET (wrong)" : "clear");

        boolean ok = gotImage && callMs.get() >= 0 && callMs.get() <= MAX_CALL_MS
                && worstStall.get() <= MAX_STALL_MS && still != null && !controller.isCompiling();
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.getEngine().close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    private static long ms(long since) { return (System.nanoTime() - since) / 1_000_000; }
}
