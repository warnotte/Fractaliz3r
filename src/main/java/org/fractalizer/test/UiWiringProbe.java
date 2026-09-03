package org.fractalizer.test;

import org.fractalizer.fractals.FractalType;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.GraphNode;
import org.fractalizer.graph.HybridNode;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.components.NodeGraphEditor;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a control exists on screen, not merely in the source.
 *
 * The "+ Hybrid" button was written, given a tooltip and an action handler, and then never
 * added to the toolbar — it lived as a local variable and was dropped. The code reads
 * perfectly and the feature was unreachable. Nothing short of building the panel catches
 * that, so the panel gets built.
 *
 * Usage:  -Dexec.args=""   (no arguments)
 */
public class UiWiringProbe {

    static final List<String> failures = new ArrayList<>();

    static void check(String what, boolean ok) {
        System.out.printf("  %-52s %s%n", what, ok ? "ok" : "MISSING");
        if (!ok) failures.add(what);
    }

    /** Every Labeled control anywhere under a parent, by its text. */
    static void collectLabels(Node n, List<String> out) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) collectLabels(c, out);
        }
    }

    static Button findButton(Node n, String text) {
        if (n instanceof Button b && text.equals(b.getText())) return b;
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

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setFractalType(FractalType.MANDELBULB);
        NodeGraphParams ngp = (NodeGraphParams) controller.getParams();

        System.out.println("=== UiWiringProbe ===");

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                NodeGraphEditor editor = new NodeGraphEditor(controller, () -> { });
                editor.loadParams(ngp);

                List<String> labels = new ArrayList<>();
                collectLabels(editor, labels);
                check("toolbar exposes \"+ Hybrid\"", labels.contains("+ Hybrid"));
                check("toolbar exposes \"+ Fractal\" (control)", labels.contains("+ Fractal"));

                Button hybridBtn = findButton(editor, "+ Hybrid");
                if (hybridBtn != null) {
                    hybridBtn.fire();
                    check("firing it puts a HybridNode in the graph",
                            ngp.getGraphRoot() != null && containsHybrid(ngp.getGraphRoot()));
                } else {
                    failures.add("cannot fire an absent button");
                }
            } catch (Throwable t) {
                t.printStackTrace();
                failures.add("editor construction threw " + t.getClass().getSimpleName());
            } finally {
                done.countDown();
            }
        });
        done.await(60, TimeUnit.SECONDS);

        // The chain library is plain logic and can be checked off the FX thread.
        var presets = HybridPresets.all();
        check("chain library is populated (" + presets.size() + " chains)", presets.size() >= 5);
        boolean applies = false;
        if (!presets.isEmpty()) {
            HybridNode n = new HybridNode();
            HybridPresets.apply(n, presets.get(2));
            applies = n.getSteps().size() == presets.get(2).steps().size();
        }
        check("a preset loads into a node", applies);

        System.out.println();
        System.out.println(failures.isEmpty()
                ? "RESULT: the hybrid node and its chain library are reachable from the UI"
                : "RESULT: UNREACHABLE -> " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
