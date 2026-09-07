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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fractalizer.explore.ChainProspector;
import org.fractalizer.explore.ChainProspector.Discovery;
import org.fractalizer.explore.ChainProspector.Settings;
import org.fractalizer.explore.ControllerChainRenderer;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.NodeGraphParams;
import org.fractalizer.graph.HybridPresets;
import org.fractalizer.ui.GLSLFractalizerController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Presets &amp; Chains": what the app can show, as pictures, and what it can find. One
 * tab of the shipped {@code .frac} presets, one of the hybrid chain library, each entry a
 * thumbnail with its name; a click loads it. The thumbnails are rendered ahead of time by
 * {@code test/ThumbnailForge} and shipped under {@code /thumbs}, because rendering thirty
 * scenes on open — with a shader compile each — is not something to wait for.
 *
 * <p>The third tab, <b>Discoveries</b>, is the {@link ChainProspector} in the app: random
 * chains of the 28 step types, compiled and rendered on the GPU while you wait (about ten
 * seconds a structure, once per machine), scored, the known families left out, the rest
 * shown best first as they arrive. A click makes one the scene. The search drives the
 * controller from a worker thread on a throw-away scene: the host pauses its preview loop
 * for the duration and the user's own scene is put back afterwards, as Explore does.
 */
public class SceneBrowser {

    /** What the browser asks of the main window. Calls arrive on the FX thread. */
    public interface Host {
        void loadPreset(String name);
        void loadChain(HybridPresets.Preset preset);
        /** The scene's params. A search swaps them out for its duration and puts them
         *  back unchanged. */
        AbstractFractalParams params();
        /** A search is starting (true) or over (false): pause / resume the render loop. */
        void prospectingChanged(boolean on);
        /** Make a discovery the scene: a fresh node graph, the chain at its root. */
        void loadDiscovery(NodeGraphParams scene, String label);
    }

    /** One shipped preset, from {@code /thumbs/presets/index.txt}. */
    public record PresetEntry(String name, String type, String description) {}

    static final int THUMB_W = 320, THUMB_H = 180;
    static final String THUMBS = "/thumbs/";
    /** Draws per structure and samples per thumbnail when prospecting from the app. */
    static final int DRAWS = 8, SAMPLES = 6;

    private final Stage stage;
    private final Host host;
    private final GLSLFractalizerController controller;
    private final List<PresetEntry> presets;
    private final TabPane tabs = new TabPane();
    private final FlowPane presetGrid = new FlowPane(8, 8);
    private final FlowPane chainGrid = new FlowPane(8, 8);

    // Discoveries
    private final FlowPane foundGrid = new FlowPane(8, 8);
    private final List<DiscoveryTile> foundTiles = new ArrayList<>();
    private final Button prospectBtn = new Button("Prospect");
    private final Button stopBtn = new Button("Stop");
    private final Spinner<Integer> structuresSpinner = new Spinner<>(1, 200, 10);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label status = new Label("Nothing searched yet.");
    private volatile boolean cancelled;
    private boolean running;
    private Runnable pendingPick;
    private int knownLeftOut;

    /** @param controller the GPU controller the Discoveries tab searches on; null in tests,
     *                    which disables prospecting and nothing else. */
    public SceneBrowser(Window owner, GLSLFractalizerController controller, Host host) {
        this.host = host;
        this.controller = controller;
        this.presets = shippedPresets();
        for (PresetEntry p : presets) {
            String caption = p.name().replace('_', ' ');
            String tip = p.description().isBlank() ? p.type() : p.type() + " — " + p.description();
            presetGrid.getChildren().add(tile(THUMBS + "presets/" + p.name() + ".jpg", caption, tip,
                    () -> host.loadPreset(p.name())));
        }
        for (HybridPresets.Preset p : HybridPresets.all()) {
            chainGrid.getChildren().add(tile(THUMBS + "chains/" + HybridPresets.key(p) + ".jpg", p.name(),
                    p.description(), () -> host.loadChain(p)));
        }
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Presets (" + presets.size() + ")", scroll(presetGrid,
                "Scenes shipped with the app, as File > Load would open them. Click one to load it.")));
        tabs.getTabs().add(new Tab("Hybrid chains (" + HybridPresets.all().size() + ")", scroll(chainGrid,
                "Formulas composed inside one iteration loop. Click one to load it as the scene's node graph, "
                + "framed as in its thumbnail; the Node Graph editor then shows its steps.")));
        tabs.getTabs().add(discoveriesTab());

        Scene scene = new Scene(tabs, THUMB_W * 3 + 8 * 4 + 40, 720);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage = new Stage();
        stage.setTitle("Presets & Chains");
        stage.initOwner(owner);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> cancelled = true);
    }

    public void show() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /** Open the browser on the Discoveries tab. */
    public void showDiscoveries() {
        tabs.getSelectionModel().select(2);
        show();
    }

    private static ScrollPane scroll(FlowPane grid, String hint) {
        grid.setPadding(new Insets(8));
        grid.setPrefWrapLength(THUMB_W * 3 + 8 * 4);
        Label h = new Label(hint);
        h.setWrapText(true);
        h.getStyleClass().add("hint-label");
        h.setPadding(new Insets(8, 8, 0, 8));
        VBox box = new VBox(4, h, grid);
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        return sp;
    }

    // ------------------------------------------------------------ Discoveries

    private Tab discoveriesTab() {
        Label hint = new Label("Let the app look for fractals nobody has made. It draws chains of the 28 hybrid steps, "
                + "compiles and renders each on the GPU (about ten seconds a structure), keeps what is neither "
                + "empty nor a known family, and shows the rest here best first as it goes. Click one to make it "
                + "the scene; File > Save keeps it. The scene you had is put back when the search ends.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint-label");
        hint.setPadding(new Insets(8, 8, 0, 8));

        prospectBtn.setTooltip(new Tooltip("Search that many chain structures, eight parameter draws each"));
        prospectBtn.setOnAction(e -> startProspecting());
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> { cancelled = true; status.setText("Stopping after this draw…"); });
        structuresSpinner.setEditable(true);
        structuresSpinner.setPrefWidth(80);
        structuresSpinner.setTooltip(new Tooltip("Structures to compile: ten is about two minutes"));
        progress.setPrefWidth(160);
        status.setWrapText(true);
        status.getStyleClass().add("small-label");
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox bar = new HBox(8, prospectBtn, stopBtn, new Label("structures:"), structuresSpinner, progress, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        foundGrid.setPadding(new Insets(8));
        foundGrid.setPrefWrapLength(THUMB_W * 3 + 8 * 4);
        ScrollPane sp = new ScrollPane(foundGrid);
        sp.setFitToWidth(true);
        VBox box = new VBox(4, hint, bar, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        return new Tab("Discoveries", box);
    }

    /** The search, off the FX thread, on a throw-away scene; the user's scene is put back
     *  when it ends, whatever happened. */
    void startProspecting() {
        if (running || controller == null) return;
        AbstractFractalParams original = host.params();
        NodeGraphParams search = new NodeGraphParams();
        HybridPresets.showcaseLook(search);
        int structures = structuresSpinner.getValue();

        running = true;
        cancelled = false;
        pendingPick = null;
        knownLeftOut = 0;
        foundGrid.getChildren().clear();
        foundTiles.clear();
        prospectBtn.setDisable(true);
        stopBtn.setDisable(false);
        progress.setProgress(0);
        status.setText("Starting…");
        host.prospectingChanged(true);

        Settings settings = new Settings(structures, DRAWS, SAMPLES, System.nanoTime() & 0xFFFF, THUMB_W, THUMB_H);
        ChainProspector.Listener listener = new ChainProspector.Listener() {
            @Override public void found(Discovery d) { Platform.runLater(() -> offer(d)); }
            @Override public void status(double p, String message) {
                Platform.runLater(() -> { progress.setProgress(p); status.setText(message); });
            }
        };
        Thread worker = new Thread(() -> {
            String failure = null;
            try {
                ControllerChainRenderer renderer = new ControllerChainRenderer(controller, search, () -> cancelled);
                new ChainProspector(renderer, listener, () -> cancelled).prospect(settings);
            } catch (Exception ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                try {
                    // The search compiled its chains into the node-graph program; the user's
                    // scene must be compiled back (from the driver's cache, so in a moment).
                    if (original instanceof NodeGraphParams ngp) ngp.markDirty();
                    controller.replaceParams(original);
                    controller.updatePaletteTexture(original.getCustomGradient());
                    controller.restoreViewportSize();
                } catch (Exception ignored) {
                    // the host re-renders on prospectingChanged(false) either way
                }
            }
            String err = failure;
            Platform.runLater(() -> finish(err));
        }, "prospect");
        worker.setDaemon(true);
        worker.start();
    }

    /** A find arrives: a new one becomes a tile, best first; a known family is counted and
     *  left out. On the FX thread. */
    void offer(Discovery d) {
        if (!d.isNew()) { knownLeftOut++; return; }
        foundTiles.add(new DiscoveryTile(d));
        foundTiles.sort((a, b) -> Double.compare(b.discovery.score(), a.discovery.score()));
        foundGrid.getChildren().setAll(foundTiles);
    }

    private void finish(String failure) {
        running = false;
        prospectBtn.setDisable(false);
        stopBtn.setDisable(true);
        String known = knownLeftOut > 0 ? " (" + knownLeftOut + " known families left out)" : "";
        if (failure != null) status.setText("Prospecting failed: " + failure);
        else if (cancelled) status.setText("Stopped. " + foundTiles.size() + " found" + known + ". Click one to make it the scene.");
        else if (foundTiles.isEmpty()) status.setText("Nothing worth keeping this time" + known + ". Prospect again: every run draws new chains.");
        else status.setText(foundTiles.size() + " discoveries, best first" + known + ". Click one to make it the scene; File > Save keeps it.");
        progress.setProgress(failure != null || cancelled ? 0 : 1);
        host.prospectingChanged(false);
        if (pendingPick != null) {
            Runnable pick = pendingPick;
            pendingPick = null;
            pick.run();
        }
    }

    /** A discovery as a tile: its picture, its chain, its score. A click makes it the
     *  scene; during a search the search is stopped first and the load follows. */
    private final class DiscoveryTile extends VBox {
        final Discovery discovery;

        DiscoveryTile(Discovery d) {
            super(3);
            this.discovery = d;
            ImageView view = new ImageView(ExploreDialog.toFx(d.thumbnail()));
            view.setFitWidth(THUMB_W);
            view.setFitHeight(THUMB_H);
            Label label = new Label(d.label());
            label.setMaxWidth(THUMB_W);
            label.setWrapText(true);
            Label score = new Label(String.format(Locale.ROOT, "score %.0f  ·  %d%% surface  ·  %s",
                    d.score(), Math.round(d.frame().coverage() * 100), d.recipe()));
            score.getStyleClass().add("small-label");
            getChildren().addAll(view, label, score);
            setPadding(new Insets(3));
            setStyle("-fx-border-color: transparent; -fx-border-width: 2;");
            setOnMouseEntered(e -> setStyle("-fx-border-color: #00BCD4; -fx-border-width: 2;"));
            setOnMouseExited(e -> setStyle("-fx-border-color: transparent; -fx-border-width: 2;"));
            setOnMouseClicked(e -> pick());
            Tooltip.install(this, new Tooltip("Click to make this the scene"));
            setUserData(d.label());
        }

        void pick() {
            Runnable load = () -> host.loadDiscovery(ChainProspector.toParams(discovery), discovery.label());
            if (running) {
                pendingPick = load;
                cancelled = true;
                status.setText("Stopping, then loading " + discovery.label() + "…");
            } else {
                load.run();
            }
        }
    }

    // ---------------------------------------------------------------- tiles

    /** A thumbnail with a caption; a missing picture becomes a dark tile with the name on it. */
    private static VBox tile(String resource, String caption, String tooltip, Runnable onClick) {
        StackPane picture = new StackPane();
        picture.setPrefSize(THUMB_W, THUMB_H);
        picture.setMinSize(THUMB_W, THUMB_H);
        picture.setMaxSize(THUMB_W, THUMB_H);
        Image img = load(resource);
        if (img != null) {
            ImageView view = new ImageView(img);
            view.setFitWidth(THUMB_W);
            view.setFitHeight(THUMB_H);
            picture.getChildren().add(view);
        } else {
            picture.setStyle("-fx-background-color: #202028; -fx-border-color: #444;");
            Label missing = new Label(caption + "\n(no thumbnail yet)");
            missing.setStyle("-fx-text-fill: #888;");
            missing.setWrapText(true);
            missing.setAlignment(Pos.CENTER);
            picture.getChildren().add(missing);
        }
        Label label = new Label(caption);
        label.setMaxWidth(THUMB_W);
        label.setWrapText(true);
        VBox box = new VBox(3, picture, label);
        box.setPadding(new Insets(3));
        box.setStyle("-fx-border-color: transparent; -fx-border-width: 2;");
        box.setOnMouseEntered(e -> box.setStyle("-fx-border-color: #00BCD4; -fx-border-width: 2;"));
        box.setOnMouseExited(e -> box.setStyle("-fx-border-color: transparent; -fx-border-width: 2;"));
        box.setOnMouseClicked(e -> onClick.run());
        if (tooltip != null && !tooltip.isBlank()) Tooltip.install(box, new Tooltip(tooltip));
        box.setUserData(caption);
        return box;
    }

    static Image load(String resource) {
        try (InputStream in = SceneBrowser.class.getResourceAsStream(resource)) {
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** The shipped index, or — when no thumbnails were forged yet — the presets folder on disk. */
    static List<PresetEntry> shippedPresets() {
        List<PresetEntry> out = new ArrayList<>();
        try (InputStream in = SceneBrowser.class.getResourceAsStream(THUMBS + "presets/index.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] t = line.split("\\|", 3);
                    out.add(new PresetEntry(t[0], t.length > 1 ? t[1] : "", t.length > 2 ? t[2] : ""));
                }
                return out;
            }
        } catch (Exception ignored) {
            // fall through to the folder
        }
        File[] files = new File("presets").listFiles((d, n) -> n.endsWith(".frac"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) out.add(new PresetEntry(f.getName().replaceFirst("\\.frac$", ""), "", ""));
        }
        return out;
    }

    // ---------------------------------------------------------------- for tests

    /** The preset names shown, in order. */
    public List<String> presetNames() {
        List<String> names = new ArrayList<>();
        for (PresetEntry p : presets) names.add(p.name());
        return names;
    }

    /** Click the n-th tile of a tab (0 = presets, 1 = chains, 2 = discoveries). */
    void clickTile(int tab, int index) {
        grid(tab).getChildren().get(index).getOnMouseClicked().handle(null);
    }

    int tileCount(int tab) { return grid(tab).getChildren().size(); }

    int tabCount() { return tabs.getTabs().size(); }

    /** The discoveries shown, in the order shown. */
    List<String> discoveryLabels() {
        List<String> out = new ArrayList<>();
        for (DiscoveryTile t : foundTiles) out.add(t.discovery.label());
        return out;
    }

    private FlowPane grid(int tab) {
        return switch (tab) { case 0 -> presetGrid; case 1 -> chainGrid; default -> foundGrid; };
    }

    javafx.scene.Parent root() { return stage.getScene().getRoot(); }
}
