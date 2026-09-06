package org.fractalizer.ui.components;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import org.fractalizer.fractals.FractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.ui.RenderController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a control exists on screen, not merely in the source.
 *
 * The "+ Hybrid" button was once written, given a tooltip and an action handler, and then
 * never added to the toolbar: it lived as a local variable and was dropped. The code read
 * perfectly and the feature was unreachable. Nothing short of building the panel catches
 * that, so the panel gets built, on the JavaFX thread, against a controller stub that has
 * no GPU behind it.
 */
class UiWiringTest {

    /** A controller with no engine: the editor only needs a place to send compiled GLSL. */
    static final class StubController implements RenderController {
        final NodeGraphParams params = new NodeGraphParams(FractalType.MANDELBULB);
        int compiles = 0;

        @Override public void setFractalType(FractalType type) { }
        @Override public FractalType getFractalType() { return FractalType.NODE_GRAPH; }
        @Override public void setParams(FractalParams p) { }
        @Override public FractalParams getParams() { return params; }
        @Override public void setViewportSize(int w, int h) { }
        @Override public int getViewportWidth() { return 640; }
        @Override public int getViewportHeight() { return 360; }
        @Override public void setExportSize(int w, int h) { }
        @Override public int getExportWidth() { return 640; }
        @Override public int getExportHeight() { return 360; }
        @Override public void renderPreview(Consumer<Image> onComplete, Consumer<Double> onProgress) { }
        @Override public void renderFull(Consumer<Image> onComplete, Consumer<Double> onProgress, Consumer<Object> onTile) { }
        @Override public CompletableFuture<Void> exportToPNG(File f, Consumer<Double> p) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> exportToPNG(File f, int s, Consumer<Double> p) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> exportToPNG(File f, int s, Consumer<Double> p, Supplier<Boolean> c) { return CompletableFuture.completedFuture(null); }
        @Override public void exportAOV(File file, int renderMode) { }
        @Override public void cancelRender() { }
        @Override public boolean isRendering() { return false; }
        @Override public void prepareGPUEvaluator() { }
        @Override public float[] evaluateGPUSlice(float z, float half, int res) { return new float[0]; }
        @Override public String getDeviceName() { return "stub"; }
        @Override public String getDeviceType() { return "none"; }
        @Override public String compileCustomShader(String source) { return null; }
        @Override public String compileNodeGraph(String source) { compiles++; return null; }
        @Override public void close() { }
    }

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // another test class started it in this JVM
        }
        Platform.setImplicitExit(false);
    }

    /** Runs a piece of UI work on the FX thread and rethrows anything it threw. */
    static <T> T onFxThread(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(work.get()); }
            catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX work did not finish");
        if (error.get() != null) throw new AssertionError("FX thread threw", error.get());
        return result.get();
    }

    // A TabPane without a skin (no window shown) does not list its tabs' content among
    // its children, so the walk descends into the tabs explicitly.
    static void collectLabels(Node n, List<String> out) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) out.add(l.getText());
        if (n instanceof javafx.scene.control.TabPane tp) {
            for (javafx.scene.control.Tab t : tp.getTabs()) {
                if (t.getText() != null && !t.getText().isBlank()) out.add(t.getText());
                if (t.getContent() != null) collectLabels(t.getContent(), out);
            }
        }
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collectLabels(c, out);
    }

    static Button findButton(Node n, String text) {
        if (n instanceof Button b && text.equals(b.getText())) return b;
        if (n instanceof javafx.scene.control.TabPane tp) {
            for (javafx.scene.control.Tab t : tp.getTabs()) {
                Button found = t.getContent() == null ? null : findButton(t.getContent(), text);
                if (found != null) return found;
            }
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Button found = findButton(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    static boolean containsHybrid(GraphNode n) {
        if (n instanceof HybridNode) return true;
        for (GraphNode c : n.getChildren()) if (containsHybrid(c)) return true;
        return false;
    }

    record Built(StubController controller, NodeGraphEditor editor) { }

    static Built build() throws Exception {
        StubController controller = new StubController();
        NodeGraphEditor editor = onFxThread(() -> {
            NodeGraphEditor e = new NodeGraphEditor(controller, () -> { });
            e.loadParams(controller.params);
            return e;
        });
        return new Built(controller, editor);
    }

    @Test
    void toolbarExposesTheAddButtons() throws Exception {
        Built b = build();
        List<String> labels = onFxThread(() -> { List<String> l = new ArrayList<>(); collectLabels(b.editor, l); return l; });
        assertTrue(labels.contains("+ Fractal"), "\"+ Fractal\" on the toolbar; labels: " + labels);
        assertTrue(labels.contains("+ Hybrid"), "\"+ Hybrid\" on the toolbar; labels: " + labels);
    }

    @Test
    void nodeContextMenuOffersTheHybridActions() throws Exception {
        Built b = build();
        // The menu is built on demand for the node under the cursor, so it is reached
        // through its builder rather than through a real click.
        List<String> items = onFxThread(() -> {
            try {
                Method m = NodeGraphEditor.class.getDeclaredMethod("buildNodeContextMenu", GraphNode.class);
                m.setAccessible(true);
                m.invoke(b.editor, b.controller.params.getGraphRoot());
                Field f = NodeGraphEditor.class.getDeclaredField("contextMenu");
                f.setAccessible(true);
                ContextMenu menu = (ContextMenu) f.get(b.editor);
                List<String> out = new ArrayList<>();
                for (MenuItem mi : menu.getItems()) if (mi.getText() != null) out.add(mi.getText());
                return out;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not inspect the node context menu", e);
            }
        });
        assertTrue(items.contains("Replace with Hybrid Chain"), "menu items: " + items);
        assertTrue(items.contains("Add Hybrid Chain (union)"), "menu items: " + items);
    }

    @Test
    void firingTheHybridButtonPutsAHybridNodeInTheGraph() throws Exception {
        Built b = build();
        assertFalse(containsHybrid(b.controller.params.getGraphRoot()), "starts without a hybrid");
        Button hybrid = onFxThread(() -> findButton(b.editor, "+ Hybrid"));
        assertNotNull(hybrid, "cannot fire an absent button");
        onFxThread(() -> { hybrid.fire(); return null; });
        GraphNode root = b.controller.params.getGraphRoot();
        assertNotNull(root);
        assertTrue(containsHybrid(root), "the graph now holds a HybridNode");
    }
}
