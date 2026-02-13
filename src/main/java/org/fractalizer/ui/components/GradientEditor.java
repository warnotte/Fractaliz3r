package org.fractalizer.ui.components;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import org.fractalizer.fractals.GradientPalette;
import org.fractalizer.fractals.GradientPalette.ColorStop;

import java.util.List;
import java.util.function.Consumer;

/**
 * Visual gradient editor with draggable color stops.
 * <p>
 * Interactions:
 * - Click on canvas to add a stop at that position
 * - Drag a stop triangle to move it
 * - Double-click a stop to open a color picker
 * - Right-click a stop to delete it (minimum 2 stops)
 */
public class GradientEditor extends VBox {

    private static final double CANVAS_HEIGHT = 50;
    private static final double TRIANGLE_SIZE = 8;
    private static final double STOP_AREA_HEIGHT = 16;

    private final Canvas canvas;
    private GradientPalette palette;
    private Consumer<GradientPalette> onGradientChanged;

    // Drag state
    private int dragIndex = -1;
    private int selectedIndex = -1;

    public GradientEditor() {
        this(new GradientPalette());
    }

    public GradientEditor(GradientPalette palette) {
        this.palette = palette;
        setSpacing(5);
        setPadding(new Insets(5, 0, 5, 0));

        canvas = new Canvas(200, CANVAS_HEIGHT + STOP_AREA_HEIGHT);

        // Wrap Canvas in a resizable Pane so it can shrink properly.
        // Canvas is not resizable in JavaFX — its minWidth equals its current width,
        // which prevents parent containers from shrinking below the canvas's largest size.
        Pane canvasHolder = new Pane(canvas);
        canvasHolder.setMinWidth(0);
        canvasHolder.setPrefHeight(CANVAS_HEIGHT + STOP_AREA_HEIGHT);
        canvasHolder.setMaxHeight(CANVAS_HEIGHT + STOP_AREA_HEIGHT);
        canvas.widthProperty().bind(canvasHolder.widthProperty());

        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(this::handleMouseReleased);

        // Preset buttons (2 rows)
        HBox row1 = new HBox(3);
        row1.getChildren().addAll(
            createPresetBtn("Magma", GradientPalette::createMagma),
            createPresetBtn("Ice", GradientPalette::createIce),
            createPresetBtn("Forest", GradientPalette::createForest),
            createPresetBtn("Neon", GradientPalette::createNeon),
            createPresetBtn("Spectral", GradientPalette::createSpectral)
        );
        HBox row2 = new HBox(3);
        row2.getChildren().addAll(
            createPresetBtn("Sunset", GradientPalette::createSunset),
            createPresetBtn("Ocean", GradientPalette::createOcean),
            createPresetBtn("Aurora", GradientPalette::createAurora),
            createPresetBtn("Pastel", GradientPalette::createPastel),
            createPresetBtn("Mono", GradientPalette::createMonochrome)
        );

        setMinWidth(0);
        getChildren().addAll(canvasHolder, row1, row2);

        // Redraw when holder width changes
        canvasHolder.widthProperty().addListener((obs, old, nw) -> draw());

        draw();
    }

    public void setOnGradientChanged(Consumer<GradientPalette> callback) {
        this.onGradientChanged = callback;
    }

    public GradientPalette getPalette() {
        return palette;
    }

    public void setPalette(GradientPalette palette) {
        this.palette = palette;
        selectedIndex = -1;
        draw();
    }

    private Button createPresetBtn(String name, java.util.function.Supplier<GradientPalette> factory) {
        Button btn = new Button(name);
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setStyle("-fx-font-size: 10px; -fx-padding: 2 4;");
        btn.setOnAction(e -> {
            palette = factory.get();
            selectedIndex = -1;
            draw();
            fireChanged();
        });
        return btn;
    }

    private void draw() {
        double w = canvas.getWidth();
        double gradH = CANVAS_HEIGHT;
        if (w <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, canvas.getHeight());

        // Draw checkerboard for transparency reference
        drawCheckerboard(gc, w, gradH);

        // Draw gradient bar
        List<ColorStop> stops = palette.getStops();
        if (stops.size() >= 2) {
            Stop[] fxStops = new Stop[stops.size()];
            for (int i = 0; i < stops.size(); i++) {
                fxStops[i] = new Stop(stops.get(i).position(), stops.get(i).color());
            }
            LinearGradient grad = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, fxStops);
            gc.setFill(grad);
            gc.fillRect(0, 0, w, gradH);
        }

        // Draw border
        gc.setStroke(Color.gray(0.4));
        gc.setLineWidth(1);
        gc.strokeRect(0.5, 0.5, w - 1, gradH - 1);

        // Draw stop triangles below the gradient
        for (int i = 0; i < stops.size(); i++) {
            drawStopTriangle(gc, stops.get(i), w, gradH, i == selectedIndex);
        }
    }

    private void drawCheckerboard(GraphicsContext gc, double w, double h) {
        int sq = 6;
        for (int y = 0; y < h; y += sq) {
            for (int x = 0; x < w; x += sq) {
                boolean light = ((x / sq) + (y / sq)) % 2 == 0;
                gc.setFill(light ? Color.gray(0.3) : Color.gray(0.2));
                gc.fillRect(x, y, sq, sq);
            }
        }
    }

    private void drawStopTriangle(GraphicsContext gc, ColorStop stop, double canvasWidth, double gradH, boolean selected) {
        double x = stop.position() * canvasWidth;
        double y = gradH;

        // Triangle pointing down
        double[] xPoints = {x - TRIANGLE_SIZE, x + TRIANGLE_SIZE, x};
        double[] yPoints = {y + 1, y + 1, y + STOP_AREA_HEIGHT};

        gc.setFill(stop.color());
        gc.fillPolygon(xPoints, yPoints, 3);

        gc.setStroke(selected ? Color.WHITE : Color.gray(0.6));
        gc.setLineWidth(selected ? 2 : 1);
        gc.strokePolygon(xPoints, yPoints, 3);
    }

    private int findStopAt(double mouseX) {
        double w = canvas.getWidth();
        List<ColorStop> stops = palette.getStops();
        for (int i = 0; i < stops.size(); i++) {
            double sx = stops.get(i).position() * w;
            if (Math.abs(mouseX - sx) < TRIANGLE_SIZE + 2) {
                return i;
            }
        }
        return -1;
    }

    private void handleMousePressed(MouseEvent e) {
        double mx = e.getX();
        double my = e.getY();
        double w = canvas.getWidth();

        int hitIndex = findStopAt(mx);

        if (e.getButton() == MouseButton.PRIMARY) {
            if (e.getClickCount() == 2 && hitIndex >= 0) {
                // Double-click: open color picker
                openColorPicker(hitIndex);
                e.consume();
                return;
            }

            if (hitIndex >= 0) {
                // Start dragging
                dragIndex = hitIndex;
                selectedIndex = hitIndex;
                draw();
            } else if (my < CANVAS_HEIGHT) {
                // Click on gradient area: add stop
                double pos = mx / w;
                Color sampled = palette.sampleAt(pos);
                int newIndex = palette.addStop(pos, sampled);
                selectedIndex = newIndex;
                dragIndex = newIndex;
                draw();
                fireChanged();
            }
        } else if (e.getButton() == MouseButton.SECONDARY && hitIndex >= 0) {
            // Right-click: context menu to delete
            showStopContextMenu(hitIndex, e);
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (dragIndex < 0) return;
        double w = canvas.getWidth();
        double pos = Math.max(0, Math.min(1, e.getX() / w));
        int newIndex = palette.moveStop(dragIndex, pos);
        dragIndex = newIndex;
        selectedIndex = newIndex;
        draw();
        fireChanged();
    }

    private void handleMouseReleased(MouseEvent e) {
        dragIndex = -1;
    }

    private void openColorPicker(int index) {
        List<ColorStop> stops = palette.getStops();
        if (index < 0 || index >= stops.size()) return;

        ColorPicker picker = new ColorPicker(stops.get(index).color());

        Dialog<Color> dialog = new Dialog<>();
        dialog.setTitle("Stop Color");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(picker);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Live preview while picking
        picker.setOnAction(ev -> {
            palette.setStopColor(index, picker.getValue());
            draw();
            fireChanged();
        });

        dialog.setResultConverter(btn -> btn == ButtonType.OK ? picker.getValue() : null);
        dialog.showAndWait();
    }

    private void showStopContextMenu(int index, MouseEvent e) {
        ContextMenu menu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete Stop");
        deleteItem.setOnAction(ev -> {
            if (palette.removeStop(index)) {
                selectedIndex = -1;
                draw();
                fireChanged();
            }
        });
        MenuItem colorItem = new MenuItem("Change Color...");
        colorItem.setOnAction(ev -> openColorPicker(index));

        menu.getItems().addAll(colorItem, deleteItem);
        menu.show(canvas, e.getScreenX(), e.getScreenY());
    }

    private void fireChanged() {
        if (onGradientChanged != null) {
            onGradientChanged.accept(palette);
        }
    }
}
