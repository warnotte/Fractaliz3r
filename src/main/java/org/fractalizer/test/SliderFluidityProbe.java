package org.fractalizer.test;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.render.ViewportEvent;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.components.EnhancedSlider;
import org.fractalizer.ui.panels.FractalPanel;
import org.fractalizer.ui.panels.LightingPanel;
import org.fractalizer.ui.panels.MaterialPanel;
import org.fractalizer.ui.panels.QualityPanel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How fluid is the viewport while a slider moves?
 *
 * The other half of "as soon as I touch something": NavigationFluidityProbe drives the
 * camera, this one drives the app's own controls. It builds the real panels (Fractal with
 * the node graph editor, Lighting, Material, Quality) against the real controller, exactly
 * as the app does but with no window, then moves one slider of each thirty times a second
 * for a few seconds, as a drag would. What it measures is what the user feels:
 *   - the time the JavaFX thread spends inside one slider tick (the setter, the panel's
 *     own refresh, the node editor's canvas redraw, the snapshot, the request),
 *   - the longest the JavaFX thread went without answering (a pinger),
 *   - preview images per second and request-to-image latency.
 * The first row is the bare path (the parameter set and the request, no control): the UI
 * cost of each panel is the difference.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.SliderFluidityProbe" \
 *       -Dexec.args="presets/ALBEDO_039.frac 1280x720 3"
 */
public class SliderFluidityProbe {

    public static void main(String[] args) throws Exception {
        String spec = args.length > 0 ? args[0] : "MANDELBULB";
        String[] res = (args.length > 1 ? args[1] : "1280x720").split("x");
        int w = Integer.parseInt(res[0]), h = Integer.parseInt(res[1]);
        double seconds = args.length > 2 ? Double.parseDouble(args[2]) : 3;
        boolean window = args.length > 3 && args[3].equals("window");   // panels in a real window: layout and CSS passes count

        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(w, h);
        loadScene(controller, spec);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        controller.renderStill(64, 36, 1, () -> false);   // compile once, off the FX thread

        // ---- the app's panels, on the FX thread, no window ---------------------------------
        AtomicLong lastRequest = new AtomicLong(0);
        Runnable request = () -> { lastRequest.set(System.nanoTime()); controller.requestRender(); };
        javafx.scene.image.ImageView viewportView = new javafx.scene.image.ImageView();
        viewportView.setFitWidth(w); viewportView.setFitHeight(h);
        FractalPanel[] fractal = new FractalPanel[1];
        LightingPanel[] lighting = new LightingPanel[1];
        MaterialPanel[] material = new MaterialPanel[1];
        QualityPanel[] quality = new QualityPanel[1];
        CountDownLatch built = new CountDownLatch(1);
        Platform.runLater(() -> {
            fractal[0] = new FractalPanel(controller, params, request::run);
            lighting[0] = new LightingPanel(() -> params, request::run);
            material[0] = new MaterialPanel(() -> params, request::run);
            quality[0] = new QualityPanel(() -> params, request::run, on -> {});
            fractal[0].refreshFromParams(false);
            if (params instanceof NodeGraphParams ngp && ngp.getGraphRoot() != null) {
                fractal[0].getNodeGraphEditor().selectNode(ngp.getGraphRoot());
            }
            if (window) {
                javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane(
                        new javafx.scene.control.Tab("Fractal", fractal[0]), new javafx.scene.control.Tab("Lighting", lighting[0]),
                        new javafx.scene.control.Tab("Material", material[0]), new javafx.scene.control.Tab("Quality", quality[0]));
                tabs.setPrefWidth(420);
                javafx.scene.layout.BorderPane root = new javafx.scene.layout.BorderPane(viewportView, null, tabs, null, null);
                javafx.stage.Stage stage = new javafx.stage.Stage();
                stage.setTitle("SliderFluidityProbe");
                stage.setScene(new javafx.scene.Scene(root, w + 440, h + 40));
                stage.show();
            }
            built.countDown();
        });
        built.await(60, TimeUnit.SECONDS);

        // ---- images -----------------------------------------------------------------------
        AtomicInteger images = new AtomicInteger();
        List<Long> latencyMs = Collections.synchronizedList(new ArrayList<>());
        controller.setViewportListener(new ViewportEvent.Listener() {
            @Override public void onImage(ViewportEvent.ViewportImage img) {
                images.incrementAndGet();
                if (lastRequest.get() > 0) latencyMs.add((System.nanoTime() - lastRequest.get()) / 1_000_000);
                if (window) {
                    javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(img.width(), img.height());
                    image.getPixelWriter().setPixels(0, 0, img.width(), img.height(),
                            javafx.scene.image.PixelFormat.getByteBgraInstance(), img.bgra(), 0, img.width() * 4);
                    viewportView.setImage(image);
                }
            }
            @Override public void onStatus(ViewportEvent.Status st) {}
        });

        System.out.printf("=== SliderFluidityProbe: %s at %dx%d, %.0f s per control at 30 ticks/s%s ===%n", spec, w, h, seconds,
                window ? ", panels in a window" : ", no window");
        System.out.printf("  %-34s %8s %8s %8s %8s %9s %10s%n", "control", "tick avg", "tick p95", "tick max", "FX stall", "images/s", "req->img");

        // the bare path: a parameter set plus the request, no control
        runTicks("bare: params + requestRender", seconds, images, latencyMs, lastRequest, t -> {
            params.setFov((float) Math.toRadians(50 + 10 * Math.sin(t)));
            request.run();
        });

        // one slider of each panel, driven as a drag drives it
        List<EnhancedSlider> editorSliders = sliders(fractal[0].getNodeGraphEditor());
        List<EnhancedSlider> lightSliders = sliders(lighting[0]);
        List<EnhancedSlider> matSliders = sliders(material[0]);
        List<EnhancedSlider> qualSliders = sliders(quality[0]);
        drive("node editor: " + first(editorSliders), editorSliders, seconds, images, latencyMs, lastRequest);
        drive("lighting: " + first(lightSliders), lightSliders, seconds, images, latencyMs, lastRequest);
        drive("material: " + first(matSliders), matSliders, seconds, images, latencyMs, lastRequest);
        drive("quality: " + first(qualSliders), qualSliders, seconds, images, latencyMs, lastRequest);

        controller.close();
        Platform.exit();
        System.exit(0);
    }

    private interface Tick { void at(double t); }

    private static void drive(String name, List<EnhancedSlider> sliders, double seconds, AtomicInteger images,
                              List<Long> latencyMs, AtomicLong lastRequest) throws Exception {
        if (sliders.isEmpty()) { System.out.printf("  %-34s (no slider found)%n", name); return; }
        EnhancedSlider s = sliders.get(0);
        double min = s.getSlider().getMin(), max = s.getSlider().getMax(), mid = (min + max) / 2, amp = (max - min) * 0.2;
        runTicks(name, seconds, images, latencyMs, lastRequest, t -> {
            lastRequest.set(System.nanoTime());
            s.setValue(mid + amp * Math.sin(t * 3));
        });
    }

    private static void runTicks(String name, double seconds, AtomicInteger images, List<Long> latencyMs,
                                 AtomicLong lastRequest, Tick tick) throws Exception {
        List<Long> tickMs = Collections.synchronizedList(new ArrayList<>());
        AtomicLong worstStall = new AtomicLong(0);
        CountDownLatch done = new CountDownLatch(1);
        images.set(0);
        latencyMs.clear();
        Thread pinger = new Thread(() -> {
            while (done.getCount() > 0) {
                CountDownLatch pong = new CountDownLatch(1);
                long sent = System.nanoTime();
                Platform.runLater(pong::countDown);
                try { pong.await(); } catch (InterruptedException e) { return; }
                worstStall.accumulateAndGet((System.nanoTime() - sent) / 1_000_000, Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        }, "fx-pinger");
        pinger.setDaemon(true);
        long tStart = System.nanoTime();
        Platform.runLater(() -> {
            pinger.start();
            new AnimationTimer() {
                long last = 0;
                @Override public void handle(long now) {
                    if ((now - tStart) / 1e9 >= seconds) { stop(); done.countDown(); return; }
                    if (now - last < 33_000_000L) return;      // 30 ticks a second, a drag's rate
                    last = now;
                    long t0 = System.nanoTime();
                    tick.at((now - tStart) / 1e9);
                    tickMs.add((System.nanoTime() - t0) / 1_000_000);
                }
            }.start();
        });
        done.await((long) seconds + 60, TimeUnit.SECONDS);
        double elapsed = (System.nanoTime() - tStart) / 1e9;
        Thread.sleep(300);
        pinger.interrupt();
        lastRequest.set(0);
        System.out.printf("  %-34s %5d ms %5d ms %5d ms %5d ms %9.1f %7d ms%n", name,
                mean(tickMs), pct(tickMs, 95), max(tickMs), worstStall.get(), images.get() / elapsed, pct(latencyMs, 50));
    }

    private static List<EnhancedSlider> sliders(Node root) {
        List<EnhancedSlider> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node n, List<EnhancedSlider> out) {
        if (n instanceof EnhancedSlider s) { out.add(s); return; }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) collect(sp.getContent(), out);
        if (n instanceof javafx.scene.control.TitledPane tp && tp.getContent() != null) collect(tp.getContent(), out);
        if (n instanceof javafx.scene.control.TabPane tabs) tabs.getTabs().forEach(t -> { if (t.getContent() != null) collect(t.getContent(), out); });
        if (n instanceof javafx.scene.control.SplitPane sp) sp.getItems().forEach(c -> collect(c, out));
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, out);
    }

    private static String first(List<EnhancedSlider> s) { return s.isEmpty() ? "-" : s.get(0).getTitle(); }

    private static void loadScene(GLSLFractalizerController controller, String spec) throws Exception {
        File f = new File(spec);
        if (f.isFile()) {
            FractalConfig cfg = FractalConfigManager.load(f);
            controller.setFractalType(cfg.getFractalTypeEnum());
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            cfg.applyTo(params);
            controller.updatePaletteTexture(params.getCustomGradient());
            if (cfg.postProcess != null) controller.getEngine().getPostProcessParams().copyFrom(cfg.postProcess);
        } else {
            controller.setFractalType(FractalType.valueOf(spec));
        }
    }

    private static long mean(List<Long> v) { if (v.isEmpty()) return 0; long s = 0; for (long x : v) s += x; return s / v.size(); }
    private static long max(List<Long> v) { return v.isEmpty() ? 0 : Collections.max(v); }
    private static long pct(List<Long> v, int p) {
        if (v.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(v); Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * p / 100.0)));
    }
}
