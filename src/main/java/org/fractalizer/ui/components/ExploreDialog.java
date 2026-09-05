package org.fractalizer.ui.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fractalizer.engine.Camera;
import org.fractalizer.explore.CameraExplorer;
import org.fractalizer.explore.CameraExplorer.Candidate;
import org.fractalizer.explore.CameraExplorer.Settings;
import org.fractalizer.explore.ControllerViewRenderer;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * "Explore": the app looks for detailed views from where the camera is and shows them
 * as scored thumbnails; click one to fly there, then explore again from there to go
 * deeper. The search is {@link CameraExplorer}, the traveller from the navigator
 * harness, run on the GPU at thumbnail size.
 *
 * While a search runs the host pauses its own preview loop (the explorer drives the
 * scene camera and the engine size), and the thumbnails are not clickable. When it
 * ends, the camera the user had is put back — flying somewhere is a click, never a
 * side effect of searching.
 */
public class ExploreDialog {

    /** What the dialog needs from the main window. All calls arrive on the FX thread. */
    public interface Host {
        AbstractFractalParams params();
        /** The search is starting (true) or over (false): pause / resume the render loop. */
        void exploringChanged(boolean exploring);
        /** Put the camera at {@code eye} looking at {@code target} and re-render. */
        void flyTo(float[] eye, float[] target, float fovDeg);
    }

    static final int THUMB_W = 320, THUMB_H = 180;

    private final Stage stage;
    private final GLSLFractalizerController controller;
    private final Host host;

    private final Spinner<Integer> targetsSpinner = new Spinner<>(1, 5, 3);
    private final Spinner<Integer> stepsSpinner = new Spinner<>(2, 8, 4);
    private final Spinner<Double> shrinkSpinner = new Spinner<>(0.4, 0.85, 0.6, 0.05);
    private final Spinner<Integer> samplesSpinner = new Spinner<>(1, 16, 4);
    private final Button exploreBtn = new Button("Explore from current view");
    private final Button cancelBtn = new Button("Cancel");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Looks for detailed framings around what the camera sees now.");
    private final FlowPane grid = new FlowPane(8, 8);

    private final List<Tile> tiles = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private Tile selected;

    public ExploreDialog(Window owner, GLSLFractalizerController controller, Host host) {
        this.controller = controller;
        this.host = host;

        for (Spinner<?> sp : List.of(targetsSpinner, stepsSpinner, shrinkSpinner, samplesSpinner)) {
            sp.setPrefWidth(70);
            sp.setEditable(false);
        }
        targetsSpinner.setTooltip(new Tooltip("Aim points kept after the 3x3 scan; each gets its own dive"));
        stepsSpinner.setTooltip(new Tooltip("Dive steps per target"));
        shrinkSpinner.setTooltip(new Tooltip("Camera distance factor per step: 0.6 = each step 40% closer"));
        samplesSpinner.setTooltip(new Tooltip("Samples per thumbnail; few are enough to judge detail"));

        HBox controls = new HBox(6,
                new Label("Targets"), targetsSpinner,
                new Label("Steps"), stepsSpinner,
                new Label("Shrink"), shrinkSpinner,
                new Label("Samples"), samplesSpinner,
                exploreBtn, cancelBtn);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8));
        exploreBtn.setDefaultButton(true);
        exploreBtn.setOnAction(e -> start());
        cancelBtn.setOnAction(e -> cancelled = true);
        cancelBtn.setDisable(true);

        grid.setPadding(new Insets(8));
        grid.setPrefWrapLength(THUMB_W * 3 + 8 * 4);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);

        progress.setPrefWidth(220);
        status.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, status, spacer, progress);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(6, 8, 6, 8));

        BorderPane root = new BorderPane(scroll, controls, null, bottom, null);
        Scene scene = new Scene(root, THUMB_W * 3 + 8 * 4 + 24, 700);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage = new Stage();
        stage.setTitle("Explore");
        stage.initOwner(owner);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> cancelled = true);
    }

    public void show() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    public boolean isRunning() { return running; }

    /** The search, off the FX thread; the scene camera is snapshotted and restored. */
    private void start() {
        if (running) return;
        AbstractFractalParams params = host.params();
        Camera cam = params.getCamera();
        float[] eye0 = cam.getPosition().clone();
        float[] q0 = cam.getQuaternion().clone();
        float fov0 = params.getFov();
        float[] fwd0 = cam.getForwardVector().clone();
        float fovDeg = (float) Math.toDegrees(fov0);
        Settings settings = new Settings(targetsSpinner.getValue(), stepsSpinner.getValue(),
                shrinkSpinner.getValue().floatValue(), samplesSpinner.getValue());

        running = true;
        cancelled = false;
        grid.getChildren().clear();
        tiles.clear();
        selected = null;
        exploreBtn.setDisable(true);
        cancelBtn.setDisable(false);
        progress.setProgress(0);
        host.exploringChanged(true);

        CameraExplorer.Listener listener = new CameraExplorer.Listener() {
            @Override public void candidate(Candidate c) { Platform.runLater(() -> addTile(c)); }
            @Override public void status(double p, String message) {
                Platform.runLater(() -> { progress.setProgress(p); status.setText(message); });
            }
        };

        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                ControllerViewRenderer renderer = new ControllerViewRenderer(
                        controller, params, THUMB_W, THUMB_H, () -> cancelled);
                new CameraExplorer(renderer, listener, () -> cancelled).explore(eye0, fwd0, fovDeg, settings);
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                // The explorer drove the scene camera; give the user theirs back.
                cam.setPosition(eye0[0], eye0[1], eye0[2]);
                cam.setQuaternion(q0[0], q0[1], q0[2], q0[3]);
                params.setFov(fov0);
                controller.restoreViewportSize();
            }
            String err = failure;
            Platform.runLater(() -> finish(err));
        }, "explore");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(String failure) {
        running = false;
        exploreBtn.setDisable(false);
        cancelBtn.setDisable(true);
        if (failure != null) status.setText("Exploration failed: " + failure);
        else if (cancelled) status.setText("Cancelled. " + tiles.size() + " views kept; click one to fly there.");
        else if (tiles.isEmpty()) status.setText("Nothing found from here.");
        else status.setText(tiles.size() + " views, best first. Click one to fly there, then explore again to go deeper.");
        progress.setProgress(failure != null || cancelled ? 0 : 1);
        host.exploringChanged(false);
    }

    /** A thumbnail with its score; the grid keeps them sorted best first. */
    private final class Tile extends VBox {
        final Candidate candidate;

        Tile(Candidate c) {
            super(3);
            this.candidate = c;
            ImageView view = new ImageView(toFx(c.thumbnail()));
            view.setFitWidth(THUMB_W);
            view.setFitHeight(THUMB_H);
            Label caption = new Label(String.format(Locale.ROOT, "%s  ·  score %.0f  ·  detail %.0f  ·  %d%% surface  ·  dist %.3f",
                    c.label(), c.aesthetic(), c.score().detail(), Math.round(c.score().coverage() * 100), c.camDist()));
            caption.getStyleClass().add("small-label");
            caption.setMaxWidth(THUMB_W);
            caption.setWrapText(true);
            getChildren().addAll(view, caption);
            setPadding(new Insets(3));
            setStyle("-fx-border-color: transparent; -fx-border-width: 2;");
            setOnMouseClicked(e -> pick());
            Tooltip.install(this, new Tooltip("Click to move the camera here"));
        }

        void pick() {
            if (running) return;
            if (selected != null) selected.setStyle("-fx-border-color: transparent; -fx-border-width: 2;");
            selected = this;
            setStyle("-fx-border-color: #00BCD4; -fx-border-width: 2;");
            host.flyTo(candidate.eye(), candidate.target(), candidate.fov());
            status.setText("Camera moved to " + candidate.label() + ". Explore again to go deeper from here.");
        }
    }

    private void addTile(Candidate c) {
        Tile t = new Tile(c);
        tiles.add(t);
        tiles.sort((a, b) -> Double.compare(b.candidate.aesthetic(), a.candidate.aesthetic()));
        grid.getChildren().setAll(tiles);
    }

    static WritableImage toFx(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < argb.length; i++) argb[i] |= 0xFF000000;
        WritableImage fx = new WritableImage(w, h);
        fx.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return fx;
    }

    /** For tests: the candidates currently shown, best first. */
    public List<Candidate> shownCandidates() {
        List<Candidate> out = new ArrayList<>();
        for (Tile t : tiles) out.add(t.candidate);
        return out;
    }

    /** For tests: feed a candidate as the explorer would. */
    void offer(Candidate c) { addTile(c); }

    /** For tests: click the n-th shown thumbnail (best first). */
    void clickShown(int index) { tiles.get(index).pick(); }

    /** For tests: the dialog's scene graph, to check what is actually on it. */
    javafx.scene.Parent root() { return stage.getScene().getRoot(); }

    Supplier<Boolean> cancelFlag() { return () -> cancelled; }
}
