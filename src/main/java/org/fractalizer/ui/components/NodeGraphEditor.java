package org.fractalizer.ui.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.fractals.*;
import org.fractalizer.graph.*;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.panels.RenderCallback;

import java.util.*;
import java.util.EnumMap;

/**
 * Visual editor for node-based fractal DE composition graphs.
 * Canvas-based node graph on the left, detail panel on the right.
 */
public class NodeGraphEditor extends VBox {

    private static final List<FractalType> EXCLUDED_TYPES = Arrays.asList(
        FractalType.NODE_GRAPH
    );

    // Node visual constants
    private static final double NODE_W = 130;
    private static final double NODE_H = 36;
    private static final double H_GAP = 20;
    private static final double V_GAP = 50;
    private static final double PADDING = 20;
    private static final double ARC = 10;

    private static final Color COLOR_FRACTAL = Color.web("#2196F3");
    private static final Color COLOR_CSG = Color.web("#FF9800");
    private static final Color COLOR_TRANSFORM = Color.web("#4CAF50");
    private static final Color COLOR_MIRROR = Color.web("#9C27B0");
    private static final Color COLOR_TWIST = Color.web("#E91E63");
    private static final Color COLOR_BEND = Color.web("#FF5722");
    private static final Color COLOR_TAPER = Color.web("#795548");
    private static final Color COLOR_REPETITION = Color.web("#00BCD4");
    private static final Color COLOR_REPETITION_1D = Color.web("#009688");
    private static final Color COLOR_EFFECT = Color.web("#F44336");
    private static final Color COLOR_SELECTED = Color.web("#00BCD4");
    private static final Color COLOR_CONNECTION = Color.web("#9E9E9E", 0.6);
    private static final Color COLOR_TEXT = Color.WHITE;
    private static final Color COLOR_CANVAS_BG = Color.web("#1E1E1E");

    // Zoom constants
    private static final double MIN_ZOOM = 0.3;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_FACTOR = 1.1;

    // Undo/redo constants
    private static final int MAX_GRAPH_HISTORY = 30;

    // Per-field slider configuration: min, max, optional tickUnit and precision
    private record SliderConfig(double min, double max, Double tickUnit, Integer precision) {
        SliderConfig(double min, double max) { this(min, max, null, null); }
        SliderConfig(double min, double max, double tickUnit) { this(min, max, tickUnit, null); }
    }

    // Default ranges by field name (fallback when no per-type override exists)
    private static final Map<String, SliderConfig> DEFAULT_SLIDER_CONFIG = new HashMap<>();
    static {
        DEFAULT_SLIDER_CONFIG.put("maxIterations", new SliderConfig(5, 30, 5));
        DEFAULT_SLIDER_CONFIG.put("power", new SliderConfig(2, 16, 2));
        DEFAULT_SLIDER_CONFIG.put("bailout", new SliderConfig(1, 16));
        DEFAULT_SLIDER_CONFIG.put("scale", new SliderConfig(1, 4));
        DEFAULT_SLIDER_CONFIG.put("minRadius", new SliderConfig(0.01, 1));
        DEFAULT_SLIDER_CONFIG.put("fixedRadius", new SliderConfig(0.5, 2));
        DEFAULT_SLIDER_CONFIG.put("foldingLimit", new SliderConfig(0.5, 2));
        DEFAULT_SLIDER_CONFIG.put("offsetX", new SliderConfig(0, 3));
        DEFAULT_SLIDER_CONFIG.put("offsetY", new SliderConfig(0, 3));
        DEFAULT_SLIDER_CONFIG.put("offsetZ", new SliderConfig(0, 3));
        DEFAULT_SLIDER_CONFIG.put("shiftX", new SliderConfig(-1, 1));
        DEFAULT_SLIDER_CONFIG.put("shiftY", new SliderConfig(-1, 1));
        DEFAULT_SLIDER_CONFIG.put("shiftZ", new SliderConfig(-1, 1));
        DEFAULT_SLIDER_CONFIG.put("foldAngleX", new SliderConfig(-30, 30, 15));
        DEFAULT_SLIDER_CONFIG.put("foldAngleY", new SliderConfig(-30, 30, 15));
        DEFAULT_SLIDER_CONFIG.put("ifsOffset", new SliderConfig(1, 5, 1));
        DEFAULT_SLIDER_CONFIG.put("rot1X", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rot1Y", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rot1Z", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rot2X", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rot2Y", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rot2Z", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("CSizeX", new SliderConfig(0.1, 2));
        DEFAULT_SLIDER_CONFIG.put("CSizeY", new SliderConfig(0.1, 2));
        DEFAULT_SLIDER_CONFIG.put("CSizeZ", new SliderConfig(0.1, 2));
        DEFAULT_SLIDER_CONFIG.put("Size", new SliderConfig(0.5, 2));
        DEFAULT_SLIDER_CONFIG.put("DEOffset", new SliderConfig(0, 0.5));
        DEFAULT_SLIDER_CONFIG.put("foldCx", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("foldCy", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("foldCz", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("foldRadius", new SliderConfig(0.5, 2));
        DEFAULT_SLIDER_CONFIG.put("juliaCx", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("juliaCy", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("juliaCz", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("juliaCw", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("sliceW", new SliderConfig(-2, 2));
        DEFAULT_SLIDER_CONFIG.put("rotAngle", new SliderConfig(-45, 45, 15));
        DEFAULT_SLIDER_CONFIG.put("rotXW", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rotYW", new SliderConfig(-180, 180, 45));
        DEFAULT_SLIDER_CONFIG.put("rotZW", new SliderConfig(-180, 180, 45));
    }

    // Per-fractal-type overrides (only fields that differ from defaults)
    private static final Map<FractalType, Map<String, SliderConfig>> FRACTAL_SLIDER_CONFIGS = new EnumMap<>(FractalType.class);
    static {
        // Mandelbulb
        Map<String, SliderConfig> mbConfigs = new HashMap<>();
        mbConfigs.put("maxIterations", new SliderConfig(5, 30, 5));
        mbConfigs.put("power", new SliderConfig(2, 16, 2));
        mbConfigs.put("bailout", new SliderConfig(1, 16));
        mbConfigs.put("radiolaria", new SliderConfig(0, 1));
        mbConfigs.put("radiolariaFactor", new SliderConfig(-2, 2));
        FRACTAL_SLIDER_CONFIGS.put(FractalType.MANDELBULB, mbConfigs);
        // Mandelbox
        FRACTAL_SLIDER_CONFIGS.put(FractalType.MANDELBOX, Map.of(
            "maxIterations", new SliderConfig(5, 30, 5),
            "scale", new SliderConfig(-3, 3, 1),
            "minRadius", new SliderConfig(0.01, 1),
            "fixedRadius", new SliderConfig(0.5, 2),
            "foldingLimit", new SliderConfig(0.5, 2)
        ));
        // Menger Sponge
        FRACTAL_SLIDER_CONFIGS.put(FractalType.MENGER_SPONGE, Map.of(
            "maxIterations", new SliderConfig(2, 10, 2),
            "scale", new SliderConfig(2, 4),
            "rotAngle", new SliderConfig(-45, 45, 15)
        ));
        // Kaleidoscopic IFS
        FRACTAL_SLIDER_CONFIGS.put(FractalType.KALEIDOSCOPIC_IFS, Map.of(
            "maxIterations", new SliderConfig(4, 25, 5),
            "scale", new SliderConfig(1.5, 3, 0.5),
            "ifsOffset", new SliderConfig(1, 5, 1),
            "foldAngleX", new SliderConfig(-30, 30, 15),
            "foldAngleY", new SliderConfig(-30, 30, 15)
        ));
        // Polyhedral IFS
        Map<String, SliderConfig> polyConfigs = new HashMap<>();
        polyConfigs.put("maxIterations", new SliderConfig(4, 25, 5));
        polyConfigs.put("scale", new SliderConfig(1.1, 3));
        polyConfigs.put("offsetX", new SliderConfig(0, 3));
        polyConfigs.put("offsetY", new SliderConfig(0, 3));
        polyConfigs.put("offsetZ", new SliderConfig(0, 3));
        polyConfigs.put("shiftX", new SliderConfig(-1, 1));
        polyConfigs.put("shiftY", new SliderConfig(-1, 1));
        polyConfigs.put("shiftZ", new SliderConfig(-1, 1));
        FRACTAL_SLIDER_CONFIGS.put(FractalType.POLYHEDRAL_IFS, polyConfigs);
        // Sierpinski
        FRACTAL_SLIDER_CONFIGS.put(FractalType.SIERPINSKI, Map.of(
            "maxIterations", new SliderConfig(5, 30, 5),
            "scale", new SliderConfig(1.5, 3)
        ));
        // Pseudo-Kleinian
        Map<String, SliderConfig> pkConfigs = new HashMap<>();
        pkConfigs.put("maxIterations", new SliderConfig(5, 30, 5));
        pkConfigs.put("CSizeX", new SliderConfig(0.1, 2));
        pkConfigs.put("CSizeY", new SliderConfig(0.1, 2));
        pkConfigs.put("CSizeZ", new SliderConfig(0.1, 2));
        pkConfigs.put("Size", new SliderConfig(0.5, 2));
        pkConfigs.put("DEOffset", new SliderConfig(0, 0.5));
        FRACTAL_SLIDER_CONFIGS.put(FractalType.PSEUDO_KLEINIAN, pkConfigs);
        // Apollonian
        FRACTAL_SLIDER_CONFIGS.put(FractalType.APOLLONIAN, Map.of(
            "maxIterations", new SliderConfig(5, 30, 5),
            "scale", new SliderConfig(1.5, 4),
            "foldRadius", new SliderConfig(0.5, 2)
        ));
        // Bristorbrot
        FRACTAL_SLIDER_CONFIGS.put(FractalType.BRISTORBROT, Map.of(
            "maxIterations", new SliderConfig(5, 30, 5),
            "bailout", new SliderConfig(1, 16)
        ));
        // Quaternion Julia 4D
        FRACTAL_SLIDER_CONFIGS.put(FractalType.QUATERNION_JULIA_4D, Map.of(
            "maxIterations", new SliderConfig(4, 20, 4),
            "bailout", new SliderConfig(1, 10)
        ));
        // Menger Advanced
        Map<String, SliderConfig> maConfigs = new HashMap<>();
        maConfigs.put("maxIterations", new SliderConfig(2, 25, 2));
        maConfigs.put("scale", new SliderConfig(2, 4));
        maConfigs.put("offset", new SliderConfig(0.5, 2));
        maConfigs.put("rotX", new SliderConfig(-3.14159, 3.14159));
        maConfigs.put("rotZ", new SliderConfig(-3.14159, 3.14159));
        maConfigs.put("innerFold", new SliderConfig(0, 1));
        maConfigs.put("zScale", new SliderConfig(0.5, 3));
        FRACTAL_SLIDER_CONFIGS.put(FractalType.MENGER_ADVANCED, maConfigs);
        // Menger Sponge Test
        Map<String, SliderConfig> mstConfigs = new HashMap<>();
        mstConfigs.put("maxIterations", new SliderConfig(2, 25, 2));
        mstConfigs.put("scale", new SliderConfig(2, 4));
        mstConfigs.put("offset", new SliderConfig(0.5, 2));
        mstConfigs.put("rotX", new SliderConfig(-3.14159, 3.14159));
        mstConfigs.put("rotZ", new SliderConfig(-3.14159, 3.14159));
        mstConfigs.put("zShift", new SliderConfig(-2, 2));
        mstConfigs.put("centerZ", new SliderConfig(0, 2));
        FRACTAL_SLIDER_CONFIGS.put(FractalType.MENGER_SPONGE_TEST, mstConfigs);
    }

    private static SliderConfig resolveSliderConfig(FractalType type, String fieldName, boolean isInt) {
        // 1. Per-type override
        if (type != null) {
            Map<String, SliderConfig> typeMap = FRACTAL_SLIDER_CONFIGS.get(type);
            if (typeMap != null && typeMap.containsKey(fieldName)) return typeMap.get(fieldName);
        }
        // 2. Default by field name
        if (DEFAULT_SLIDER_CONFIG.containsKey(fieldName)) return DEFAULT_SLIDER_CONFIG.get(fieldName);
        // 3. Hardcoded fallback
        return isInt ? new SliderConfig(1, 50) : new SliderConfig(0, 10);
    }

    private static double computeNiceTickUnit(double min, double max, boolean isInt) {
        double range = max - min;
        if (range <= 0) return 1;
        int targetTicks = 5;
        double rawUnit = range / targetTicks;
        if (isInt) {
            // Snap to 1, 2, 5, 10, 20, 50, ...
            double[] niceInts = {1, 2, 5, 10, 20, 50, 100};
            for (double n : niceInts) {
                if (n >= rawUnit) return n;
            }
            return Math.ceil(rawUnit);
        } else {
            // Snap to nice decimals: 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, ...
            double[] niceFloats = {0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 25, 50};
            for (double n : niceFloats) {
                if (n >= rawUnit) return n;
            }
            return rawUnit;
        }
    }

    private final RenderController controller;
    private final RenderCallback renderCallback;

    private final Canvas canvas;
    private final VBox detailPanel;
    private final Label statusLabel;
    private final ComboBox<String> presetCombo;
    private ScrollPane canvasScroll;
    private Label zoomLabel;

    // Zoom
    private double zoomLevel = 1.0;

    // Undo/redo
    private final List<Map<String, Object>> graphHistory = new ArrayList<>();
    private int graphHistoryIndex = -1;
    private Button undoBtn, redoBtn;


    // Context menu
    private ContextMenu contextMenu;

    private NodeGraphParams currentParams;
    private GraphNode selectedNode;
    private final List<NodeLayout> nodeLayouts = new ArrayList<>();

    private Runnable onGraphStructureChanged;

    public NodeGraphEditor(RenderController controller, RenderCallback renderCallback) {
        super(6);
        this.controller = controller;
        this.renderCallback = renderCallback;

        // Toolbar
        Button addFractalBtn = new Button("+ Fractal");
        addFractalBtn.setTooltip(new Tooltip("Add a fractal leaf node"));
        addFractalBtn.setOnAction(e -> addFractalNode());

        Button wrapCSGBtn = new Button("Wrap CSG");
        wrapCSGBtn.setTooltip(new Tooltip("Wrap selected node in a CSG operation"));
        wrapCSGBtn.setOnAction(e -> wrapInCSG());

        Button wrapTransformBtn = new Button("Wrap Transform");
        wrapTransformBtn.setTooltip(new Tooltip("Wrap selected node in a transform (translate/rotate/scale)"));
        wrapTransformBtn.setOnAction(e -> wrapInTransform(TransformNode.Mode.STANDARD));

        MenuButton moreTransforms = new MenuButton("+");
        moreTransforms.setTooltip(new Tooltip("More transform types"));
        for (TransformNode.Mode mode : TransformNode.Mode.values()) {
            if (mode == TransformNode.Mode.STANDARD) continue;
            MenuItem mi = new MenuItem(mode.getDisplayName());
            mi.setOnAction(e -> wrapInTransform(mode));
            moreTransforms.getItems().add(mi);
        }

        MenuButton wrapEffectBtn = new MenuButton("Wrap Effect");
        wrapEffectBtn.setTooltip(new Tooltip("Wrap selected node in a surface effect"));
        for (EffectNode.EffectType etype : EffectNode.EffectType.values()) {
            MenuItem mi = new MenuItem(etype.getDisplayName());
            mi.setOnAction(e -> wrapInEffect(etype));
            wrapEffectBtn.getItems().add(mi);
        }

        Button deleteBtn = new Button("Delete");
        deleteBtn.setTooltip(new Tooltip("Remove selected node"));
        deleteBtn.setOnAction(e -> deleteSelected());

        Separator tbSep1 = new Separator();
        tbSep1.setOrientation(javafx.geometry.Orientation.VERTICAL);

        undoBtn = new Button("\u21B6");
        undoBtn.setTooltip(new Tooltip("Undo (Ctrl+Z)"));
        undoBtn.setDisable(true);
        undoBtn.setOnAction(e -> undo());

        redoBtn = new Button("\u21B7");
        redoBtn.setTooltip(new Tooltip("Redo (Ctrl+Y)"));
        redoBtn.setDisable(true);
        redoBtn.setOnAction(e -> redo());

        Separator tbSep2 = new Separator();
        tbSep2.setOrientation(javafx.geometry.Orientation.VERTICAL);

        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("Single Fractal", "Union", "Subtract + Transform", "Mirror Symmetry",
            "Eroded Mandelbulb", "Crystal Menger", "Mossy Rocks", "Stacked Effects");
        presetCombo.setPromptText("Presets...");
        presetCombo.setOnAction(e -> applyPreset(presetCombo.getValue()));

        HBox toolbar = new HBox(4, addFractalBtn, wrapCSGBtn, wrapTransformBtn, moreTransforms, wrapEffectBtn, deleteBtn,
            tbSep1, undoBtn, redoBtn, tbSep2, presetCombo);
        toolbar.setPadding(new Insets(2));

        // Canvas for visual node graph
        canvas = new Canvas(400, 250);
        canvas.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (e.getClickCount() == 2) {
                    // Double-click on empty space -> fit to view
                    double mx = e.getX() / zoomLevel;
                    double my = e.getY() / zoomLevel;
                    boolean hitNode = false;
                    for (NodeLayout nl : nodeLayouts) {
                        if (nl.contains(mx, my)) { hitNode = true; break; }
                    }
                    if (!hitNode) fitToView();
                } else {
                    handleCanvasClick(e.getX() / zoomLevel, e.getY() / zoomLevel);
                }
            }
        });

        // Context menu
        contextMenu = new ContextMenu();
        canvas.setOnContextMenuRequested(this::showContextMenu);

        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setMinSize(0, 0);
        canvasPane.getStyleClass().add("node-graph-canvas");

        // Zoom label overlay
        zoomLabel = new Label("100%");
        zoomLabel.setStyle("-fx-background-color: rgba(0,0,0,0.5); -fx-text-fill: #aaa; -fx-padding: 1 4; -fx-font-size: 10;");
        zoomLabel.setMouseTransparent(true);
        StackPane.setAlignment(zoomLabel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(zoomLabel, new Insets(0, 4, 4, 0));
        canvasPane.getChildren().add(zoomLabel);

        canvasScroll = new ScrollPane(canvasPane);
        canvasScroll.setPannable(true);
        canvasScroll.setFitToWidth(false);
        canvasScroll.addEventFilter(ScrollEvent.SCROLL, this::handleZoom);

        // Detail panel (right side, shows controls for selected node)
        detailPanel = new VBox(6);
        detailPanel.setPadding(new Insets(4));
        detailPanel.setMinHeight(80);
        detailPanel.setMinWidth(180);

        ScrollPane detailScroll = new ScrollPane(detailPanel);
        detailScroll.setFitToWidth(true);

        // Split: canvas left, detail right
        SplitPane splitPane = new SplitPane(canvasScroll, detailScroll);
        splitPane.setDividerPositions(0.55);
        splitPane.setMinHeight(150);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // Status
        statusLabel = new Label("No graph loaded");
        statusLabel.getStyleClass().add("compile-status");

        getChildren().addAll(toolbar, splitPane, statusLabel);

        // Keyboard shortcuts for undo/redo
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.Z) {
                undo();
                e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.Y) {
                redo();
                e.consume();
            }
        });
    }

    /**
     * Set callback for graph structural changes (node add/remove/rename).
     * Used by AnimationManager to refresh tracks.
     */
    public void setOnGraphStructureChanged(Runnable r) {
        this.onGraphStructureChanged = r;
    }

    /**
     * Load params and rebuild canvas from the graph root.
     */
    public void loadParams(NodeGraphParams params) {
        this.currentParams = params;
        selectedNode = (params != null) ? params.getGraphRoot() : null;

        // Reset undo history
        graphHistory.clear();
        graphHistoryIndex = -1;
        if (params != null && params.getGraphRoot() != null) {
            graphHistory.add(FractalConfig.serializeGraphNode(params.getGraphRoot()));
            graphHistoryIndex = 0;
        }
        updateUndoRedoButtons();

        redrawCanvas();
        refreshDetailPanel();
        autoCompile();
    }

    /**
     * Whether loadParams() has been called at least once with valid params.
     */
    public boolean isLoaded() {
        return currentParams != null && currentParams.getGraphRoot() != null;
    }

    public NodeGraphParams getCurrentParams() {
        return currentParams;
    }

    /**
     * Lightweight refresh: only update slider values in the detail panel.
     * No canvas redraw, no recompile. Used during animation scrub/playback.
     */
    public void refreshSliders() {
        refreshDetailPanel();
    }

    // ========================================================================
    // Zoom / Pan
    // ========================================================================

    private void handleZoom(ScrollEvent e) {
        e.consume();
        if (e.getDeltaY() == 0) return;

        double oldZoom = zoomLevel;
        if (e.getDeltaY() > 0) {
            zoomLevel = Math.min(MAX_ZOOM, zoomLevel * ZOOM_FACTOR);
        } else {
            zoomLevel = Math.max(MIN_ZOOM, zoomLevel / ZOOM_FACTOR);
        }
        if (zoomLevel == oldZoom) return;

        // Use screenToLocal for robust coordinates regardless of ScrollPane layout
        var canvasPt = canvas.screenToLocal(e.getScreenX(), e.getScreenY());
        var vpPt = canvasScroll.screenToLocal(e.getScreenX(), e.getScreenY());

        // Logical (unzoomed) point under cursor
        double logX = canvasPt != null ? canvasPt.getX() / oldZoom : 0;
        double logY = canvasPt != null ? canvasPt.getY() / oldZoom : 0;
        // Mouse position relative to viewport top-left
        double vpMouseX = vpPt != null ? vpPt.getX() : 0;
        double vpMouseY = vpPt != null ? vpPt.getY() : 0;

        redrawCanvas();
        zoomLabel.setText(String.format("%.0f%%", zoomLevel * 100));

        // Defer scroll to after layout pass — ScrollPane needs to recalculate range first
        Platform.runLater(() -> {
            double vpW = canvasScroll.getViewportBounds().getWidth();
            double vpH = canvasScroll.getViewportBounds().getHeight();
            double cw = canvas.getWidth();
            double ch = canvas.getHeight();

            double hRange = cw - vpW;
            double vRange = ch - vpH;
            if (hRange > 0)
                canvasScroll.setHvalue(Math.max(0, Math.min(1, (logX * zoomLevel - vpMouseX) / hRange)));
            if (vRange > 0)
                canvasScroll.setVvalue(Math.max(0, Math.min(1, (logY * zoomLevel - vpMouseY) / vRange)));
        });
    }

    private void fitToView() {
        if (currentParams == null || currentParams.getGraphRoot() == null) return;

        GraphNode root = currentParams.getGraphRoot();
        double contentW = computeSubtreeWidth(root) + PADDING * 2;
        double contentH = computeTreeHeight(root) + PADDING * 2;

        double viewW = canvasScroll.getViewportBounds().getWidth();
        double viewH = canvasScroll.getViewportBounds().getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        zoomLevel = Math.min(viewW / contentW, viewH / contentH);
        zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomLevel));

        zoomLabel.setText(String.format("%.0f%%", zoomLevel * 100));
        redrawCanvas();
        canvasScroll.setHvalue(0);
        canvasScroll.setVvalue(0);
    }

    // ========================================================================
    // Context Menu
    // ========================================================================

    private void showContextMenu(ContextMenuEvent e) {
        contextMenu.hide();
        contextMenu = new ContextMenu();
        double mx = e.getX() / zoomLevel;
        double my = e.getY() / zoomLevel;

        GraphNode hitNode = null;
        for (NodeLayout nl : nodeLayouts) {
            if (nl.contains(mx, my)) {
                hitNode = nl.node;
                break;
            }
        }

        if (hitNode != null) {
            buildNodeContextMenu(hitNode);
        } else {
            buildEmptyContextMenu();
        }

        contextMenu.show(canvas, e.getScreenX(), e.getScreenY());
    }

    private void buildNodeContextMenu(GraphNode node) {
        // Select the node under cursor
        selectedNode = node;
        redrawCanvas();
        refreshDetailPanel();

        MenuItem renameItem = new MenuItem("Rename");
        renameItem.setOnAction(e -> {
            // Focus the name TextField in the detail panel (first TextField found)
            for (var child : detailPanel.getChildren()) {
                if (child instanceof TextField tf) {
                    tf.requestFocus();
                    tf.selectAll();
                    break;
                }
            }
        });
        contextMenu.getItems().add(renameItem);

        contextMenu.getItems().add(new SeparatorMenuItem());

        MenuItem wrapCSGItem = new MenuItem("Wrap in CSG (Union)");
        wrapCSGItem.setOnAction(e -> wrapInCSG());
        contextMenu.getItems().add(wrapCSGItem);

        Menu wrapTransformMenu = new Menu("Wrap in Transform");
        for (TransformNode.Mode mode : TransformNode.Mode.values()) {
            MenuItem mi = new MenuItem(mode.getDisplayName());
            mi.setOnAction(e -> wrapInTransform(mode));
            wrapTransformMenu.getItems().add(mi);
        }
        contextMenu.getItems().add(wrapTransformMenu);

        Menu wrapEffectMenu = new Menu("Wrap in Effect");
        for (EffectNode.EffectType etype : EffectNode.EffectType.values()) {
            MenuItem mi = new MenuItem(etype.getDisplayName());
            mi.setOnAction(e -> wrapInEffect(etype));
            wrapEffectMenu.getItems().add(mi);
        }
        contextMenu.getItems().add(wrapEffectMenu);

        MenuItem duplicateItem = new MenuItem("Duplicate Subtree");
        duplicateItem.setOnAction(e -> duplicateSubtree(node));
        contextMenu.getItems().add(duplicateItem);

        contextMenu.getItems().add(new SeparatorMenuItem());

        // CSG-specific: swap children
        if (node instanceof CSGNode) {
            MenuItem swapItem = new MenuItem("Swap Children");
            swapItem.setOnAction(e -> {
                ((CSGNode) node).swapChildren();
                pushUndoSnapshot();
                onStructuralChange();
            });
            contextMenu.getItems().add(swapItem);
        }

        // Fractal-specific: change type submenu
        if (node instanceof FractalNode fn) {
            Menu changeTypeMenu = new Menu("Change Type");
            for (FractalType ft : FractalType.values()) {
                if (EXCLUDED_TYPES.contains(ft)) continue;
                MenuItem mi = new MenuItem(ft.getDisplayName());
                if (ft == fn.getFractalType()) mi.setDisable(true);
                mi.setOnAction(e -> {
                    pushUndoSnapshot();
                    fn.setFractalType(ft);
                    onStructuralChange();
                });
                changeTypeMenu.getItems().add(mi);
            }
            contextMenu.getItems().add(changeTypeMenu);
        }

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteSelected());
        contextMenu.getItems().add(deleteItem);
    }

    private void buildEmptyContextMenu() {
        MenuItem addFractalItem = new MenuItem("Add Fractal");
        addFractalItem.setOnAction(e -> addFractalNode());
        contextMenu.getItems().add(addFractalItem);

        contextMenu.getItems().add(new SeparatorMenuItem());

        MenuItem fitItem = new MenuItem("Fit to View");
        fitItem.setOnAction(e -> fitToView());
        contextMenu.getItems().add(fitItem);

        MenuItem resetZoomItem = new MenuItem("Reset Zoom (100%)");
        resetZoomItem.setOnAction(e -> {
            zoomLevel = 1.0;
            zoomLabel.setText("100%");
            redrawCanvas();
        });
        contextMenu.getItems().add(resetZoomItem);
    }

    // ========================================================================
    // Undo / Redo
    // ========================================================================

    private void pushUndoSnapshot() {
        if (currentParams == null || currentParams.getGraphRoot() == null) return;

        Map<String, Object> snapshot = FractalConfig.serializeGraphNode(currentParams.getGraphRoot());

        // Trim forward history
        while (graphHistory.size() > graphHistoryIndex + 1) {
            graphHistory.remove(graphHistory.size() - 1);
        }

        graphHistory.add(snapshot);

        // Limit history size
        if (graphHistory.size() > MAX_GRAPH_HISTORY) {
            graphHistory.remove(0);
        }

        graphHistoryIndex = graphHistory.size() - 1;
        updateUndoRedoButtons();
    }

    private void undo() {
        if (graphHistoryIndex <= 0) return;
        graphHistoryIndex--;
        restoreGraphSnapshot(graphHistory.get(graphHistoryIndex));
    }

    private void redo() {
        if (graphHistoryIndex >= graphHistory.size() - 1) return;
        graphHistoryIndex++;
        restoreGraphSnapshot(graphHistory.get(graphHistoryIndex));
    }

    private void restoreGraphSnapshot(Map<String, Object> snapshot) {
        GraphNode restored = FractalConfig.deserializeGraphNode(snapshot);
        if (restored == null) return;

        currentParams.setGraphRoot(restored);
        GraphNodeNamer.ensureAllNamed(restored);
        selectedNode = restored;
        currentParams.markDirty();
        redrawCanvas();
        refreshDetailPanel();
        updateUndoRedoButtons();

        if (onGraphStructureChanged != null) {
            onGraphStructureChanged.run();
        }
        // Defer GL compilation to next FX pulse so UI updates are visible immediately
        statusLabel.setText("Compiling...");
        statusLabel.getStyleClass().removeAll("success", "error");
        Platform.runLater(this::autoCompile);
    }

    private void updateUndoRedoButtons() {
        undoBtn.setDisable(graphHistoryIndex <= 0);
        redoBtn.setDisable(graphHistoryIndex >= graphHistory.size() - 1);
    }

    // ========================================================================
    // Canvas layout & rendering
    // ========================================================================

    private record NodeLayout(GraphNode node, double x, double y, double w, double h) {
        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
        double centerX() { return x + w / 2; }
        double bottomCenterY() { return y + h; }
        double topCenterY() { return y; }
    }

    private double computeSubtreeWidth(GraphNode node) {
        List<GraphNode> children = node.getChildren();
        if (children.isEmpty()) return NODE_W;
        double total = 0;
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) total += H_GAP;
            total += computeSubtreeWidth(children.get(i));
        }
        return Math.max(NODE_W, total);
    }

    private void positionNodes(GraphNode node, double x, double y) {
        double subtreeW = computeSubtreeWidth(node);
        double nodeX = x + (subtreeW - NODE_W) / 2;
        nodeLayouts.add(new NodeLayout(node, nodeX, y, NODE_W, NODE_H));

        List<GraphNode> children = node.getChildren();
        double childY = y + NODE_H + V_GAP;
        double childX = x;
        for (GraphNode child : children) {
            double childW = computeSubtreeWidth(child);
            positionNodes(child, childX, childY);
            childX += childW + H_GAP;
        }
    }

    private void redrawCanvas() {
        nodeLayouts.clear();
        if (currentParams == null || currentParams.getGraphRoot() == null) {
            canvas.setWidth(400);
            canvas.setHeight(250);
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFill(COLOR_CANVAS_BG);
            gc.fillRect(0, 0, 400, 250);
            gc.setFill(Color.GRAY);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No graph", 200, 125);
            return;
        }

        GraphNode root = currentParams.getGraphRoot();
        double totalW = computeSubtreeWidth(root) + PADDING * 2;
        double totalH = computeTreeHeight(root) + PADDING * 2;

        canvas.setWidth(Math.max(400, totalW) * zoomLevel);
        canvas.setHeight(Math.max(250, totalH) * zoomLevel);

        positionNodes(root, PADDING, PADDING);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(COLOR_CANVAS_BG);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.save();
        gc.scale(zoomLevel, zoomLevel);

        // Draw connections first (behind nodes)
        gc.setStroke(COLOR_CONNECTION);
        gc.setLineWidth(2);
        for (NodeLayout nl : nodeLayouts) {
            for (GraphNode child : nl.node.getChildren()) {
                NodeLayout childLayout = findLayout(child);
                if (childLayout != null) drawConnection(gc, nl, childLayout);
            }
        }

        // Draw nodes
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.CENTER);

        for (NodeLayout nl : nodeLayouts) {
            Color fill = getNodeColor(nl.node);
            boolean isSelected = (nl.node == selectedNode);

            gc.setFill(fill);
            gc.fillRoundRect(nl.x, nl.y, nl.w, nl.h, ARC, ARC);

            if (isSelected) {
                gc.setStroke(COLOR_SELECTED);
                gc.setLineWidth(2.5);
                gc.strokeRoundRect(nl.x - 1, nl.y - 1, nl.w + 2, nl.h + 2, ARC, ARC);
            }

            gc.setFill(COLOR_TEXT);
            gc.fillText(getNodeLabel(nl.node), nl.centerX(), nl.y + nl.h / 2 + 4, nl.w - 8);
        }

        gc.restore();
    }

    private double computeTreeHeight(GraphNode node) {
        List<GraphNode> children = node.getChildren();
        if (children.isEmpty()) return NODE_H;
        double maxChildH = 0;
        for (GraphNode child : children) {
            maxChildH = Math.max(maxChildH, computeTreeHeight(child));
        }
        return NODE_H + V_GAP + maxChildH;
    }

    private void drawConnection(GraphicsContext gc, NodeLayout parent, NodeLayout child) {
        double x1 = parent.centerX(), y1 = parent.bottomCenterY();
        double x2 = child.centerX(), y2 = child.topCenterY();
        double cy = (y1 + y2) / 2;
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.bezierCurveTo(x1, cy, x2, cy, x2, y2);
        gc.stroke();
    }

    private NodeLayout findLayout(GraphNode node) {
        for (NodeLayout nl : nodeLayouts) {
            if (nl.node == node) return nl;
        }
        return null;
    }

    private Color getNodeColor(GraphNode node) {
        if (node instanceof FractalNode) return COLOR_FRACTAL;
        if (node instanceof EffectNode) return COLOR_EFFECT;
        if (node instanceof CSGNode) return COLOR_CSG;
        if (node instanceof TransformNode tn) {
            return switch (tn.getMode()) {
                case MIRROR -> COLOR_MIRROR;
                case TWIST -> COLOR_TWIST;
                case BEND -> COLOR_BEND;
                case TAPER -> COLOR_TAPER;
                case REPETITION -> COLOR_REPETITION;
                case REPETITION_1D -> COLOR_REPETITION_1D;
                default -> COLOR_TRANSFORM;
            };
        }
        return Color.GRAY;
    }

    private String getNodeLabel(GraphNode node) {
        if (node.getName() != null) return node.getName();
        if (node instanceof FractalNode fn) return fn.getFractalType().getDisplayName();
        if (node instanceof EffectNode en) return en.getEffectType().getDisplayName();
        if (node instanceof CSGNode csn) return csn.getOp().name();
        if (node instanceof TransformNode tn) return tn.getMode().getDisplayName();
        return "?";
    }

    private void handleCanvasClick(double mx, double my) {
        for (NodeLayout nl : nodeLayouts) {
            if (nl.contains(mx, my)) {
                selectedNode = nl.node;
                redrawCanvas();
                refreshDetailPanel();
                return;
            }
        }
    }

    // ========================================================================
    // Parent tracking
    // ========================================================================

    private ParentRef findParent(GraphNode target) {
        if (currentParams == null) return null;
        return findParentRecursive(currentParams.getGraphRoot(), target);
    }

    private ParentRef findParentRecursive(GraphNode current, GraphNode target) {
        if (current instanceof CSGNode csn) {
            if (csn.getLeft() == target) return new ParentRef(csn, 0);
            if (csn.getRight() == target) return new ParentRef(csn, 1);
            ParentRef r = findParentRecursive(csn.getLeft(), target);
            if (r != null) return r;
            return findParentRecursive(csn.getRight(), target);
        } else if (current instanceof EffectNode en) {
            if (en.getChild() == target) return new ParentRef(en, 0);
            return findParentRecursive(en.getChild(), target);
        } else if (current instanceof TransformNode tn) {
            if (tn.getChild() == target) return new ParentRef(tn, 0);
            return findParentRecursive(tn.getChild(), target);
        }
        return null;
    }

    private record ParentRef(GraphNode parent, int childIndex) {
        void setChild(GraphNode newChild) {
            if (parent instanceof CSGNode csn) {
                if (childIndex == 0) csn.setLeft(newChild);
                else csn.setRight(newChild);
            } else if (parent instanceof EffectNode en) {
                en.setChild(newChild);
            } else if (parent instanceof TransformNode tn) {
                tn.setChild(newChild);
            }
        }
    }

    // ========================================================================
    // Detail panel
    // ========================================================================

    private void refreshDetailPanel() {
        detailPanel.getChildren().clear();
        if (selectedNode == null) return;

        if (selectedNode instanceof FractalNode fn) {
            buildFractalDetail(fn);
        } else if (selectedNode instanceof EffectNode en) {
            buildEffectDetail(en);
        } else if (selectedNode instanceof CSGNode csn) {
            buildCSGDetail(csn);
        } else if (selectedNode instanceof TransformNode tn) {
            buildTransformDetail(tn);
        }
    }

    private void buildNameField(GraphNode node) {
        TextField nameField = new TextField(node.getName() != null ? node.getName() : "");
        nameField.setPromptText("Node name");
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setOnAction(e -> commitNameChange(node, nameField));
        nameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commitNameChange(node, nameField);
        });
        detailPanel.getChildren().addAll(new Label("Name:"), nameField);
    }

    private void commitNameChange(GraphNode node, TextField nameField) {
        String newName = nameField.getText().trim();
        if (newName.isEmpty() || newName.equals(node.getName())) return;

        String oldName = node.getName();
        if (currentParams != null && GraphNodeNamer.renameNode(currentParams.getGraphRoot(), node, newName)) {
            redrawCanvas();
            if (onGraphStructureChanged != null) {
                onGraphStructureChanged.run();
            }
        } else {
            // Revert to current name on failure
            nameField.setText(node.getName() != null ? node.getName() : "");
        }
    }

    private void buildFractalDetail(FractalNode fn) {
        Label title = new Label("Fractal Node");
        title.getStyleClass().add("bold-label");
        detailPanel.getChildren().add(title);
        buildNameField(fn);

        ComboBox<FractalType> typeCombo = new ComboBox<>();
        for (FractalType ft : FractalType.values()) {
            if (!EXCLUDED_TYPES.contains(ft)) typeCombo.getItems().add(ft);
        }
        typeCombo.setValue(fn.getFractalType());
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setOnAction(e -> {
            pushUndoSnapshot();
            fn.setFractalType(typeCombo.getValue());
            onStructuralChange();
        });

        detailPanel.getChildren().addAll(new Label("Type:"), typeCombo);

        AbstractFractalParams params = fn.getFractalParams();
        if (params == null) return;

        // Custom Shader: show inline editor instead of sliders
        if (params instanceof CustomShaderParams csp) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(4, 0, 4, 0));
            detailPanel.getChildren().add(sep);

            CustomShaderEditor csEditor = new CustomShaderEditor(controller, renderCallback);
            csEditor.setOnCompileSuccess(() -> {
                // Re-trigger graph compilation when custom shader source changes
                currentParams.markDirty();
                onStructuralChange();
            });
            csEditor.loadParams(csp);
            detailPanel.getChildren().add(csEditor);
            return;
        }

        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));
        detailPanel.getChildren().add(sep);

        // Auto-discover @Animatable parameters and create sliders
        List<AnimatableParameter> animParams = params.getAnimatableParameters();
        FractalType fractalType = fn.getFractalType();
        String lastGroup = null;
        for (AnimatableParameter ap : animParams) {
            if (ap.valueType() != Float.class && ap.valueType() != Integer.class) continue;

            // Visual grouping by prefix with header labels
            String group = getParamGroup(ap.name());
            if (group != null && !group.equals(lastGroup)) {
                Separator groupSep = new Separator();
                groupSep.setPadding(new Insets(2, 0, 0, 0));
                Label groupLabel = new Label(getGroupDisplayName(group));
                groupLabel.getStyleClass().add("small-label");
                groupLabel.setStyle("-fx-text-fill: #00ccff; -fx-font-size: 10;");
                detailPanel.getChildren().addAll(groupSep, groupLabel);
                lastGroup = group;
            }

            boolean isInt = (ap.valueType() == Integer.class);
            SliderConfig cfg = resolveSliderConfig(fractalType, ap.name(), isInt);

            Number currentVal = (Number) ap.getter().get();
            EnhancedSlider slider = new EnhancedSlider(ap.displayName(), cfg.min(), cfg.max(),
                currentVal.doubleValue(), isInt);
            if (cfg.precision() != null) slider.setPrecision(cfg.precision());
            slider.showTickMarks(true);
            double tickUnit = cfg.tickUnit() != null ? cfg.tickUnit() : computeNiceTickUnit(cfg.min(), cfg.max(), isInt);
            slider.setMajorTickUnit(tickUnit);
            slider.getSlider().setMinorTickCount(4);
            slider.setOnAction(v -> {
                ap.setter().accept(isInt ? v.intValue() : v.floatValue());
                onParameterChange();
            });
            detailPanel.getChildren().add(slider);
        }

        // Type-specific extras: enums, presets, hints
        buildFractalExtras(fn);
    }

    private String getParamGroup(String name) {
        // Group related params by prefix for visual separators
        if (name.startsWith("offset")) return "offset";
        if (name.startsWith("shift")) return "shift";
        if (name.startsWith("rot1")) return "rot1";
        if (name.startsWith("rot2")) return "rot2";
        if (name.startsWith("julia")) return "julia";
        if (name.startsWith("rot") && !name.startsWith("rot1") && !name.startsWith("rot2")) return "rot4D";
        if (name.startsWith("foldC")) return "foldC";
        if (name.startsWith("CSize")) return "CSize";
        if (name.startsWith("fold")) return "fold";
        if (name.startsWith("radiolaria")) return "radiolaria";
        return null;
    }

    private String getGroupDisplayName(String group) {
        return switch (group) {
            case "offset" -> "Offset";
            case "shift" -> "Shift";
            case "rot1" -> "Rotation 1";
            case "rot2" -> "Rotation 2";
            case "julia" -> "Julia C";
            case "rot4D" -> "4D Rotations";
            case "foldC" -> "Fold C";
            case "CSize" -> "C Size";
            case "fold" -> "Fold Angles";
            case "radiolaria" -> "Radiolaria";
            default -> group;
        };
    }

    private void buildFractalExtras(FractalNode fn) {
        AbstractFractalParams params = fn.getFractalParams();

        // --- Polyhedral IFS: PolyType enum ComboBox + 8 presets ---
        if (params instanceof PolyhedralIFSParams poly) {
            detailPanel.getChildren().add(new Separator());

            ComboBox<PolyhedralIFSParams.PolyType> polyCombo = new ComboBox<>();
            polyCombo.getItems().addAll(PolyhedralIFSParams.PolyType.values());
            polyCombo.setValue(poly.getPolyType());
            polyCombo.setMaxWidth(Double.MAX_VALUE);
            polyCombo.setOnAction(e -> {
                poly.setPolyType(polyCombo.getValue());
                onParameterChange();
            });
            detailPanel.getChildren().addAll(new Label("Symmetry:"), polyCombo);

            Label presetLabel = new Label("Presets:");
            presetLabel.getStyleClass().add("bold-label");
            detailPanel.getChildren().add(presetLabel);

            addPolyPresetButton(fn, "Octa Classic", PolyhedralIFSParams.PolyType.OCTAHEDRAL, 15, 2.0, 1,1,1, 0,0,0, 0,0,0, 0,0,0);
            addPolyPresetButton(fn, "Twisted Octa", PolyhedralIFSParams.PolyType.OCTAHEDRAL, 15, 2.0, 1,1,1, 0,0,0, 15,10,0, 0,0,0);
            addPolyPresetButton(fn, "Sierpinski Tetra", PolyhedralIFSParams.PolyType.TETRAHEDRON, 15, 2.0, 1,1,1, 0,0,0, 0,0,0, 0,0,0);
            addPolyPresetButton(fn, "Icosa Crystal", PolyhedralIFSParams.PolyType.ICOSAHEDRON, 15, 2.0, 1,1,1, 0,0,0, 5,5,0, 0,0,0);
            addPolyPresetButton(fn, "Dodeca Flower", PolyhedralIFSParams.PolyType.DODECAHEDRON, 12, 2.0, 1,1,1, 0,0,0, 6,0,4, 0,3,0);
            addPolyPresetButton(fn, "Alien Artifact", PolyhedralIFSParams.PolyType.OCTAHEDRAL, 18, 2.3, 1.2,0.8,1.0, 0.1,-0.05,0.1, 12,8,5, -5,3,0);
            addPolyPresetButton(fn, "Deep Coral", PolyhedralIFSParams.PolyType.ICOSAHEDRON, 16, 1.9, 1.0,1.0,1.0, 0,0,0, 8,12,0, 3,-3,0);
            addPolyPresetButton(fn, "Cathedral", PolyhedralIFSParams.PolyType.DODECAHEDRON, 15, 2.0, 1,1.15,1, 0,0,0, 0,5,0, 3,0,0);
        }

        // --- Quaternion Julia 4D: 5 presets ---
        if (params instanceof QuaternionJulia4DParams) {
            detailPanel.getChildren().add(new Separator());
            Label presetLabel = new Label("Presets:");
            presetLabel.getStyleClass().add("bold-label");
            detailPanel.getChildren().add(presetLabel);

            addQJ4DPresetButton(fn, "Classic", QuaternionJulia4DParams.classicPreset());
            addQJ4DPresetButton(fn, "4D Flower", QuaternionJulia4DParams.flowerPreset());
            addQJ4DPresetButton(fn, "Wormhole", QuaternionJulia4DParams.wormholePreset());
            addQJ4DPresetButton(fn, "Crystal", QuaternionJulia4DParams.crystalPreset());
            addQJ4DPresetButton(fn, "Hypersphere", QuaternionJulia4DParams.hyperspherePreset());
        }

        // --- Kaleidoscopic IFS: hint ---
        if (params instanceof KaleidoscopicIFSParams) {
            Label hint = new Label("Classic Sierpinski: Scale=2, Offset=3");
            hint.getStyleClass().add("hint-label");
            hint.setWrapText(true);
            detailPanel.getChildren().addAll(new Separator(), hint);
        }
    }

    private void addPolyPresetButton(FractalNode fn, String name,
                                      PolyhedralIFSParams.PolyType type, int iter, double scale,
                                      double ox, double oy, double oz,
                                      double sx, double sy, double sz,
                                      double r1x, double r1y, double r1z,
                                      double r2x, double r2y, double r2z) {
        Button btn = new Button(name);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> {
            if (fn.getFractalParams() instanceof PolyhedralIFSParams p) {
                p.setPolyType(type);
                p.setMaxIterations(iter);
                p.setScale((float) scale);
                p.setOffsetX((float) ox); p.setOffsetY((float) oy); p.setOffsetZ((float) oz);
                p.setShiftX((float) sx); p.setShiftY((float) sy); p.setShiftZ((float) sz);
                p.setRot1X((float) r1x); p.setRot1Y((float) r1y); p.setRot1Z((float) r1z);
                p.setRot2X((float) r2x); p.setRot2Y((float) r2y); p.setRot2Z((float) r2z);
                refreshDetailPanel();
                onParameterChange();
            }
        });
        detailPanel.getChildren().add(btn);
    }

    private void addQJ4DPresetButton(FractalNode fn, String name, QuaternionJulia4DParams preset) {
        Button btn = new Button(name);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> {
            if (fn.getFractalParams() instanceof QuaternionJulia4DParams p) {
                p.setMaxIterations(preset.getMaxIterations());
                p.setBailout(preset.getBailout());
                p.setJuliaCx(preset.getJuliaCx());
                p.setJuliaCy(preset.getJuliaCy());
                p.setJuliaCz(preset.getJuliaCz());
                p.setJuliaCw(preset.getJuliaCw());
                p.setSliceW(preset.getSliceW());
                p.setRotXW(preset.getRotXW());
                p.setRotYW(preset.getRotYW());
                p.setRotZW(preset.getRotZW());
                refreshDetailPanel();
                onParameterChange();
            }
        });
        detailPanel.getChildren().add(btn);
    }

    private void buildCSGDetail(CSGNode csn) {
        Label title = new Label("CSG Node");
        title.getStyleClass().add("bold-label");
        detailPanel.getChildren().add(title);
        buildNameField(csn);

        ComboBox<CSGNode.Op> opCombo = new ComboBox<>();
        opCombo.getItems().addAll(CSGNode.Op.values());
        opCombo.setValue(csn.getOp());
        opCombo.setMaxWidth(Double.MAX_VALUE);
        opCombo.setOnAction(e -> {
            pushUndoSnapshot();
            csn.setOp(opCombo.getValue());
            onStructuralChange();
        });

        Button swapBtn = new Button("Swap Left \u21c4 Right");
        swapBtn.setMaxWidth(Double.MAX_VALUE);
        swapBtn.setOnAction(e -> {
            pushUndoSnapshot();
            csn.swapChildren();
            onStructuralChange();
        });

        EnhancedSlider blendSlider = new EnhancedSlider("Blend", 0, 2, csn.getBlend(), false);
        blendSlider.setOnAction(v -> {
            csn.setBlend(v.floatValue());
            onParameterChange();
        });

        detailPanel.getChildren().addAll(new Label("Operation:"), opCombo, swapBtn, blendSlider);
    }

    private void buildTransformDetail(TransformNode tn) {
        // Mode selector (structural change)
        Label title = new Label(tn.getMode().getDisplayName() + " Node");
        title.getStyleClass().add("bold-label");
        detailPanel.getChildren().add(title);
        buildNameField(tn);

        ComboBox<TransformNode.Mode> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(TransformNode.Mode.values());
        modeCombo.setValue(tn.getMode());
        modeCombo.setMaxWidth(Double.MAX_VALUE);
        modeCombo.setOnAction(e -> {
            pushUndoSnapshot();
            tn.setMode(modeCombo.getValue());
            onStructuralChange();
        });

        detailPanel.getChildren().addAll(new Label("Mode:"), modeCombo);

        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));
        detailPanel.getChildren().add(sep);

        switch (tn.getMode()) {
            case STANDARD -> buildStandardTransformSliders(tn);
            case MIRROR -> buildMirrorSliders(tn);
            case TWIST -> buildTwistSliders(tn);
            case BEND -> buildBendSliders(tn);
            case TAPER -> buildTaperSliders(tn);
            case REPETITION -> buildRepetitionSliders(tn);
            case REPETITION_1D -> buildRepetition1DSliders(tn);
        }
    }

    private void buildEffectDetail(EffectNode en) {
        Label title = new Label(en.getEffectType().getDisplayName() + " Effect");
        title.getStyleClass().add("bold-label");
        detailPanel.getChildren().add(title);
        buildNameField(en);

        // Effect type selector (structural change → recompile)
        ComboBox<EffectNode.EffectType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(EffectNode.EffectType.values());
        typeCombo.setValue(en.getEffectType());
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setOnAction(e -> {
            pushUndoSnapshot();
            en.setEffectType(typeCombo.getValue());
            onStructuralChange();
        });
        detailPanel.getChildren().addAll(new Label("Effect Type:"), typeCombo);

        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));
        detailPanel.getChildren().add(sep);

        // Common sliders: strength, time, scale
        EnhancedSlider strengthSlider = new EnhancedSlider("Strength", 0, 1, en.getStrength(), false);
        strengthSlider.setOnAction(v -> {
            en.setStrength(v.floatValue());
            onParameterChange();
        });
        EnhancedSlider timeSlider = new EnhancedSlider("Time", 0, 20, en.getTime(), false);
        timeSlider.setOnAction(v -> {
            en.setTime(v.floatValue());
            onParameterChange();
        });
        EnhancedSlider scaleSlider = new EnhancedSlider("Scale", 0.1, 5, en.getScale(), false);
        scaleSlider.setOnAction(v -> {
            en.setScale(v.floatValue());
            onParameterChange();
        });
        detailPanel.getChildren().addAll(strengthSlider, timeSlider, scaleSlider);

        // Type-specific controls
        switch (en.getEffectType()) {
            case EROSION -> {
                ComboBox<String> erosionTypeCombo = new ComboBox<>();
                erosionTypeCombo.getItems().addAll("All", "Hydraulic", "Thermal", "Cracks");
                erosionTypeCombo.setValue(erosionTypeCombo.getItems().get(en.getErosionType()));
                erosionTypeCombo.setMaxWidth(Double.MAX_VALUE);
                erosionTypeCombo.setOnAction(e -> {
                    pushUndoSnapshot();
                    en.setErosionType(erosionTypeCombo.getItems().indexOf(erosionTypeCombo.getValue()));
                    onStructuralChange();
                });
                detailPanel.getChildren().addAll(new Label("Erosion Type:"), erosionTypeCombo);
            }
            case CRYSTAL -> {
                EnhancedSlider sharpSlider = new EnhancedSlider("Sharpness", 0.5, 5, en.getSharpness(), false);
                sharpSlider.setOnAction(v -> {
                    en.setSharpness(v.floatValue());
                    onParameterChange();
                });
                detailPanel.getChildren().add(sharpSlider);
            }
            case MOSS -> {
                Label note = new Label("Moss coloring is global (QualityPanel).\nThis node controls geometry only.");
                note.setWrapText(true);
                note.setStyle("-fx-text-fill: #888; -fx-font-size: 10px;");
                detailPanel.getChildren().add(note);
            }
        }
    }

    private void buildStandardTransformSliders(TransformNode tn) {
        float[] off = tn.getOffset();
        float[] rot = tn.getRotation();

        EnhancedSlider offX = new EnhancedSlider("Offset X", -5, 5, off[0], false);
        EnhancedSlider offY = new EnhancedSlider("Offset Y", -5, 5, off[1], false);
        EnhancedSlider offZ = new EnhancedSlider("Offset Z", -5, 5, off[2], false);
        offX.setOnAction(v -> { tn.getOffset()[0] = v.floatValue(); onParameterChange(); });
        offY.setOnAction(v -> { tn.getOffset()[1] = v.floatValue(); onParameterChange(); });
        offZ.setOnAction(v -> { tn.getOffset()[2] = v.floatValue(); onParameterChange(); });

        EnhancedSlider rotX = new EnhancedSlider("Rotation X", -180, 180, rot[0], false);
        EnhancedSlider rotY = new EnhancedSlider("Rotation Y", -180, 180, rot[1], false);
        EnhancedSlider rotZ = new EnhancedSlider("Rotation Z", -180, 180, rot[2], false);
        rotX.setOnAction(v -> { tn.getRotation()[0] = v.floatValue(); onParameterChange(); });
        rotY.setOnAction(v -> { tn.getRotation()[1] = v.floatValue(); onParameterChange(); });
        rotZ.setOnAction(v -> { tn.getRotation()[2] = v.floatValue(); onParameterChange(); });

        EnhancedSlider scaleSlider = new EnhancedSlider("Scale", 0.01, 10, tn.getScale(), false);
        scaleSlider.setOnAction(v -> { tn.setScale(v.floatValue()); onParameterChange(); });

        detailPanel.getChildren().addAll(offX, offY, offZ, rotX, rotY, rotZ, scaleSlider);
    }

    private void buildMirrorSliders(TransformNode tn) {
        ComboBox<String> axisCombo = createAxisCombo(tn);
        detailPanel.getChildren().addAll(new Label("Axis:"), axisCombo);

        EnhancedSlider offsetSlider = new EnhancedSlider("Offset", -5, 5,
            tn.getOffset()[tn.getAxis()], false);
        offsetSlider.setOnAction(v -> {
            tn.getOffset()[tn.getAxis()] = v.floatValue();
            onParameterChange();
        });
        detailPanel.getChildren().add(offsetSlider);
    }

    private void buildTwistSliders(TransformNode tn) {
        buildAxisStrengthFreqOffsetSliders(tn);
    }

    private void buildBendSliders(TransformNode tn) {
        buildAxisStrengthFreqOffsetSliders(tn);
    }

    private void buildTaperSliders(TransformNode tn) {
        buildAxisStrengthFreqOffsetSliders(tn);
    }

    private void buildAxisStrengthFreqOffsetSliders(TransformNode tn) {
        ComboBox<String> axisCombo = createAxisCombo(tn);
        detailPanel.getChildren().addAll(new Label("Axis:"), axisCombo);

        EnhancedSlider strengthSlider = new EnhancedSlider("Strength", -5, 5, tn.getScale(), false);
        strengthSlider.setOnAction(v -> { tn.setScale(v.floatValue()); onParameterChange(); });

        EnhancedSlider frequencySlider = new EnhancedSlider("Frequency", 0.1, 10, tn.getFrequency(), false);
        frequencySlider.setOnAction(v -> { tn.setFrequency(v.floatValue()); onParameterChange(); });

        EnhancedSlider offsetSlider = new EnhancedSlider("Offset", -10, 10, tn.getOffset()[0], false);
        offsetSlider.setOnAction(v -> { tn.getOffset()[0] = v.floatValue(); onParameterChange(); });

        detailPanel.getChildren().addAll(strengthSlider, frequencySlider, offsetSlider);
    }

    private void buildRepetition1DSliders(TransformNode tn) {
        ComboBox<String> axisCombo = createAxisCombo(tn);
        detailPanel.getChildren().addAll(new Label("Axis:"), axisCombo);

        int ax = tn.getAxis();
        EnhancedSlider periodSlider = new EnhancedSlider("Period", 0.1, 20,
            Math.max(0.1f, tn.getOffset()[ax]), false);
        periodSlider.setOnAction(v -> {
            tn.getOffset()[tn.getAxis()] = v.floatValue();
            onParameterChange();
        });
        detailPanel.getChildren().add(periodSlider);
    }

    private void buildRepetitionSliders(TransformNode tn) {
        float[] off = tn.getOffset();
        EnhancedSlider periodX = new EnhancedSlider("Period X", 0.1, 10, Math.max(0.1, off[0]), false);
        EnhancedSlider periodY = new EnhancedSlider("Period Y", 0.1, 10, Math.max(0.1, off[1]), false);
        EnhancedSlider periodZ = new EnhancedSlider("Period Z", 0.1, 10, Math.max(0.1, off[2]), false);
        periodX.setOnAction(v -> { tn.getOffset()[0] = v.floatValue(); onParameterChange(); });
        periodY.setOnAction(v -> { tn.getOffset()[1] = v.floatValue(); onParameterChange(); });
        periodZ.setOnAction(v -> { tn.getOffset()[2] = v.floatValue(); onParameterChange(); });
        detailPanel.getChildren().addAll(periodX, periodY, periodZ);
    }

    private ComboBox<String> createAxisCombo(TransformNode tn) {
        ComboBox<String> axisCombo = new ComboBox<>();
        axisCombo.getItems().addAll("X", "Y", "Z");
        axisCombo.setValue(new String[]{"X", "Y", "Z"}[tn.getAxis()]);
        axisCombo.setMaxWidth(Double.MAX_VALUE);
        axisCombo.setOnAction(e -> {
            tn.setAxis(axisCombo.getSelectionModel().getSelectedIndex());
            onStructuralChange();
        });
        return axisCombo;
    }

    // ========================================================================
    // Graph manipulation
    // ========================================================================

    private void addFractalNode() {
        if (currentParams == null) return;
        pushUndoSnapshot();

        if (selectedNode instanceof CSGNode csn) {
            GraphNode newFractal = new FractalNode(FractalType.MENGER_SPONGE);
            CSGNode newCSG = new CSGNode(CSGNode.Op.UNION, csn.getRight(), newFractal);
            csn.setRight(newCSG);
        } else {
            GraphNode newFractal = new FractalNode(FractalType.MENGER_SPONGE);
            GraphNode oldRoot = currentParams.getGraphRoot();
            CSGNode newRoot = new CSGNode(CSGNode.Op.UNION, oldRoot, newFractal);
            currentParams.setGraphRoot(newRoot);
        }
        onStructuralChange();
    }

    private void wrapInCSG() {
        if (currentParams == null || selectedNode == null) return;
        pushUndoSnapshot();

        GraphNode newFractal = new FractalNode(FractalType.MENGER_SPONGE);
        CSGNode wrapper = new CSGNode(CSGNode.Op.UNION, selectedNode, newFractal, 0.1f);

        replaceNode(selectedNode, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void wrapInTransform(TransformNode.Mode mode) {
        if (currentParams == null || selectedNode == null) return;
        pushUndoSnapshot();

        TransformNode wrapper;
        switch (mode) {
            case MIRROR -> wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
            case TWIST -> {
                wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
                wrapper.setScale(0.5f);
            }
            case BEND -> {
                wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
                wrapper.setScale(0.5f);
            }
            case TAPER -> {
                wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
                wrapper.setScale(0.3f);
            }
            case REPETITION -> wrapper = new TransformNode(selectedNode, new float[]{3, 3, 3});
            case REPETITION_1D -> {
                wrapper = new TransformNode(selectedNode, new float[]{3, 3, 3});
            }
            default -> wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
        }
        wrapper.setMode(mode);

        replaceNode(selectedNode, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void wrapInEffect(EffectNode.EffectType effectType) {
        if (currentParams == null || selectedNode == null) return;
        pushUndoSnapshot();

        EffectNode wrapper = new EffectNode(selectedNode, effectType);
        replaceNode(selectedNode, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void deleteSelected() {
        if (currentParams == null || selectedNode == null) return;
        pushUndoSnapshot();

        if (selectedNode == currentParams.getGraphRoot()) {
            if (selectedNode instanceof CSGNode csn) {
                currentParams.setGraphRoot(csn.getLeft());
                selectedNode = csn.getLeft();
            } else if (selectedNode instanceof EffectNode en) {
                currentParams.setGraphRoot(en.getChild());
                selectedNode = en.getChild();
            } else if (selectedNode instanceof TransformNode tn) {
                currentParams.setGraphRoot(tn.getChild());
                selectedNode = tn.getChild();
            } else {
                return;
            }
        } else {
            ParentRef parentRef = findParent(selectedNode);
            if (parentRef == null) return;
            if (selectedNode.getChildren().isEmpty()) {
                FractalNode replacement = new FractalNode(FractalType.MANDELBULB);
                parentRef.setChild(replacement);
                selectedNode = replacement;
            } else {
                GraphNode promoted = selectedNode.getChildren().get(0);
                parentRef.setChild(promoted);
                selectedNode = promoted;
            }
        }
        onStructuralChange();
    }

    private void duplicateSubtree(GraphNode node) {
        if (currentParams == null || node == null) return;
        pushUndoSnapshot();

        // Deep copy via serialize/deserialize round-trip
        Map<String, Object> serialized = FractalConfig.serializeGraphNode(node);
        GraphNode copy = FractalConfig.deserializeGraphNode(serialized);
        if (copy == null) return;

        // Wrap original + copy in a Union CSG
        CSGNode wrapper = new CSGNode(CSGNode.Op.UNION, node, copy, 0.1f);
        replaceNode(node, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void replaceNode(GraphNode oldNode, GraphNode newNode) {
        if (oldNode == currentParams.getGraphRoot()) {
            currentParams.setGraphRoot(newNode);
        } else {
            ParentRef ref = findParent(oldNode);
            if (ref != null) ref.setChild(newNode);
        }
    }

    // ========================================================================
    // Presets
    // ========================================================================

    private void applyPreset(String presetName) {
        if (currentParams == null || presetName == null) return;
        pushUndoSnapshot();

        switch (presetName) {
            case "Single Fractal" ->
                currentParams.setGraphRoot(new FractalNode(FractalType.MANDELBULB));
            case "Union" ->
                currentParams.setGraphRoot(new CSGNode(
                    CSGNode.Op.UNION,
                    new FractalNode(FractalType.MANDELBULB),
                    new TransformNode(
                        new FractalNode(FractalType.MENGER_SPONGE),
                        new float[]{2, 0, 0}
                    ),
                    0.1f
                ));
            case "Subtract + Transform" ->
                currentParams.setGraphRoot(new CSGNode(
                    CSGNode.Op.SUBTRACT,
                    new FractalNode(FractalType.MANDELBULB),
                    new TransformNode(
                        new FractalNode(FractalType.MENGER_SPONGE),
                        new float[]{0, 0, 0},
                        new float[]{0, 0, 0},
                        0.8f
                    ),
                    0.05f
                ));
            case "Mirror Symmetry" -> {
                TransformNode mirror = new TransformNode(
                    new FractalNode(FractalType.MANDELBULB),
                    new float[]{0, 0, 0}
                );
                mirror.setMode(TransformNode.Mode.MIRROR);
                mirror.setAxis(0); // mirror on X
                currentParams.setGraphRoot(mirror);
            }
            case "Eroded Mandelbulb" -> {
                EffectNode erosion = new EffectNode(
                    new FractalNode(FractalType.MANDELBULB),
                    EffectNode.EffectType.EROSION
                );
                erosion.setStrength(0.5f);
                erosion.setTime(5f);
                erosion.setScale(1f);
                erosion.setErosionType(0); // All
                currentParams.setGraphRoot(erosion);
            }
            case "Crystal Menger" -> {
                EffectNode crystal = new EffectNode(
                    new FractalNode(FractalType.MENGER_SPONGE),
                    EffectNode.EffectType.CRYSTAL
                );
                crystal.setStrength(0.6f);
                crystal.setTime(5f);
                crystal.setScale(1f);
                crystal.setSharpness(2.5f);
                currentParams.setGraphRoot(crystal);
            }
            case "Mossy Rocks" -> {
                EffectNode moss = new EffectNode(
                    new FractalNode(FractalType.APOLLONIAN),
                    EffectNode.EffectType.MOSS
                );
                moss.setStrength(0.7f);
                moss.setTime(5f);
                moss.setScale(1.2f);
                currentParams.setGraphRoot(moss);
            }
            case "Stacked Effects" -> {
                // Erosion on top of Crystal on a Mandelbulb
                EffectNode crystal = new EffectNode(
                    new FractalNode(FractalType.MANDELBULB),
                    EffectNode.EffectType.CRYSTAL
                );
                crystal.setStrength(0.5f);
                crystal.setTime(4f);
                crystal.setSharpness(2f);
                EffectNode erosion = new EffectNode(
                    crystal,
                    EffectNode.EffectType.EROSION
                );
                erosion.setStrength(0.4f);
                erosion.setTime(5f);
                erosion.setErosionType(3); // Cracks
                currentParams.setGraphRoot(erosion);
            }
        }

        presetCombo.setValue(null);
        presetCombo.setPromptText("Presets...");
        selectedNode = currentParams.getGraphRoot();
        onStructuralChange();
    }

    // ========================================================================
    // Structural vs parameter change
    // ========================================================================

    private void onStructuralChange() {
        if (currentParams.getGraphRoot() != null) {
            GraphNodeNamer.ensureAllNamed(currentParams.getGraphRoot());
        }
        currentParams.markDirty();
        redrawCanvas();
        refreshDetailPanel();
        if (onGraphStructureChanged != null) {
            onGraphStructureChanged.run();
        }
        // Defer GL compilation to next FX pulse so UI updates are visible immediately
        statusLabel.setText("Compiling...");
        statusLabel.getStyleClass().removeAll("success", "error");
        Platform.runLater(this::autoCompile);
    }

    private void onParameterChange() {
        currentParams.updateUniforms();
        redrawCanvas();
        renderCallback.requestRender();
    }

    private void autoCompile() {
        if (currentParams == null) return;

        String glsl = currentParams.recompile();
        if (glsl == null) {
            statusLabel.setText("Compilation failed");
            statusLabel.getStyleClass().removeAll("success", "error");
            statusLabel.getStyleClass().add("error");
            return;
        }

        String error = controller.compileNodeGraph(glsl);
        if (error != null) {
            statusLabel.setText("GPU error: " + error);
            statusLabel.getStyleClass().removeAll("success", "error");
            statusLabel.getStyleClass().add("error");
        } else {
            int uniformCount = currentParams.getUniformValues().size();
            statusLabel.setText(String.format("Compiled OK \u2014 %d chars, %d uniforms",
                glsl.length(), uniformCount));
            statusLabel.getStyleClass().removeAll("success", "error");
            statusLabel.getStyleClass().add("success");
            renderCallback.requestRender();
        }
    }
}
