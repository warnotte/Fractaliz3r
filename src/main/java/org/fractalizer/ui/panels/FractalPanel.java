package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.components.EnhancedSlider;
import org.fractalizer.ui.components.NodeGraphEditor;

/**
 * Panel for fractal editing via the NodeGraphEditor.
 * All fractal type selection and parameter editing is done through the node graph.
 * Navigation controls (speed, position, camera reset) remain here.
 */
public class FractalPanel extends ScrollPane implements Refreshable {

    private final RenderController controller;
    private final RenderCallback renderCallback;

    private AbstractFractalParams params;
    private Camera camera;
    private boolean suppressRender = false;

    private java.util.function.BiConsumer<FractalType, AbstractFractalParams> onFractalTypeChanged;

    // Node Graph Editor (the single fractal editing UI)
    private final NodeGraphEditor nodeGraphEditor;

    // Navigation controls
    private EnhancedSlider speedSlider;
    private Label positionLabel;

    public FractalPanel(RenderController controller, AbstractFractalParams initialParams,
                        RenderCallback renderCallback) {
        this.controller = controller;
        this.params = initialParams;
        this.camera = initialParams.getCamera();
        this.renderCallback = renderCallback;

        nodeGraphEditor = new NodeGraphEditor(controller, renderCallback);

        setContent(createContent());
        setFitToWidth(true);
        setFitToHeight(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Node Graph Editor takes all available space
        VBox.setVgrow(nodeGraphEditor, Priority.ALWAYS);

        // Navigation & camera
        // Logarithmic, the range of the wheel over the viewport: a dive runs the speed from 0.1
        // down to 1e-10, and the camera holds its position in double to follow it.
        speedSlider = EnhancedSlider.logarithmic("Move Speed", 1e-12, 1.0, 0.1);
        speedSlider.setOnAction(v -> {
            camera.setMoveSpeed(v.floatValue());
            updatePositionLabel();
        });

        autoSpeedCheck = new javafx.scene.control.CheckBox("Auto speed (fraction of the gap ahead)");
        autoSpeedCheck.setSelected(true);
        autoSpeedCheck.setTooltip(new javafx.scene.control.Tooltip(
                "On: each frame moves the camera by a fraction of the free distance ahead in the\n" +
                "direction it is going, measured on the scene itself, so a dive keeps its pace at\n" +
                "any depth (a decade a second at 0.1) and never crosses the surface; Move Speed is\n" +
                "that fraction, and the step far from everything. Off: Move Speed is the step."));
        autoSpeedCheck.setOnAction(e -> { if (autoSpeedListener != null) autoSpeedListener.accept(autoSpeedCheck.isSelected()); });

        positionLabel = new Label("Pos: (0.00, 0.00, -3.00)");
        positionLabel.getStyleClass().add("mono-label");

        Label navLabel = new Label("Navigation:");
        navLabel.getStyleClass().add("bold-label");
        Label helpLabel = new Label(
            "Drag: Look around\n" +
            "Arrows: Move\n" +
            "Q/E: Roll\n" +
            "PgUp/Dn: Up/Down\n" +
            "R: Reset camera\n" +
            "Space: Render full\n" +
            "Scroll: Adjust speed"
        );
        helpLabel.getStyleClass().add("small-label");

        Button resetBtn = new Button("Reset Camera (R)");
        resetBtn.setOnAction(e -> {
            camera.reset();
            renderCallback.requestRender();
            updatePositionLabel();
        });
        resetBtn.setMaxWidth(Double.MAX_VALUE);

        VBox navBox = new VBox(5, speedSlider, autoSpeedCheck, positionLabel, navLabel, helpLabel, resetBtn);
        TitledPane navPane = new TitledPane("Navigation", navBox);
        navPane.setExpanded(true);

        panel.getChildren().addAll(nodeGraphEditor, navPane);

        // Don't load params here — Scene doesn't exist yet.
        // The first refreshFromParams() call (after Scene is set) will do it.

        return panel;
    }

    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress;
        try {
            if (params instanceof NodeGraphParams ngp) {
                if (suppressRender && nodeGraphEditor.isLoaded() && nodeGraphEditor.getCurrentParams() == ngp) {
                    nodeGraphEditor.refreshSliders();
                } else {
                    nodeGraphEditor.loadParams(ngp);
                }
            }
            speedSlider.setValue(camera.getMoveSpeed());
            updatePositionLabel();
        } finally {
            suppressRender = false;
        }
    }

    public void updatePositionLabel() {
        if (positionLabel != null && camera != null) {
            // Nine decimals: on a dive the camera moves by 1e-9 and two decimals show nothing.
            double[] pos = camera.getPositionD();
            positionLabel.setText(String.format(java.util.Locale.ROOT, "Pos: (%.9f, %.9f, %.9f)\nSpeed: %.3g",
                    pos[0], pos[1], pos[2], camera.getMoveSpeed()));
        }
    }

    private javafx.scene.control.CheckBox autoSpeedCheck;
    private java.util.function.Consumer<Boolean> autoSpeedListener;

    /** Who to tell when the auto-speed box changes (the navigation controller); told at once. */
    public void setAutoSpeedListener(java.util.function.Consumer<Boolean> listener) {
        this.autoSpeedListener = listener;
        if (listener != null && autoSpeedCheck != null) listener.accept(autoSpeedCheck.isSelected());
    }

    public Camera getCamera() {
        return camera;
    }

    public AbstractFractalParams getParams() {
        return params;
    }

    public void setSpeedSliderValue(double value) {
        if (speedSlider != null) {
            speedSlider.setValue(value);
            updatePositionLabel();
        }
    }

    public void setParams(AbstractFractalParams newParams) {
        this.params = newParams;
        this.camera = newParams.getCamera();
        // Force full reload (suppress=false) so NodeGraphEditor rebuilds from
        // the deserialized graph tree, not just refreshes sliders.
        refreshFromParams(false);
    }

    public void setOnFractalTypeChanged(java.util.function.BiConsumer<FractalType, AbstractFractalParams> callback) {
        this.onFractalTypeChanged = callback;
    }

    public NodeGraphEditor getNodeGraphEditor() {
        return nodeGraphEditor;
    }
}
