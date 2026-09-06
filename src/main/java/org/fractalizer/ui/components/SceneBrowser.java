package org.fractalizer.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fractalizer.graph.HybridPresets;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "Presets &amp; Chains": what the app can show, as pictures. One tab of the shipped
 * {@code .frac} presets, one of the hybrid chain library, each entry a thumbnail with
 * its name; a click loads it. The thumbnails are rendered ahead of time by
 * {@code test/ThumbnailForge} and shipped under {@code /thumbs}, because rendering
 * thirty scenes on open — with a shader compile each — is not something to wait for.
 */
public class SceneBrowser {

    /** What the browser asks of the main window. Calls arrive on the FX thread. */
    public interface Host {
        void loadPreset(String name);
        void loadChain(HybridPresets.Preset preset);
    }

    /** One shipped preset, from {@code /thumbs/presets/index.txt}. */
    public record PresetEntry(String name, String type, String description) {}

    static final int THUMB_W = 320, THUMB_H = 180;
    static final String THUMBS = "/thumbs/";

    private final Stage stage;
    private final Host host;
    private final List<PresetEntry> presets;
    private final FlowPane presetGrid = new FlowPane(8, 8);
    private final FlowPane chainGrid = new FlowPane(8, 8);

    public SceneBrowser(Window owner, Host host) {
        this.host = host;
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

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Presets (" + presets.size() + ")", scroll(presetGrid,
                "Scenes shipped with the app, as File > Load would open them. Click one to load it.")));
        tabs.getTabs().add(new Tab("Hybrid chains (" + HybridPresets.all().size() + ")", scroll(chainGrid,
                "Formulas composed inside one iteration loop. Click one to load it as the scene's node graph, "
                + "framed as in its thumbnail; the Node Graph editor then shows its steps.")));

        Scene scene = new Scene(tabs, THUMB_W * 3 + 8 * 4 + 40, 720);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        stage = new Stage();
        stage.setTitle("Presets & Chains");
        stage.initOwner(owner);
        stage.setScene(scene);
    }

    public void show() {
        stage.show();
        stage.toFront();
        stage.requestFocus();
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

    /** For tests: the preset names shown, in order. */
    public List<String> presetNames() {
        List<String> names = new ArrayList<>();
        for (PresetEntry p : presets) names.add(p.name());
        return names;
    }

    /** For tests: click the n-th tile of a tab (0 = presets, 1 = chains). */
    void clickTile(int tab, int index) {
        FlowPane grid = tab == 0 ? presetGrid : chainGrid;
        grid.getChildren().get(index).getOnMouseClicked().handle(null);
    }

    int tileCount(int tab) { return (tab == 0 ? presetGrid : chainGrid).getChildren().size(); }

    javafx.scene.Parent root() { return stage.getScene().getRoot(); }
}
