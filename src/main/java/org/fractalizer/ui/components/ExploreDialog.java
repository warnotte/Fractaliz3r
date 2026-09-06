package org.fractalizer.ui.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
import org.fractalizer.explore.ParamExplorer;
import org.fractalizer.explore.ParamExplorer.Variant;
import org.fractalizer.explore.ParamKnobs;
import org.fractalizer.explore.ParamKnobs.Knob;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.GLSLFractalizerController;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * "Explore": two ways of asking the app what else there is to see.
 *
 * <b>Views</b> looks for detailed framings from where the camera is and shows them as
 * scored thumbnails; click one to fly there, then explore again from there to go deeper.
 * The search is {@link CameraExplorer}, the traveller from the navigator harness, run on
 * the GPU at thumbnail size.
 *
 * <b>Variations</b> keeps the camera and nudges the scene's parameters instead — the
 * Julia constant, the power, a fold's scale — renders each nudge and scores it the same
 * way; click one to make it the scene. The search is {@link ParamExplorer}.
 *
 * While a search runs the host pauses its own preview loop (the search drives the scene
 * camera, the parameters and the engine size), and the thumbnails are not clickable. When
 * it ends, the camera and parameters the user had are back — changing the scene is a
 * click, never a side effect of searching.
 */
public class ExploreDialog {

    /** What the dialog needs from the main window. All calls arrive on the FX thread. */
    public interface Host {
        AbstractFractalParams params();
        /** The search is starting (true) or over (false): pause / resume the render loop. */
        void exploringChanged(boolean exploring);
        /** Put the camera at {@code eye} looking at {@code target} and re-render. */
        void flyTo(float[] eye, float[] target, float fovDeg);
        /** Parameters were written to the scene; refresh the panels and re-render. */
        void paramsChanged();
    }

    static final int THUMB_W = 320, THUMB_H = 180;
    private static final String PLAIN = "-fx-border-color: transparent; -fx-border-width: 2;";
    private static final String PICKED = "-fx-border-color: #00BCD4; -fx-border-width: 2;";

    private final Stage stage;
    private final GLSLFractalizerController controller;
    private final Host host;

    // Views
    private final Spinner<Integer> targetsSpinner = new Spinner<>(1, 5, 3);
    private final Spinner<Integer> stepsSpinner = new Spinner<>(2, 8, 4);
    private final Spinner<Double> shrinkSpinner = new Spinner<>(0.4, 0.85, 0.6, 0.05);
    private final Spinner<Integer> samplesSpinner = new Spinner<>(1, 16, 4);
    private final Button exploreBtn = new Button("Explore from current view");
    private final FlowPane viewGrid = new FlowPane(8, 8);
    private final List<Tile> viewTiles = new ArrayList<>();

    // Variations
    private final Spinner<Integer> countSpinner = new Spinner<>(2, 24, 12);
    private final Spinner<Integer> amplitudeSpinner = new Spinner<>(5, 80, 20, 5);
    private final Spinner<Integer> varSamplesSpinner = new Spinner<>(1, 16, 4);
    private final Button varyBtn = new Button("Vary from current scene");
    private final Button restoreBtn = new Button("Restore original");
    private final VBox knobBox = new VBox(2);
    private final List<CheckBox> knobChecks = new ArrayList<>();
    private final FlowPane varGrid = new FlowPane(8, 8);
    private final List<Tile> varTiles = new ArrayList<>();
    private List<Knob> knobs = List.of();
    private double[] originalValues;

    // Shared
    private final Button cancelBtn = new Button("Cancel");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Views: detailed framings around what the camera sees. "
            + "Variations: the scene's parameters nudged, same camera.");
    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private Tile selected;

    public ExploreDialog(Window owner, GLSLFractalizerController controller, Host host) {
        this.controller = controller;
        this.host = host;

        for (Spinner<?> sp : List.of(targetsSpinner, stepsSpinner, shrinkSpinner, samplesSpinner,
                countSpinner, amplitudeSpinner, varSamplesSpinner)) {
            sp.setPrefWidth(70);
            sp.setEditable(false);
        }
        targetsSpinner.setTooltip(new Tooltip("Aim points kept after the 3x3 scan; each gets its own dive"));
        stepsSpinner.setTooltip(new Tooltip("Dive steps per target"));
        shrinkSpinner.setTooltip(new Tooltip("Camera distance factor per step: 0.6 = each step 40% closer"));
        samplesSpinner.setTooltip(new Tooltip("Samples per thumbnail; few are enough to judge detail"));
        countSpinner.setTooltip(new Tooltip("Variants to render, besides the current scene"));
        amplitudeSpinner.setTooltip(new Tooltip("Size of a nudge, in percent of each parameter's own scale"));
        varSamplesSpinner.setTooltip(new Tooltip("Samples per thumbnail"));

        // --- Views tab
        HBox viewControls = new HBox(6,
                new Label("Targets"), targetsSpinner,
                new Label("Steps"), stepsSpinner,
                new Label("Shrink"), shrinkSpinner,
                new Label("Samples"), samplesSpinner,
                exploreBtn);
        viewControls.setAlignment(Pos.CENTER_LEFT);
        viewControls.setPadding(new Insets(8));
        exploreBtn.setOnAction(e -> startViews());
        BorderPane viewsPane = new BorderPane(scroll(viewGrid), viewControls, null, null, null);

        // --- Variations tab
        HBox varControls = new HBox(6,
                new Label("Count"), countSpinner,
                new Label("Amplitude %"), amplitudeSpinner,
                new Label("Samples"), varSamplesSpinner,
                varyBtn, restoreBtn);
        varControls.setAlignment(Pos.CENTER_LEFT);
        varControls.setPadding(new Insets(8));
        varyBtn.setOnAction(e -> startVariations());
        restoreBtn.setOnAction(e -> restoreOriginal());
        restoreBtn.setDisable(true);
        Label knobTitle = new Label("Parameters to vary");
        knobTitle.getStyleClass().add("small-label");
        ScrollPane knobScroll = new ScrollPane(knobBox);
        knobScroll.setFitToWidth(true);
        knobScroll.setPrefHeight(120);
        knobBox.setPadding(new Insets(4, 8, 4, 8));
        VBox varTop = new VBox(4, varControls, knobTitle, knobScroll);
        BorderPane varPane = new BorderPane(scroll(varGrid), varTop, null, null, null);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Views", viewsPane));
        tabs.getTabs().add(new Tab("Variations", varPane));
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n.intValue() == 1) refreshKnobs();
        });

        // --- shared bottom bar
        cancelBtn.setOnAction(e -> cancelled = true);
        cancelBtn.setDisable(true);
        progress.setPrefWidth(220);
        status.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, status, spacer, cancelBtn, progress);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(6, 8, 6, 8));

        BorderPane root = new BorderPane(tabs, null, null, bottom, null);
        Scene scene = new Scene(root, THUMB_W * 3 + 8 * 4 + 24, 740);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage = new Stage();
        stage.setTitle("Explore");
        stage.initOwner(owner);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> cancelled = true);
    }

    private static ScrollPane scroll(FlowPane grid) {
        grid.setPadding(new Insets(8));
        grid.setPrefWrapLength(THUMB_W * 3 + 8 * 4);
        ScrollPane sp = new ScrollPane(grid);
        sp.setFitToWidth(true);
        return sp;
    }

    public void show() {
        refreshKnobs();
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    public boolean isRunning() { return running; }

    // ------------------------------------------------------------------ Views

    /** The camera search, off the FX thread; the scene camera is snapshotted and restored. */
    private void startViews() {
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

        beginRun(viewGrid, viewTiles);
        CameraExplorer.Listener listener = new CameraExplorer.Listener() {
            @Override public void candidate(Candidate c) {
                Platform.runLater(() -> addTile(viewGrid, viewTiles, new Tile(c.thumbnail(), viewCaption(c), c.aesthetic(),
                        () -> { host.flyTo(c.eye(), c.target(), c.fov());
                                status.setText("Camera moved to " + c.label() + ". Explore again to go deeper from here."); })));
            }
            @Override public void status(double p, String message) { Platform.runLater(() -> { progress.setProgress(p); status.setText(message); }); }
        };
        runOffFx(() -> {
            ControllerViewRenderer renderer = new ControllerViewRenderer(controller, params, THUMB_W, THUMB_H, () -> cancelled);
            new CameraExplorer(renderer, listener, () -> cancelled).explore(eye0, fwd0, fovDeg, settings);
        }, () -> {
            cam.setPosition(eye0[0], eye0[1], eye0[2]);
            cam.setQuaternion(q0[0], q0[1], q0[2], q0[3]);
            params.setFov(fov0);
        }, viewTiles, "views, best first. Click one to fly there, then explore again to go deeper.");
    }

    private static String viewCaption(Candidate c) {
        return String.format(Locale.ROOT, "%s  ·  score %.0f  ·  detail %.0f  ·  %d%% surface  ·  dist %.3f",
                c.label(), c.aesthetic(), c.score().detail(), Math.round(c.score().coverage() * 100), c.camDist());
    }

    // ------------------------------------------------------------- Variations

    /** Rebuild the knob list from the current scene (its graph may have changed). */
    void refreshKnobs() {
        if (running) return;
        knobs = ParamKnobs.of(host.params());
        knobBox.getChildren().clear();
        knobChecks.clear();
        for (Knob k : knobs) {
            CheckBox cb = new CheckBox(k.name() + String.format(Locale.ROOT, "  (%.3g)", k.value()));
            cb.setSelected(k.interestingByDefault());
            knobChecks.add(cb);
            knobBox.getChildren().add(cb);
        }
        if (knobs.isEmpty()) knobBox.getChildren().add(new Label("This scene has no numeric parameters to vary."));
    }

    private List<Knob> chosenKnobs() {
        List<Knob> chosen = new ArrayList<>();
        for (int i = 0; i < knobs.size(); i++) if (knobChecks.get(i).isSelected()) chosen.add(knobs.get(i));
        return chosen;
    }

    private void startVariations() {
        if (running) return;
        List<Knob> chosen = chosenKnobs();
        if (chosen.isEmpty()) { status.setText("Tick at least one parameter to vary."); return; }
        AbstractFractalParams params = host.params();
        Camera cam = params.getCamera();
        float[] eye = cam.getPosition().clone();
        float[] target = cam.getTarget().clone();
        float fovDeg = (float) Math.toDegrees(params.getFov());
        float[] q0 = cam.getQuaternion().clone();
        float fov0 = params.getFov();
        int count = countSpinner.getValue();
        double amplitude = amplitudeSpinner.getValue() / 100.0;
        int samples = varSamplesSpinner.getValue();
        long seed = System.nanoTime();

        originalValues = new double[chosen.size()];
        for (int i = 0; i < chosen.size(); i++) originalValues[i] = chosen.get(i).value();
        final List<Knob> varied = chosen;

        beginRun(varGrid, varTiles);
        ParamExplorer.Listener listener = new ParamExplorer.Listener() {
            @Override public void variant(Variant v) {
                Platform.runLater(() -> addTile(varGrid, varTiles, new Tile(v.thumbnail(), varCaption(v), v.aesthetic(),
                        () -> { ParamExplorer.apply(varied, v.values());
                                restoreBtn.setDisable(false);
                                host.paramsChanged();
                                status.setText("Scene set to " + v.label() + ". Vary again from here, or restore the original."); })));
            }
            @Override public void status(double p, String message) { Platform.runLater(() -> { progress.setProgress(p); status.setText(message); }); }
        };
        runOffFx(() -> {
            ControllerViewRenderer renderer = new ControllerViewRenderer(controller, params, THUMB_W, THUMB_H, () -> cancelled);
            new ParamExplorer(renderer, listener, () -> cancelled).explore(varied, count, amplitude, seed, eye, target, fovDeg, samples);
        }, () -> {
            ParamExplorer.apply(varied, originalValues);
            cam.setPosition(eye[0], eye[1], eye[2]);
            cam.setQuaternion(q0[0], q0[1], q0[2], q0[3]);
            params.setFov(fov0);
        }, varTiles, "variations, best first. Click one to make it the scene.");
    }

    private static String varCaption(Variant v) {
        return String.format(Locale.ROOT, "%s  ·  score %.0f  ·  detail %.0f  ·  %d%% surface",
                v.label(), v.aesthetic(), v.score().detail(), Math.round(v.score().coverage() * 100));
    }

    private void restoreOriginal() {
        if (running || originalValues == null) return;
        List<Knob> chosen = chosenKnobs();
        if (chosen.size() != originalValues.length) return;
        ParamExplorer.apply(chosen, originalValues);
        if (selected != null) selected.setStyle(PLAIN);
        selected = null;
        host.paramsChanged();
        status.setText("Original parameters restored.");
    }

    // ---------------------------------------------------------------- shared

    private void beginRun(FlowPane grid, List<Tile> tiles) {
        running = true;
        cancelled = false;
        grid.getChildren().clear();
        tiles.clear();
        selected = null;
        exploreBtn.setDisable(true);
        varyBtn.setDisable(true);
        cancelBtn.setDisable(false);
        progress.setProgress(0);
        host.exploringChanged(true);
    }

    /** Run a search on a worker thread; {@code restore} puts the scene back, then the
     *  FX thread is told the run is over. */
    private void runOffFx(Runnable search, Runnable restore, List<Tile> tiles, String doneMessage) {
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                search.run();
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                restore.run();
                controller.restoreViewportSize();
            }
            String err = failure;
            Platform.runLater(() -> finish(err, tiles, doneMessage));
        }, "explore");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(String failure, List<Tile> tiles, String doneMessage) {
        running = false;
        exploreBtn.setDisable(false);
        varyBtn.setDisable(false);
        cancelBtn.setDisable(true);
        if (failure != null) status.setText("Exploration failed: " + failure);
        else if (cancelled) status.setText("Cancelled. " + tiles.size() + " kept; click one to use it.");
        else if (tiles.isEmpty()) status.setText("Nothing found from here.");
        else status.setText(tiles.size() + " " + doneMessage);
        progress.setProgress(failure != null || cancelled ? 0 : 1);
        host.exploringChanged(false);
    }

    /** A thumbnail with its caption; a grid keeps them sorted best first. */
    private final class Tile extends VBox {
        final double score;
        final Runnable onPick;

        Tile(BufferedImage image, String caption, double score, Runnable onPick) {
            super(3);
            this.score = score;
            this.onPick = onPick;
            ImageView view = new ImageView(toFx(image));
            view.setFitWidth(THUMB_W);
            view.setFitHeight(THUMB_H);
            Label label = new Label(caption);
            label.getStyleClass().add("small-label");
            label.setMaxWidth(THUMB_W);
            label.setWrapText(true);
            getChildren().addAll(view, label);
            setPadding(new Insets(3));
            setStyle(PLAIN);
            setOnMouseClicked(e -> pick());
            Tooltip.install(this, new Tooltip("Click to use this one"));
        }

        void pick() {
            if (running) return;
            if (selected != null) selected.setStyle(PLAIN);
            selected = this;
            setStyle(PICKED);
            onPick.run();
        }
    }

    private void addTile(FlowPane grid, List<Tile> tiles, Tile t) {
        tiles.add(t);
        tiles.sort((a, b) -> Double.compare(b.score, a.score));
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

    // ------------------------------------------------------------- for tests

    /** The view candidates currently shown, best first. */
    public List<Candidate> shownCandidates() {
        List<Candidate> out = new ArrayList<>();
        for (Tile t : viewTiles) out.add(shownViews.get(t));
        return out;
    }

    private final java.util.IdentityHashMap<Tile, Candidate> shownViews = new java.util.IdentityHashMap<>();

    /** Feed a view candidate as the explorer would. */
    void offer(Candidate c) {
        Tile t = new Tile(c.thumbnail(), viewCaption(c), c.aesthetic(), () -> host.flyTo(c.eye(), c.target(), c.fov()));
        shownViews.put(t, c);
        addTile(viewGrid, viewTiles, t);
    }

    /** Feed a variant as the explorer would; a click applies its values to {@code knobs}. */
    void offer(Variant v, List<Knob> varied) {
        addTile(varGrid, varTiles, new Tile(v.thumbnail(), varCaption(v), v.aesthetic(),
                () -> { ParamExplorer.apply(varied, v.values()); host.paramsChanged(); }));
    }

    /** Click the n-th shown thumbnail (best first) of a tab: 0 = views, 1 = variations. */
    void clickShown(int tab, int index) { (tab == 0 ? viewTiles : varTiles).get(index).pick(); }

    /** Names of the knobs offered for the current scene, ticked or not. */
    List<String> knobNames() {
        List<String> out = new ArrayList<>();
        for (Knob k : knobs) out.add(k.name());
        return out;
    }

    javafx.scene.Parent root() { return stage.getScene().getRoot(); }

    Supplier<Boolean> cancelFlag() { return () -> cancelled; }
}
