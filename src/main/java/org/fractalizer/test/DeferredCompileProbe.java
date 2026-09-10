package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Does a scene shader compile block the JavaFX thread?
 *
 * The app asks for the viewport from the JavaFX thread; when the scene needs a program the
 * request must return at once, the scheduler must compile on the GL thread and report
 * COMPILING, the JavaFX thread must keep answering meanwhile, and the image must arrive
 * afterwards. Off the JavaFX thread (exports, harnesses) a still must compile inside the
 * call and return the image.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DeferredCompileProbe"
 *
 * Exit code 1 when the request held the JavaFX thread for more than {@link #MAX_CALL_MS},
 * when the JavaFX thread stalled for more than {@link #MAX_STALL_MS} while compiling, when
 * no COMPILING status was seen, or when no image arrived.
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
        CountDownLatch imageArrived = new CountDownLatch(1);
        AtomicLong imageSize = new AtomicLong();
        boolean[] sawCompiling = {false};
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) {
                events.add(String.format("%6d ms  image %dx%d, %d samples", (System.nanoTime() - tRef) / 1_000_000,
                        img.width(), img.height(), img.samples()));
                imageSize.set((long) img.width() << 32 | img.height());
                imageArrived.countDown();
            }
            @Override public void onStatus(ViewportEvent.Status st) {
                events.add(String.format("%6d ms  status %s%s", (System.nanoTime() - tRef) / 1_000_000, st.phase(),
                        st.message() != null ? " " + st.message() : ""));
                if (st.phase() == ViewportEvent.Phase.COMPILING) sawCompiling[0] = true;
            }
        });

        // ---- 1. From the JavaFX thread: must not block -------------------------------------
        AtomicLong callMs = new AtomicLong(-1);
        CountDownLatch issued = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.replaceParams(new NodeGraphParams(FractalType.MANDELBOX));   // fresh graph: needs a compile
            long c0 = System.nanoTime();
            controller.requestRender();
            callMs.set(ms(c0));
            issued.countDown();
        });
        issued.await(30, TimeUnit.SECONDS);

        // Ping the JavaFX thread while the compile runs: the longest gap is how long the UI froze.
        AtomicLong worstStall = new AtomicLong(0);
        Thread pinger = new Thread(() -> {
            while (imageArrived.getCount() > 0) {
                CountDownLatch pong = new CountDownLatch(1);
                long sent = System.nanoTime();
                Platform.runLater(pong::countDown);
                try { pong.await(); } catch (InterruptedException e) { return; }
                worstStall.accumulateAndGet((System.nanoTime() - sent) / 1_000_000, Math::max);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "fx-pinger");
        pinger.setDaemon(true);
        pinger.start();

        boolean gotImage = imageArrived.await(180, TimeUnit.SECONDS);
        long totalMs = (System.nanoTime() - tRef) / 1_000_000;
        pinger.interrupt();

        System.out.println("=== 1. requestRender from the JavaFX thread, scene not compiled ===");
        System.out.printf("  call returned in     %6d ms   (limit %d)%n", callMs.get(), MAX_CALL_MS);
        System.out.printf("  COMPILING reported   %s%n", sawCompiling[0] ? "yes" : "NO");
        System.out.printf("  worst FX stall       %6d ms   (limit %d)%n", worstStall.get(), MAX_STALL_MS);
        System.out.printf("  image arrived        %s after %d ms%n", gotImage ? "yes" : "NO", totalMs);
        for (String e : events) System.out.println("  " + e);

        // ---- 2. Off the JavaFX thread: must compile in the call and return the image ---------
        controller.replaceParams(new NodeGraphParams(FractalType.MENGER_SPONGE));
        long s0 = System.nanoTime();
        java.awt.image.BufferedImage still = controller.renderStill(320, 180, 2, () -> false);
        long stillMs = ms(s0);
        System.out.println("=== 2. renderStill from the main thread, scene not compiled ===");
        System.out.printf("  call took            %6d ms   (includes the compile)%n", stillMs);
        System.out.printf("  image                %s%n", still != null ? still.getWidth() + "x" + still.getHeight() : "NONE");

        boolean ok = gotImage && sawCompiling[0] && callMs.get() >= 0 && callMs.get() <= MAX_CALL_MS
                && worstStall.get() <= MAX_STALL_MS && still != null;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    private static long ms(long since) { return (System.nanoTime() - since) / 1_000_000; }
}
