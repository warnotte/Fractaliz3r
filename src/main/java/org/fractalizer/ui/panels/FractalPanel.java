package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.RenderController;

/**
 * Panel for fractal type selection and fractal-specific parameters.
 * Implements Refreshable for save/load configuration support.
 */
public class FractalPanel extends ScrollPane implements Refreshable {

    private final RenderController controller;
    private final RenderCallback renderCallback;

    // Current state
    private AbstractFractalParams params;
    private Camera camera;

    // Suppress render during refresh
    private boolean suppressRender = false;

    // Fractal type combo
    private ComboBox<FractalType> typeCombo;

    // Fractal-specific control containers
    private VBox mandelbulbControls;
    private VBox mandelboxControls;
    private VBox mengerControls;
    private VBox kaleidoscopicControls;
    private VBox julia3dControls;
    private VBox pseudoKleinianControls;

    // Common controls
    private Slider speedSlider;
    private Label positionLabel;

    // Mandelbulb sliders
    private Slider mbPowerSlider;
    private Slider mbIterSlider;
    private Slider mbBailoutSlider;
    private Label mbPowerLabel;
    private Label mbIterLabel;
    private Label mbBailoutLabel;

    // Mandelbox sliders
    private Slider mbxScaleSlider;
    private Slider mbxMinRadiusSlider;
    private Slider mbxFixedRadiusSlider;
    private Slider mbxFoldingLimitSlider;
    private Slider mbxIterSlider;

    // Menger sliders
    private Slider mengerIterSlider;
    private Slider mengerScaleSlider;

    // Kaleidoscopic sliders
    private Slider kIterSlider;
    private Slider kScaleSlider;
    private Slider kOffsetSlider;
    private Slider kFoldXSlider;
    private Slider kFoldYSlider;

    // Julia3D sliders
    private Slider j3dIterSlider;
    private Slider j3dCxSlider;
    private Slider j3dCySlider;
    private Slider j3dCzSlider;
    private Slider j3dCwSlider;

    // PseudoKleinian sliders
    private Slider pkIterSlider;
    private Slider pkSizeSlider;
    private Slider pkCsizeXSlider;
    private Slider pkCsizeYSlider;
    private Slider pkCsizeZSlider;
    private Slider pkJuliaXSlider;
    private Slider pkJuliaYSlider;
    private Slider pkJuliaZSlider;
    private Slider pkDeOffsetSlider;
    private Slider pkZOffsetSlider;

    public FractalPanel(RenderController controller, AbstractFractalParams initialParams,
                        RenderCallback renderCallback) {
        this.controller = controller;
        this.params = initialParams;
        this.camera = initialParams.getCamera();
        this.renderCallback = renderCallback;

        setContent(createContent());
        setFitToWidth(true);
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Fractal type selector
        Label typeLabel = new Label("Fractal Type:");
        typeCombo = createTypeComboBox();

        // Create all fractal-specific controls
        createMandelbulbControls();
        createMandelboxControls();
        createMengerControls();
        createKaleidoscopicControls();
        createJulia3dControls();
        createPseudoKleinianControls();

        // Movement speed
        Label speedLabel = new Label("Move Speed: 0.10");
        speedSlider = new Slider(0.001, 0.5, 0.1);
        speedSlider.valueProperty().addListener((obs, old, val) -> {
            speedLabel.setText(String.format("Move Speed: %.3f", val.doubleValue()));
            camera.setMoveSpeed(val.floatValue());
        });

        // Position display
        positionLabel = new Label("Pos: (0.00, 0.00, -3.00)");
        positionLabel.setStyle("-fx-font-family: monospace;");

        // Navigation help
        Label navLabel = new Label("Navigation:");
        navLabel.setStyle("-fx-font-weight: bold;");
        Label helpLabel = new Label(
            "Drag: Look around\n" +
            "Arrows: Move\n" +
            "Q/E: Roll\n" +
            "PgUp/Dn: Up/Down\n" +
            "R: Reset camera\n" +
            "Space: Render full\n" +
            "Scroll: Adjust speed"
        );
        helpLabel.setStyle("-fx-font-size: 11px;");

        // Reset button
        Button resetBtn = new Button("Reset Camera (R)");
        resetBtn.setOnAction(e -> {
            camera.reset();
            renderCallback.requestRender();
            updatePositionLabel();
        });
        resetBtn.setMaxWidth(Double.MAX_VALUE);

        panel.getChildren().addAll(
            typeLabel, typeCombo,
            new Separator(),
            mandelbulbControls,
            mandelboxControls,
            mengerControls,
            kaleidoscopicControls,
            julia3dControls,
            pseudoKleinianControls,
            speedLabel, speedSlider,
            new Separator(),
            positionLabel,
            new Separator(),
            navLabel, helpLabel,
            new Separator(),
            resetBtn
        );

        return panel;
    }

    private ComboBox<FractalType> createTypeComboBox() {
        ComboBox<FractalType> combo = new ComboBox<>();
        combo.getItems().addAll(FractalType.MANDELBULB, FractalType.MANDELBOX,
                                    FractalType.MENGER_SPONGE, FractalType.KALEIDOSCOPIC_IFS,
                                    FractalType.JULIA_3D, FractalType.PSEUDO_KLEINIAN);
        combo.setValue(FractalType.MANDELBULB);
        combo.setMaxWidth(Double.MAX_VALUE);

        // Display friendly names
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });

        // Fractal type change handler
        combo.setOnAction(e -> {
            if (suppressRender) return;
            FractalType selectedType = combo.getValue();
            controller.setFractalType(selectedType);
            params = (AbstractFractalParams) controller.getParams();
            camera = params.getCamera();

            // Hide all fractal-specific controls
            mandelbulbControls.setVisible(false);
            mandelbulbControls.setManaged(false);
            mandelboxControls.setVisible(false);
            mandelboxControls.setManaged(false);
            mengerControls.setVisible(false);
            mengerControls.setManaged(false);
            kaleidoscopicControls.setVisible(false);
            kaleidoscopicControls.setManaged(false);
            julia3dControls.setVisible(false);
            julia3dControls.setManaged(false);
            pseudoKleinianControls.setVisible(false);
            pseudoKleinianControls.setManaged(false);

            // Show only the relevant controls
            switch (selectedType) {
                case MANDELBULB -> {
                    mandelbulbControls.setVisible(true);
                    mandelbulbControls.setManaged(true);
                }
                case MANDELBOX -> {
                    mandelboxControls.setVisible(true);
                    mandelboxControls.setManaged(true);
                }
                case MENGER_SPONGE -> {
                    mengerControls.setVisible(true);
                    mengerControls.setManaged(true);
                }
                case KALEIDOSCOPIC_IFS -> {
                    kaleidoscopicControls.setVisible(true);
                    kaleidoscopicControls.setManaged(true);
                }
                case JULIA_3D -> {
                    julia3dControls.setVisible(true);
                    julia3dControls.setManaged(true);
                }
                case PSEUDO_KLEINIAN -> {
                    pseudoKleinianControls.setVisible(true);
                    pseudoKleinianControls.setManaged(true);
                }
            }

            renderCallback.requestRender();
        });

        return combo;
    }

    private void createMandelbulbControls() {
        mandelbulbControls = new VBox(8);

        mbPowerLabel = new Label("Power: 8.0");
        mbPowerSlider = new Slider(2, 16, 8);
        mbPowerSlider.setShowTickLabels(true);
        mbPowerSlider.setShowTickMarks(true);
        mbPowerSlider.valueProperty().addListener((obs, old, val) -> {
            mbPowerLabel.setText(String.format("Power: %.1f", val.doubleValue()));
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.power(val.floatValue());
                renderCallback.requestRender();
            }
        });

        mbIterLabel = new Label("Iterations: 15");
        mbIterSlider = new Slider(5, 30, 15);
        mbIterSlider.setShowTickLabels(true);
        mbIterSlider.valueProperty().addListener((obs, old, val) -> {
            mbIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.iterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        mbBailoutLabel = new Label("Bailout: 2.0");
        mbBailoutSlider = new Slider(1, 16, 2.0);
        mbBailoutSlider.setShowTickLabels(true);
        mbBailoutSlider.valueProperty().addListener((obs, old, val) -> {
            mbBailoutLabel.setText(String.format("Bailout: %.2f", val.floatValue()));
            if (!suppressRender && params instanceof MandelbulbParams mbParams) {
                mbParams.setBailout(val.floatValue());
                renderCallback.requestRender();
            }
        });

        mandelbulbControls.getChildren().addAll(
            mbPowerLabel, mbPowerSlider,
            mbIterLabel, mbIterSlider,
            mbBailoutLabel, mbBailoutSlider
        );
    }

    private void createMandelboxControls() {
        mandelboxControls = new VBox(8);

        Label scaleLabel = new Label("Scale: 2.0");
        mbxScaleSlider = new Slider(-3, 3, 2);
        mbxScaleSlider.setShowTickLabels(true);
        mbxScaleSlider.valueProperty().addListener((obs, old, val) -> {
            scaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof MandelboxParams mbxParams) {
                mbxParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label minRadiusLabel = new Label("Min Radius: 0.25");
        mbxMinRadiusSlider = new Slider(0.01, 1.0, 0.25);
        mbxMinRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            minRadiusLabel.setText(String.format("Min Radius: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof MandelboxParams mbxParams) {
                mbxParams.setMinRadius(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label fixedRadiusLabel = new Label("Fixed Radius: 1.0");
        mbxFixedRadiusSlider = new Slider(0.5, 2.0, 1.0);
        mbxFixedRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            fixedRadiusLabel.setText(String.format("Fixed Radius: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof MandelboxParams mbxParams) {
                mbxParams.setFixedRadius(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label foldingLimitLabel = new Label("Folding Limit: 1.0");
        mbxFoldingLimitSlider = new Slider(0.5, 2.0, 1.0);
        mbxFoldingLimitSlider.valueProperty().addListener((obs, old, val) -> {
            foldingLimitLabel.setText(String.format("Folding Limit: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof MandelboxParams mbxParams) {
                mbxParams.setFoldingLimit(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label iterLabel = new Label("Iterations: 15");
        mbxIterSlider = new Slider(5, 30, 15);
        mbxIterSlider.setShowTickLabels(true);
        mbxIterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof MandelboxParams mbxParams) {
                mbxParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        mandelboxControls.getChildren().addAll(
            scaleLabel, mbxScaleSlider,
            minRadiusLabel, mbxMinRadiusSlider,
            fixedRadiusLabel, mbxFixedRadiusSlider,
            foldingLimitLabel, mbxFoldingLimitSlider,
            iterLabel, mbxIterSlider
        );
        mandelboxControls.setVisible(false);
        mandelboxControls.setManaged(false);
    }

    private void createMengerControls() {
        mengerControls = new VBox(8);

        Label iterLabel = new Label("Iterations: 6");
        mengerIterSlider = new Slider(2, 10, 6);
        mengerIterSlider.setShowTickLabels(true);
        mengerIterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof MengerSpongeParams msParams) {
                msParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        Label scaleLabel = new Label("Scale: 3.0");
        mengerScaleSlider = new Slider(2.0, 4.0, 3.0);
        mengerScaleSlider.valueProperty().addListener((obs, old, val) -> {
            scaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof MengerSpongeParams msParams) {
                msParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        mengerControls.getChildren().addAll(
            iterLabel, mengerIterSlider,
            scaleLabel, mengerScaleSlider
        );
        mengerControls.setVisible(false);
        mengerControls.setManaged(false);
    }

    private void createKaleidoscopicControls() {
        kaleidoscopicControls = new VBox(8);

        // Info label about parameter relationships
        Label infoLabel = new Label("Classic Sierpinski: Scale=2, Offset=3");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label iterLabel = new Label("Iterations: 15");
        kIterSlider = new Slider(4, 25, 15);
        kIterSlider.setShowTickLabels(true);
        kIterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        // Scale: typically 1.5 to 3.0, default 2.0 for Sierpinski
        Label scaleLabel = new Label("Scale: 2.0");
        kScaleSlider = new Slider(1.5, 3.0, 2.0);
        kScaleSlider.setShowTickLabels(true);
        kScaleSlider.setMajorTickUnit(0.5);
        kScaleSlider.valueProperty().addListener((obs, old, val) -> {
            scaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Offset: critical parameter! For scale=2, offset should be around 3 (scale+1)
        Label offsetLabel = new Label("Offset: 3.0");
        kOffsetSlider = new Slider(1.0, 5.0, 3.0);
        kOffsetSlider.setShowTickLabels(true);
        kOffsetSlider.setMajorTickUnit(1.0);
        kOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            offsetLabel.setText(String.format("Offset: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setOffset(val.floatValue(), 0, 0);
                renderCallback.requestRender();
            }
        });

        // Rotation angles: 0 = pure Sierpinski, small values create variations
        Label foldXLabel = new Label("Rotation X: 0");
        kFoldXSlider = new Slider(-30, 30, 0);
        kFoldXSlider.setShowTickLabels(true);
        kFoldXSlider.setMajorTickUnit(15);
        kFoldXSlider.valueProperty().addListener((obs, old, val) -> {
            foldXLabel.setText(String.format("Rotation X: %.0f°", val.doubleValue()));
            if (!suppressRender && params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setFoldAngleX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label foldYLabel = new Label("Rotation Y: 0");
        kFoldYSlider = new Slider(-30, 30, 0);
        kFoldYSlider.setShowTickLabels(true);
        kFoldYSlider.setMajorTickUnit(15);
        kFoldYSlider.valueProperty().addListener((obs, old, val) -> {
            foldYLabel.setText(String.format("Rotation Y: %.0f°", val.doubleValue()));
            if (!suppressRender && params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setFoldAngleY(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Preset buttons for common configurations
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button sierpinskiBtn = new Button("Sierpinski");
        sierpinskiBtn.setOnAction(e -> {
            kScaleSlider.setValue(2.0);
            kOffsetSlider.setValue(3.0);
            kFoldXSlider.setValue(0);
            kFoldYSlider.setValue(0);
        });

        Button variation1Btn = new Button("Variation 1");
        variation1Btn.setOnAction(e -> {
            kScaleSlider.setValue(2.0);
            kOffsetSlider.setValue(2.5);
            kFoldXSlider.setValue(10);
            kFoldYSlider.setValue(5);
        });

        Button variation2Btn = new Button("Variation 2");
        variation2Btn.setOnAction(e -> {
            kScaleSlider.setValue(2.2);
            kOffsetSlider.setValue(3.5);
            kFoldXSlider.setValue(-15);
            kFoldYSlider.setValue(8);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(sierpinskiBtn, variation1Btn, variation2Btn);

        kaleidoscopicControls.getChildren().addAll(
            infoLabel,
            iterLabel, kIterSlider,
            scaleLabel, kScaleSlider,
            offsetLabel, kOffsetSlider,
            foldXLabel, kFoldXSlider,
            foldYLabel, kFoldYSlider,
            new Separator(),
            presetLabel, presetBox
        );
        kaleidoscopicControls.setVisible(false);
        kaleidoscopicControls.setManaged(false);
    }

    private void createJulia3dControls() {
        julia3dControls = new VBox(8);

        Label titleLabel = new Label("Julia 3D (Quaternion)");
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label infoLabel = new Label("q' = q² + c (quaternion iteration)");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Iterations
        Label iterLabel = new Label("Iterations: 12");
        j3dIterSlider = new Slider(4, 20, 12);
        j3dIterSlider.setShowTickLabels(true);
        j3dIterSlider.setMajorTickUnit(4);
        j3dIterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof Julia3DParams jParams) {
                jParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        // Julia constant C components
        Label cLabel = new Label("Julia Constant (c):");
        cLabel.setStyle("-fx-font-weight: bold;");

        Label cxLabel = new Label("cx: -0.20");
        j3dCxSlider = new Slider(-1.0, 1.0, -0.2);
        j3dCxSlider.valueProperty().addListener((obs, old, val) -> {
            cxLabel.setText(String.format("cx: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof Julia3DParams jParams) {
                jParams.setJuliaCx(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label cyLabel = new Label("cy: 0.80");
        j3dCySlider = new Slider(-1.0, 1.0, 0.8);
        j3dCySlider.valueProperty().addListener((obs, old, val) -> {
            cyLabel.setText(String.format("cy: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof Julia3DParams jParams) {
                jParams.setJuliaCy(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label czLabel = new Label("cz: 0.00");
        j3dCzSlider = new Slider(-1.0, 1.0, 0.0);
        j3dCzSlider.valueProperty().addListener((obs, old, val) -> {
            czLabel.setText(String.format("cz: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof Julia3DParams jParams) {
                jParams.setJuliaCz(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label cwLabel = new Label("cw: 0.00");
        j3dCwSlider = new Slider(-1.0, 1.0, 0.0);
        j3dCwSlider.valueProperty().addListener((obs, old, val) -> {
            cwLabel.setText(String.format("cw: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof Julia3DParams jParams) {
                jParams.setJuliaCw(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Preset buttons
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button classicBtn = new Button("Classic");
        classicBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.2);
            j3dCySlider.setValue(0.8);
            j3dCzSlider.setValue(0.0);
            j3dCwSlider.setValue(0.0);
        });

        Button organicBtn = new Button("Organic");
        organicBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.291);
            j3dCySlider.setValue(-0.399);
            j3dCzSlider.setValue(0.339);
            j3dCwSlider.setValue(0.437);
        });

        Button spikyBtn = new Button("Spiky");
        spikyBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.125);
            j3dCySlider.setValue(-0.256);
            j3dCzSlider.setValue(0.847);
            j3dCwSlider.setValue(0.0895);
        });

        Button spiralBtn = new Button("Spiral");
        spiralBtn.setOnAction(e -> {
            j3dCxSlider.setValue(-0.4);
            j3dCySlider.setValue(0.6);
            j3dCzSlider.setValue(0.2);
            j3dCwSlider.setValue(-0.1);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(classicBtn, organicBtn, spikyBtn, spiralBtn);

        julia3dControls.getChildren().addAll(
            titleLabel, infoLabel,
            iterLabel, j3dIterSlider,
            new Separator(),
            cLabel,
            cxLabel, j3dCxSlider,
            cyLabel, j3dCySlider,
            czLabel, j3dCzSlider,
            cwLabel, j3dCwSlider,
            new Separator(),
            presetLabel, presetBox
        );
        julia3dControls.setVisible(false);
        julia3dControls.setManaged(false);
    }

    private void createPseudoKleinianControls() {
        pseudoKleinianControls = new VBox(8);

        Label titleLabel = new Label("Pseudo Kleinian");
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label infoLabel = new Label("Box fold + Sphere fold + Julia");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Iterations
        Label iterLabel = new Label("Iterations: 8");
        pkIterSlider = new Slider(4, 15, 8);
        pkIterSlider.setShowTickLabels(true);
        pkIterSlider.setMajorTickUnit(4);
        pkIterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        // Size (sphere fold)
        Label sizeLabel = new Label("Size: 1.0");
        pkSizeSlider = new Slider(0.5, 2.0, 1.0);
        pkSizeSlider.valueProperty().addListener((obs, old, val) -> {
            sizeLabel.setText(String.format("Size: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setSize(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // CSize (box fold bounds)
        Label csizeLabel = new Label("Box Fold Size:");
        csizeLabel.setStyle("-fx-font-weight: bold;");

        Label csizeXLabel = new Label("CSize X: 0.90");
        pkCsizeXSlider = new Slider(0.5, 1.5, 0.90453);
        pkCsizeXSlider.valueProperty().addListener((obs, old, val) -> {
            csizeXLabel.setText(String.format("CSize X: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label csizeYLabel = new Label("CSize Y: 0.92");
        pkCsizeYSlider = new Slider(0.5, 1.5, 0.92);
        pkCsizeYSlider.valueProperty().addListener((obs, old, val) -> {
            csizeYLabel.setText(String.format("CSize Y: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeY(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label csizeZLabel = new Label("CSize Z: 0.90");
        pkCsizeZSlider = new Slider(0.5, 1.5, 0.90453);
        pkCsizeZSlider.valueProperty().addListener((obs, old, val) -> {
            csizeZLabel.setText(String.format("CSize Z: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeZ(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Julia constant
        Label juliaLabel = new Label("Julia Constant:");
        juliaLabel.setStyle("-fx-font-weight: bold;");

        Label jxLabel = new Label("Julia X: 0.0");
        pkJuliaXSlider = new Slider(-2.0, 2.0, 0.0);
        pkJuliaXSlider.valueProperty().addListener((obs, old, val) -> {
            jxLabel.setText(String.format("Julia X: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label jyLabel = new Label("Julia Y: 0.0");
        pkJuliaYSlider = new Slider(-2.0, 2.0, 0.0);
        pkJuliaYSlider.valueProperty().addListener((obs, old, val) -> {
            jyLabel.setText(String.format("Julia Y: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaY(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label jzLabel = new Label("Julia Z: 0.0");
        pkJuliaZSlider = new Slider(-2.0, 2.0, 0.0);
        pkJuliaZSlider.valueProperty().addListener((obs, old, val) -> {
            jzLabel.setText(String.format("Julia Z: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaZ(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // DE parameters
        Label deLabel = new Label("Distance Estimator:");
        deLabel.setStyle("-fx-font-weight: bold;");

        Label deOffsetLabel = new Label("DE Offset: 0.0");
        pkDeOffsetSlider = new Slider(-0.1, 0.1, 0.0);
        pkDeOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            deOffsetLabel.setText(String.format("DE Offset: %.3f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setDeOffset(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label zOffsetLabel = new Label("Z Offset: 1.0");
        pkZOffsetSlider = new Slider(0.0, 3.0, 1.0);
        pkZOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            zOffsetLabel.setText(String.format("Z Offset: %.2f", val.doubleValue()));
            if (!suppressRender && params instanceof PseudoKleinianParams pkParams) {
                pkParams.setZOffset(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Preset buttons
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button defaultBtn = new Button("Default");
        defaultBtn.setOnAction(e -> {
            pkIterSlider.setValue(8);
            pkSizeSlider.setValue(1.0);
            pkCsizeXSlider.setValue(0.90453);
            pkCsizeYSlider.setValue(0.92);
            pkCsizeZSlider.setValue(0.90453);
            pkJuliaXSlider.setValue(0.0);
            pkJuliaYSlider.setValue(0.0);
            pkJuliaZSlider.setValue(0.0);
            pkDeOffsetSlider.setValue(0.0);
            pkZOffsetSlider.setValue(1.0);
        });

        Button coralBtn = new Button("Coral");
        coralBtn.setOnAction(e -> {
            pkIterSlider.setValue(10);
            pkSizeSlider.setValue(1.0);
            pkCsizeXSlider.setValue(0.8);
            pkCsizeYSlider.setValue(0.8);
            pkCsizeZSlider.setValue(0.8);
            pkJuliaXSlider.setValue(0.5);
            pkJuliaYSlider.setValue(0.5);
            pkJuliaZSlider.setValue(0.0);
            pkDeOffsetSlider.setValue(0.0);
            pkZOffsetSlider.setValue(1.2);
        });

        Button crystalBtn = new Button("Crystal");
        crystalBtn.setOnAction(e -> {
            pkIterSlider.setValue(12);
            pkSizeSlider.setValue(1.2);
            pkCsizeXSlider.setValue(1.0);
            pkCsizeYSlider.setValue(1.0);
            pkCsizeZSlider.setValue(1.0);
            pkJuliaXSlider.setValue(-0.5);
            pkJuliaYSlider.setValue(0.3);
            pkJuliaZSlider.setValue(0.2);
            pkDeOffsetSlider.setValue(0.01);
            pkZOffsetSlider.setValue(0.8);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(defaultBtn, coralBtn, crystalBtn);

        pseudoKleinianControls.getChildren().addAll(
            titleLabel, infoLabel,
            iterLabel, pkIterSlider,
            sizeLabel, pkSizeSlider,
            new Separator(),
            csizeLabel,
            csizeXLabel, pkCsizeXSlider,
            csizeYLabel, pkCsizeYSlider,
            csizeZLabel, pkCsizeZSlider,
            new Separator(),
            juliaLabel,
            jxLabel, pkJuliaXSlider,
            jyLabel, pkJuliaYSlider,
            jzLabel, pkJuliaZSlider,
            new Separator(),
            deLabel,
            deOffsetLabel, pkDeOffsetSlider,
            zOffsetLabel, pkZOffsetSlider,
            new Separator(),
            presetLabel, presetBox
        );
        pseudoKleinianControls.setVisible(false);
        pseudoKleinianControls.setManaged(false);
    }

    // Public methods for external access
    public void updatePositionLabel() {
        float[] pos = camera.getPosition();
        positionLabel.setText(String.format("Pos: (%.2f, %.2f, %.2f)", pos[0], pos[1], pos[2]));
    }

    public Camera getCamera() {
        return camera;
    }

    public AbstractFractalParams getParams() {
        return params;
    }

    public void setSpeedSliderValue(double value) {
        speedSlider.setValue(value);
    }

    /**
     * Update params reference (for load configuration).
     */
    public void setParams(AbstractFractalParams newParams) {
        this.params = newParams;
        this.camera = newParams.getCamera();
    }

    /**
     * Refresh all UI controls from current params.
     * Called after loading a configuration.
     */
    @Override
    public void refreshFromParams(boolean suppress) {
        suppressRender = suppress;
        try {
            // Update fractal type combo and show correct controls
            FractalType type = params.getType();
            typeCombo.setValue(type);

            // Hide all, then show relevant
            mandelbulbControls.setVisible(false);
            mandelbulbControls.setManaged(false);
            mandelboxControls.setVisible(false);
            mandelboxControls.setManaged(false);
            mengerControls.setVisible(false);
            mengerControls.setManaged(false);
            kaleidoscopicControls.setVisible(false);
            kaleidoscopicControls.setManaged(false);
            julia3dControls.setVisible(false);
            julia3dControls.setManaged(false);
            pseudoKleinianControls.setVisible(false);
            pseudoKleinianControls.setManaged(false);

            // Update fractal-specific controls based on type
            if (params instanceof MandelbulbParams mb) {
                mandelbulbControls.setVisible(true);
                mandelbulbControls.setManaged(true);
                mbPowerSlider.setValue(mb.getPower());
                mbIterSlider.setValue(mb.getMaxIterations());
                mbBailoutSlider.setValue(mb.getBailout());
            } else if (params instanceof MandelboxParams mbx) {
                mandelboxControls.setVisible(true);
                mandelboxControls.setManaged(true);
                mbxScaleSlider.setValue(mbx.getScale());
                mbxMinRadiusSlider.setValue(mbx.getMinRadius());
                mbxFixedRadiusSlider.setValue(mbx.getFixedRadius());
                mbxFoldingLimitSlider.setValue(mbx.getFoldingLimit());
                mbxIterSlider.setValue(mbx.getMaxIterations());
            } else if (params instanceof MengerSpongeParams ms) {
                mengerControls.setVisible(true);
                mengerControls.setManaged(true);
                mengerIterSlider.setValue(ms.getMaxIterations());
                mengerScaleSlider.setValue(ms.getScale());
            } else if (params instanceof KaleidoscopicIFSParams k) {
                kaleidoscopicControls.setVisible(true);
                kaleidoscopicControls.setManaged(true);
                kIterSlider.setValue(k.getMaxIterations());
                kScaleSlider.setValue(k.getScale());
                kOffsetSlider.setValue(k.getOffsetX());
                kFoldXSlider.setValue(k.getFoldAngleX());
                kFoldYSlider.setValue(k.getFoldAngleY());
            } else if (params instanceof Julia3DParams j) {
                julia3dControls.setVisible(true);
                julia3dControls.setManaged(true);
                j3dIterSlider.setValue(j.getMaxIterations());
                j3dCxSlider.setValue(j.getJuliaCx());
                j3dCySlider.setValue(j.getJuliaCy());
                j3dCzSlider.setValue(j.getJuliaCz());
                j3dCwSlider.setValue(j.getJuliaCw());
            } else if (params instanceof PseudoKleinianParams pk) {
                pseudoKleinianControls.setVisible(true);
                pseudoKleinianControls.setManaged(true);
                pkIterSlider.setValue(pk.getMaxIterations());
                pkSizeSlider.setValue(pk.getSize());
                pkCsizeXSlider.setValue(pk.getCSizeX());
                pkCsizeYSlider.setValue(pk.getCSizeY());
                pkCsizeZSlider.setValue(pk.getCSizeZ());
                pkJuliaXSlider.setValue(pk.getJuliaX());
                pkJuliaYSlider.setValue(pk.getJuliaY());
                pkJuliaZSlider.setValue(pk.getJuliaZ());
                pkDeOffsetSlider.setValue(pk.getDeOffset());
                pkZOffsetSlider.setValue(pk.getZOffset());
            }

            // Update common controls
            speedSlider.setValue(camera.getMoveSpeed());
            updatePositionLabel();

        } finally {
            suppressRender = false;
        }
    }
}
