package org.fractalizer.ui.components;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.fractalizer.fractals.*;
import org.fractalizer.graph.*;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.panels.RenderCallback;

import java.util.*;

/**
 * Visual editor for node-based fractal DE composition graphs.
 * Canvas-based node graph on the left, detail panel on the right.
 */
public class NodeGraphEditor extends VBox {

    private static final List<FractalType> EXCLUDED_TYPES = Arrays.asList(
        FractalType.TEST_SCENE, FractalType.CORNELL_BOX,
        FractalType.FRACTAL_TERRAIN, FractalType.CUSTOM_SHADER,
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
    private static final Color COLOR_REPETITION = Color.web("#00BCD4");
    private static final Color COLOR_SELECTED = Color.web("#00BCD4");
    private static final Color COLOR_CONNECTION = Color.web("#9E9E9E", 0.6);
    private static final Color COLOR_TEXT = Color.WHITE;
    private static final Color COLOR_CANVAS_BG = Color.web("#1E1E1E");

    // Slider ranges keyed by @Animatable field name
    private static final Map<String, double[]> SLIDER_RANGES = new HashMap<>();
    static {
        SLIDER_RANGES.put("maxIterations", new double[]{1, 50});
        SLIDER_RANGES.put("power", new double[]{2, 20});
        SLIDER_RANGES.put("bailout", new double[]{0.5, 10});
        SLIDER_RANGES.put("scale", new double[]{-5, 5});
        SLIDER_RANGES.put("minRadius", new double[]{0.01, 2});
        SLIDER_RANGES.put("fixedRadius", new double[]{0.01, 4});
        SLIDER_RANGES.put("foldingLimit", new double[]{0, 3});
        SLIDER_RANGES.put("offsetX", new double[]{-3, 3});
        SLIDER_RANGES.put("offsetY", new double[]{-3, 3});
        SLIDER_RANGES.put("offsetZ", new double[]{-3, 3});
        SLIDER_RANGES.put("shiftX", new double[]{-3, 3});
        SLIDER_RANGES.put("shiftY", new double[]{-3, 3});
        SLIDER_RANGES.put("shiftZ", new double[]{-3, 3});
        SLIDER_RANGES.put("foldAngleX", new double[]{0, 180});
        SLIDER_RANGES.put("foldAngleY", new double[]{0, 180});
        SLIDER_RANGES.put("ifsOffset", new double[]{-3, 3});
        SLIDER_RANGES.put("rot1X", new double[]{-180, 180});
        SLIDER_RANGES.put("rot1Y", new double[]{-180, 180});
        SLIDER_RANGES.put("rot1Z", new double[]{-180, 180});
        SLIDER_RANGES.put("rot2X", new double[]{-180, 180});
        SLIDER_RANGES.put("rot2Y", new double[]{-180, 180});
        SLIDER_RANGES.put("rot2Z", new double[]{-180, 180});
        SLIDER_RANGES.put("CSizeX", new double[]{0, 2});
        SLIDER_RANGES.put("CSizeY", new double[]{0, 2});
        SLIDER_RANGES.put("CSizeZ", new double[]{0, 2});
        SLIDER_RANGES.put("Size", new double[]{0, 2});
        SLIDER_RANGES.put("DEOffset", new double[]{0, 1});
        SLIDER_RANGES.put("foldCx", new double[]{-2, 2});
        SLIDER_RANGES.put("foldCy", new double[]{-2, 2});
        SLIDER_RANGES.put("foldCz", new double[]{-2, 2});
        SLIDER_RANGES.put("foldRadius", new double[]{0.1, 5});
        SLIDER_RANGES.put("juliaCx", new double[]{-2, 2});
        SLIDER_RANGES.put("juliaCy", new double[]{-2, 2});
        SLIDER_RANGES.put("juliaCz", new double[]{-2, 2});
        SLIDER_RANGES.put("juliaCw", new double[]{-2, 2});
        SLIDER_RANGES.put("sliceW", new double[]{-2, 2});
        SLIDER_RANGES.put("rotXW", new double[]{-180, 180});
        SLIDER_RANGES.put("rotYW", new double[]{-180, 180});
        SLIDER_RANGES.put("rotZW", new double[]{-180, 180});
    }

    private final RenderController controller;
    private final RenderCallback renderCallback;

    private final Canvas canvas;
    private final VBox detailPanel;
    private final Label statusLabel;
    private final ComboBox<String> presetCombo;

    private NodeGraphParams currentParams;
    private GraphNode selectedNode;
    private final List<NodeLayout> nodeLayouts = new ArrayList<>();

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

        Button deleteBtn = new Button("Delete");
        deleteBtn.setTooltip(new Tooltip("Remove selected node"));
        deleteBtn.setOnAction(e -> deleteSelected());

        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("Single Fractal", "Union", "Subtract + Transform", "Mirror Symmetry");
        presetCombo.setPromptText("Presets...");
        presetCombo.setOnAction(e -> applyPreset(presetCombo.getValue()));

        HBox toolbar = new HBox(4, addFractalBtn, wrapCSGBtn, wrapTransformBtn, moreTransforms, deleteBtn, presetCombo);
        toolbar.setPadding(new Insets(2));

        // Canvas for visual node graph
        canvas = new Canvas(400, 250);
        canvas.setOnMouseClicked(e -> handleCanvasClick(e.getX(), e.getY()));
        StackPane canvasPane = new StackPane(canvas);
        canvasPane.setMinSize(0, 0);
        canvasPane.getStyleClass().add("node-graph-canvas");

        ScrollPane canvasScroll = new ScrollPane(canvasPane);
        canvasScroll.setPannable(true);
        canvasScroll.setFitToWidth(true);

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
        splitPane.setPrefHeight(280);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // Status
        statusLabel = new Label("No graph loaded");
        statusLabel.getStyleClass().add("compile-status");

        getChildren().addAll(toolbar, splitPane, statusLabel);
    }

    /**
     * Load params and rebuild canvas from the graph root.
     */
    public void loadParams(NodeGraphParams params) {
        this.currentParams = params;
        selectedNode = (params != null) ? params.getGraphRoot() : null;
        redrawCanvas();
        refreshDetailPanel();
        autoCompile();
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

        canvas.setWidth(Math.max(400, totalW));
        canvas.setHeight(Math.max(250, totalH));

        positionNodes(root, PADDING, PADDING);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(COLOR_CANVAS_BG);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

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
        if (node instanceof CSGNode) return COLOR_CSG;
        if (node instanceof TransformNode tn) {
            return switch (tn.getMode()) {
                case MIRROR -> COLOR_MIRROR;
                case TWIST -> COLOR_TWIST;
                case REPETITION -> COLOR_REPETITION;
                default -> COLOR_TRANSFORM;
            };
        }
        return Color.GRAY;
    }

    private String getNodeLabel(GraphNode node) {
        if (node instanceof FractalNode fn) return fn.getFractalType().getDisplayName();
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
        } else if (selectedNode instanceof CSGNode csn) {
            buildCSGDetail(csn);
        } else if (selectedNode instanceof TransformNode tn) {
            buildTransformDetail(tn);
        }
    }

    private void buildFractalDetail(FractalNode fn) {
        Label title = new Label("Fractal Node");
        title.getStyleClass().add("bold-label");

        ComboBox<FractalType> typeCombo = new ComboBox<>();
        for (FractalType ft : FractalType.values()) {
            if (!EXCLUDED_TYPES.contains(ft)) typeCombo.getItems().add(ft);
        }
        typeCombo.setValue(fn.getFractalType());
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setOnAction(e -> {
            fn.setFractalType(typeCombo.getValue());
            onStructuralChange();
        });

        detailPanel.getChildren().addAll(title, new Label("Type:"), typeCombo);

        // Auto-discover @Animatable parameters and create sliders
        AbstractFractalParams params = fn.getFractalParams();
        if (params != null) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(4, 0, 4, 0));
            detailPanel.getChildren().add(sep);

            for (AnimatableParameter ap : params.getAnimatableParameters()) {
                if (ap.valueType() != Float.class && ap.valueType() != Integer.class) continue;

                boolean isInt = (ap.valueType() == Integer.class);
                double[] range = SLIDER_RANGES.getOrDefault(ap.name(),
                    isInt ? new double[]{1, 50} : new double[]{0, 10});

                Number currentVal = (Number) ap.getter().get();
                EnhancedSlider slider = new EnhancedSlider(ap.displayName(), range[0], range[1],
                    currentVal.doubleValue(), isInt);
                slider.setOnAction(v -> {
                    ap.setter().accept(isInt ? v.intValue() : v.floatValue());
                    onParameterChange();
                });
                detailPanel.getChildren().add(slider);
            }
        }
    }

    private void buildCSGDetail(CSGNode csn) {
        Label title = new Label("CSG Node");
        title.getStyleClass().add("bold-label");

        ComboBox<CSGNode.Op> opCombo = new ComboBox<>();
        opCombo.getItems().addAll(CSGNode.Op.values());
        opCombo.setValue(csn.getOp());
        opCombo.setMaxWidth(Double.MAX_VALUE);
        opCombo.setOnAction(e -> {
            csn.setOp(opCombo.getValue());
            onStructuralChange();
        });

        Button swapBtn = new Button("Swap Left \u21c4 Right");
        swapBtn.setMaxWidth(Double.MAX_VALUE);
        swapBtn.setOnAction(e -> {
            csn.swapChildren();
            onStructuralChange();
        });

        EnhancedSlider blendSlider = new EnhancedSlider("Blend", 0, 2, csn.getBlend(), false);
        blendSlider.setOnAction(v -> {
            csn.setBlend(v.floatValue());
            onParameterChange();
        });

        detailPanel.getChildren().addAll(title, new Label("Operation:"), opCombo, swapBtn, blendSlider);
    }

    private void buildTransformDetail(TransformNode tn) {
        // Mode selector (structural change)
        Label title = new Label(tn.getMode().getDisplayName() + " Node");
        title.getStyleClass().add("bold-label");

        ComboBox<TransformNode.Mode> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(TransformNode.Mode.values());
        modeCombo.setValue(tn.getMode());
        modeCombo.setMaxWidth(Double.MAX_VALUE);
        modeCombo.setOnAction(e -> {
            tn.setMode(modeCombo.getValue());
            onStructuralChange();
        });

        detailPanel.getChildren().addAll(title, new Label("Mode:"), modeCombo);

        Separator sep = new Separator();
        sep.setPadding(new Insets(4, 0, 4, 0));
        detailPanel.getChildren().add(sep);

        switch (tn.getMode()) {
            case STANDARD -> buildStandardTransformSliders(tn);
            case MIRROR -> buildMirrorSliders(tn);
            case TWIST -> buildTwistSliders(tn);
            case REPETITION -> buildRepetitionSliders(tn);
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
        ComboBox<String> axisCombo = createAxisCombo(tn);
        detailPanel.getChildren().addAll(new Label("Axis:"), axisCombo);

        EnhancedSlider strengthSlider = new EnhancedSlider("Strength", -5, 5, tn.getScale(), false);
        strengthSlider.setOnAction(v -> { tn.setScale(v.floatValue()); onParameterChange(); });
        detailPanel.getChildren().add(strengthSlider);
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

        GraphNode newFractal = new FractalNode(FractalType.MENGER_SPONGE);
        CSGNode wrapper = new CSGNode(CSGNode.Op.UNION, selectedNode, newFractal, 0.1f);

        replaceNode(selectedNode, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void wrapInTransform(TransformNode.Mode mode) {
        if (currentParams == null || selectedNode == null) return;

        TransformNode wrapper;
        switch (mode) {
            case MIRROR -> wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
            case TWIST -> {
                wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
                wrapper.setScale(0.5f); // default twist strength
            }
            case REPETITION -> wrapper = new TransformNode(selectedNode, new float[]{3, 3, 3});
            default -> wrapper = new TransformNode(selectedNode, new float[]{0, 0, 0});
        }
        wrapper.setMode(mode);

        replaceNode(selectedNode, wrapper);
        selectedNode = wrapper;
        onStructuralChange();
    }

    private void deleteSelected() {
        if (currentParams == null || selectedNode == null) return;

        if (selectedNode == currentParams.getGraphRoot()) {
            if (selectedNode instanceof CSGNode csn) {
                currentParams.setGraphRoot(csn.getLeft());
                selectedNode = csn.getLeft();
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
        currentParams.markDirty();
        redrawCanvas();
        refreshDetailPanel();
        autoCompile();
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
