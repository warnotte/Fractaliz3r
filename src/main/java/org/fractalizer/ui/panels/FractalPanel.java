package org.fractalizer.ui.panels;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.RenderController;

/**
 * Panel for fractal type selection and fractal-specific parameters.
 */
public class FractalPanel extends ScrollPane {

    private final RenderController controller;
    private final RenderCallback renderCallback;

    // Current state
    private AbstractFractalParams params;
    private Camera camera;

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
        ComboBox<FractalType> typeCombo = createTypeComboBox();

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
        ComboBox<FractalType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(FractalType.MANDELBULB, FractalType.MANDELBOX,
                                    FractalType.MENGER_SPONGE, FractalType.KALEIDOSCOPIC_IFS,
                                    FractalType.JULIA_3D, FractalType.PSEUDO_KLEINIAN);
        typeCombo.setValue(FractalType.MANDELBULB);
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        // Display friendly names
        typeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });
        typeCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FractalType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });

        // Fractal type change handler
        typeCombo.setOnAction(e -> {
            FractalType selectedType = typeCombo.getValue();
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

        return typeCombo;
    }

    private void createMandelbulbControls() {
        mandelbulbControls = new VBox(8);

        Label powerLabel = new Label("Power: 8.0");
        Slider powerSlider = new Slider(2, 16, 8);
        powerSlider.setShowTickLabels(true);
        powerSlider.setShowTickMarks(true);
        powerSlider.valueProperty().addListener((obs, old, val) -> {
            powerLabel.setText(String.format("Power: %.1f", val.doubleValue()));
            if (params instanceof MandelbulbParams mbParams) {
                mbParams.power(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label iterLabel = new Label("Iterations: 15");
        Slider iterSlider = new Slider(5, 30, 15);
        iterSlider.setShowTickLabels(true);
        iterSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MandelbulbParams mbParams) {
                mbParams.iterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        Label bailoutLabel = new Label("Bailout: "+((MandelbulbParams)params).getBailout());
        Slider bailoutSlider = new Slider(1, 16, ((MandelbulbParams)params).getBailout());
        bailoutSlider.setShowTickLabels(true);
        bailoutSlider.valueProperty().addListener((obs, old, val) -> {
            bailoutLabel.setText(String.format("Bailout: %.2f", val.floatValue()));
            if (params instanceof MandelbulbParams mbParams) {
                mbParams.setBailout(val.floatValue());
                renderCallback.requestRender();
            }
        });

        mandelbulbControls.getChildren().addAll(
            powerLabel, powerSlider,
            iterLabel, iterSlider,
            bailoutLabel, bailoutSlider
        );
    }

    private void createMandelboxControls() {
        mandelboxControls = new VBox(8);

        Label scaleLabel = new Label("Scale: 2.0");
        Slider scaleSlider = new Slider(-3, 3, 2);
        scaleSlider.setShowTickLabels(true);
        scaleSlider.valueProperty().addListener((obs, old, val) -> {
            scaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label minRadiusLabel = new Label("Min Radius: 0.25");
        Slider minRadiusSlider = new Slider(0.01, 1.0, 0.25);
        minRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            minRadiusLabel.setText(String.format("Min Radius: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setMinRadius(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label fixedRadiusLabel = new Label("Fixed Radius: 1.0");
        Slider fixedRadiusSlider = new Slider(0.5, 2.0, 1.0);
        fixedRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            fixedRadiusLabel.setText(String.format("Fixed Radius: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setFixedRadius(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label foldingLimitLabel = new Label("Folding Limit: 1.0");
        Slider foldingLimitSlider = new Slider(0.5, 2.0, 1.0);
        foldingLimitSlider.valueProperty().addListener((obs, old, val) -> {
            foldingLimitLabel.setText(String.format("Folding Limit: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setFoldingLimit(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label mbxIterLabel = new Label("Iterations: 15");
        Slider mbxIterSlider = new Slider(5, 30, 15);
        mbxIterSlider.setShowTickLabels(true);
        mbxIterSlider.valueProperty().addListener((obs, old, val) -> {
            mbxIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        mandelboxControls.getChildren().addAll(
            scaleLabel, scaleSlider,
            minRadiusLabel, minRadiusSlider,
            fixedRadiusLabel, fixedRadiusSlider,
            foldingLimitLabel, foldingLimitSlider,
            mbxIterLabel, mbxIterSlider
        );
        mandelboxControls.setVisible(false);
        mandelboxControls.setManaged(false);
    }

    private void createMengerControls() {
        mengerControls = new VBox(8);

        Label mengerIterLabel = new Label("Iterations: 6");
        Slider mengerIterSlider = new Slider(2, 10, 6);
        mengerIterSlider.setShowTickLabels(true);
        mengerIterSlider.valueProperty().addListener((obs, old, val) -> {
            mengerIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MengerSpongeParams msParams) {
                msParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        Label mengerScaleLabel = new Label("Scale: 3.0");
        Slider mengerScaleSlider = new Slider(2.0, 4.0, 3.0);
        mengerScaleSlider.valueProperty().addListener((obs, old, val) -> {
            mengerScaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof MengerSpongeParams msParams) {
                msParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        mengerControls.getChildren().addAll(
            mengerIterLabel, mengerIterSlider,
            mengerScaleLabel, mengerScaleSlider
        );
        mengerControls.setVisible(false);
        mengerControls.setManaged(false);
    }

    private void createKaleidoscopicControls() {
        kaleidoscopicControls = new VBox(8);

        // Info label about parameter relationships
        Label infoLabel = new Label("Classic Sierpinski: Scale=2, Offset=3");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        Label kIterLabel = new Label("Iterations: 15");
        Slider kIterSlider = new Slider(4, 25, 15);
        kIterSlider.setShowTickLabels(true);
        kIterSlider.valueProperty().addListener((obs, old, val) -> {
            kIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setMaxIterations(val.intValue());
                renderCallback.requestRender();
            }
        });

        // Scale: typically 1.5 to 3.0, default 2.0 for Sierpinski
        Label kScaleLabel = new Label("Scale: 2.0");
        Slider kScaleSlider = new Slider(1.5, 3.0, 2.0);
        kScaleSlider.setShowTickLabels(true);
        kScaleSlider.setMajorTickUnit(0.5);
        kScaleSlider.valueProperty().addListener((obs, old, val) -> {
            kScaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setScale(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Offset: critical parameter! For scale=2, offset should be around 3 (scale+1)
        // Valid range typically: scale to scale*2
        Label kOffsetLabel = new Label("Offset: 3.0");
        Slider kOffsetSlider = new Slider(1.0, 5.0, 3.0);
        kOffsetSlider.setShowTickLabels(true);
        kOffsetSlider.setMajorTickUnit(1.0);
        kOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            kOffsetLabel.setText(String.format("Offset: %.2f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                // Offset is stored in offsetX (scalar for KIFS)
                kParams.setOffset(val.floatValue(), 0, 0);
                renderCallback.requestRender();
            }
        });

        // Rotation angles: 0 = pure Sierpinski, small values create variations
        Label kFoldXLabel = new Label("Rotation X: 0");
        Slider kFoldXSlider = new Slider(-30, 30, 0);
        kFoldXSlider.setShowTickLabels(true);
        kFoldXSlider.setMajorTickUnit(15);
        kFoldXSlider.valueProperty().addListener((obs, old, val) -> {
            kFoldXLabel.setText(String.format("Rotation X: %.0f°", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setFoldAngleX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label kFoldYLabel = new Label("Rotation Y: 0");
        Slider kFoldYSlider = new Slider(-30, 30, 0);
        kFoldYSlider.setShowTickLabels(true);
        kFoldYSlider.setMajorTickUnit(15);
        kFoldYSlider.valueProperty().addListener((obs, old, val) -> {
            kFoldYLabel.setText(String.format("Rotation Y: %.0f°", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
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
            kIterLabel, kIterSlider,
            kScaleLabel, kScaleSlider,
            kOffsetLabel, kOffsetSlider,
            kFoldXLabel, kFoldXSlider,
            kFoldYLabel, kFoldYSlider,
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
        Slider iterSlider = new Slider(4, 20, 12);
        iterSlider.setShowTickLabels(true);
        iterSlider.setMajorTickUnit(4);
        iterSlider.valueProperty().addListener((obs, old, val) -> {
            int iter = val.intValue();
            iterLabel.setText(String.format("Iterations: %d", iter));
            if (params instanceof Julia3DParams jParams) {
                jParams.setMaxIterations(iter);
                renderCallback.requestRender();
            }
        });

        // Julia constant C components
        Label cLabel = new Label("Julia Constant (c):");
        cLabel.setStyle("-fx-font-weight: bold;");

        Label cxLabel = new Label("cx: -0.20");
        Slider cxSlider = new Slider(-1.0, 1.0, -0.2);
        cxSlider.valueProperty().addListener((obs, old, val) -> {
            cxLabel.setText(String.format("cx: %.2f", val.doubleValue()));
            if (params instanceof Julia3DParams jParams) {
                jParams.setJuliaCx(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label cyLabel = new Label("cy: 0.80");
        Slider cySlider = new Slider(-1.0, 1.0, 0.8);
        cySlider.valueProperty().addListener((obs, old, val) -> {
            cyLabel.setText(String.format("cy: %.2f", val.doubleValue()));
            if (params instanceof Julia3DParams jParams) {
                jParams.setJuliaCy(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label czLabel = new Label("cz: 0.00");
        Slider czSlider = new Slider(-1.0, 1.0, 0.0);
        czSlider.valueProperty().addListener((obs, old, val) -> {
            czLabel.setText(String.format("cz: %.2f", val.doubleValue()));
            if (params instanceof Julia3DParams jParams) {
                jParams.setJuliaCz(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label cwLabel = new Label("cw: 0.00");
        Slider cwSlider = new Slider(-1.0, 1.0, 0.0);
        cwSlider.valueProperty().addListener((obs, old, val) -> {
            cwLabel.setText(String.format("cw: %.2f", val.doubleValue()));
            if (params instanceof Julia3DParams jParams) {
                jParams.setJuliaCw(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Preset buttons
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button classicBtn = new Button("Classic");
        classicBtn.setOnAction(e -> {
            cxSlider.setValue(-0.2);
            cySlider.setValue(0.8);
            czSlider.setValue(0.0);
            cwSlider.setValue(0.0);
        });

        Button organicBtn = new Button("Organic");
        organicBtn.setOnAction(e -> {
            cxSlider.setValue(-0.291);
            cySlider.setValue(-0.399);
            czSlider.setValue(0.339);
            cwSlider.setValue(0.437);
        });

        Button spikyBtn = new Button("Spiky");
        spikyBtn.setOnAction(e -> {
            cxSlider.setValue(-0.125);
            cySlider.setValue(-0.256);
            czSlider.setValue(0.847);
            cwSlider.setValue(0.0895);
        });

        Button spiralBtn = new Button("Spiral");
        spiralBtn.setOnAction(e -> {
            cxSlider.setValue(-0.4);
            cySlider.setValue(0.6);
            czSlider.setValue(0.2);
            cwSlider.setValue(-0.1);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(classicBtn, organicBtn, spikyBtn, spiralBtn);

        julia3dControls.getChildren().addAll(
            titleLabel, infoLabel,
            iterLabel, iterSlider,
            new Separator(),
            cLabel,
            cxLabel, cxSlider,
            cyLabel, cySlider,
            czLabel, czSlider,
            cwLabel, cwSlider,
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
        Slider iterSlider = new Slider(4, 15, 8);
        iterSlider.setShowTickLabels(true);
        iterSlider.setMajorTickUnit(4);
        iterSlider.valueProperty().addListener((obs, old, val) -> {
            int iter = val.intValue();
            iterLabel.setText(String.format("Iterations: %d", iter));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setMaxIterations(iter);
                renderCallback.requestRender();
            }
        });

        // Size (sphere fold)
        Label sizeLabel = new Label("Size: 1.0");
        Slider sizeSlider = new Slider(0.5, 2.0, 1.0);
        sizeSlider.valueProperty().addListener((obs, old, val) -> {
            sizeLabel.setText(String.format("Size: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setSize(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // CSize (box fold bounds)
        Label csizeLabel = new Label("Box Fold Size:");
        csizeLabel.setStyle("-fx-font-weight: bold;");

        Label csizeXLabel = new Label("CSize X: 0.90");
        Slider csizeXSlider = new Slider(0.5, 1.5, 0.90453);
        csizeXSlider.valueProperty().addListener((obs, old, val) -> {
            csizeXLabel.setText(String.format("CSize X: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label csizeYLabel = new Label("CSize Y: 0.92");
        Slider csizeYSlider = new Slider(0.5, 1.5, 0.92);
        csizeYSlider.valueProperty().addListener((obs, old, val) -> {
            csizeYLabel.setText(String.format("CSize Y: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeY(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label csizeZLabel = new Label("CSize Z: 0.90");
        Slider csizeZSlider = new Slider(0.5, 1.5, 0.90453);
        csizeZSlider.valueProperty().addListener((obs, old, val) -> {
            csizeZLabel.setText(String.format("CSize Z: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setCSizeZ(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Julia constant
        Label juliaLabel = new Label("Julia Constant:");
        juliaLabel.setStyle("-fx-font-weight: bold;");

        Label jxLabel = new Label("Julia X: 0.0");
        Slider jxSlider = new Slider(-2.0, 2.0, 0.0);
        jxSlider.valueProperty().addListener((obs, old, val) -> {
            jxLabel.setText(String.format("Julia X: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaX(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label jyLabel = new Label("Julia Y: 0.0");
        Slider jySlider = new Slider(-2.0, 2.0, 0.0);
        jySlider.valueProperty().addListener((obs, old, val) -> {
            jyLabel.setText(String.format("Julia Y: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaY(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label jzLabel = new Label("Julia Z: 0.0");
        Slider jzSlider = new Slider(-2.0, 2.0, 0.0);
        jzSlider.valueProperty().addListener((obs, old, val) -> {
            jzLabel.setText(String.format("Julia Z: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setJuliaZ(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // DE parameters
        Label deLabel = new Label("Distance Estimator:");
        deLabel.setStyle("-fx-font-weight: bold;");

        Label deOffsetLabel = new Label("DE Offset: 0.0");
        Slider deOffsetSlider = new Slider(-0.1, 0.1, 0.0);
        deOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            deOffsetLabel.setText(String.format("DE Offset: %.3f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setDeOffset(val.floatValue());
                renderCallback.requestRender();
            }
        });

        Label zOffsetLabel = new Label("Z Offset: 1.0");
        Slider zOffsetSlider = new Slider(0.0, 3.0, 1.0);
        zOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            zOffsetLabel.setText(String.format("Z Offset: %.2f", val.doubleValue()));
            if (params instanceof PseudoKleinianParams pkParams) {
                pkParams.setZOffset(val.floatValue());
                renderCallback.requestRender();
            }
        });

        // Preset buttons
        Label presetLabel = new Label("Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        Button defaultBtn = new Button("Default");
        defaultBtn.setOnAction(e -> {
            iterSlider.setValue(8);
            sizeSlider.setValue(1.0);
            csizeXSlider.setValue(0.90453);
            csizeYSlider.setValue(0.92);
            csizeZSlider.setValue(0.90453);
            jxSlider.setValue(0.0);
            jySlider.setValue(0.0);
            jzSlider.setValue(0.0);
            deOffsetSlider.setValue(0.0);
            zOffsetSlider.setValue(1.0);
        });

        Button coralBtn = new Button("Coral");
        coralBtn.setOnAction(e -> {
            iterSlider.setValue(10);
            sizeSlider.setValue(1.0);
            csizeXSlider.setValue(0.8);
            csizeYSlider.setValue(0.8);
            csizeZSlider.setValue(0.8);
            jxSlider.setValue(0.5);
            jySlider.setValue(0.5);
            jzSlider.setValue(0.0);
            deOffsetSlider.setValue(0.0);
            zOffsetSlider.setValue(1.2);
        });

        Button crystalBtn = new Button("Crystal");
        crystalBtn.setOnAction(e -> {
            iterSlider.setValue(12);
            sizeSlider.setValue(1.2);
            csizeXSlider.setValue(1.0);
            csizeYSlider.setValue(1.0);
            csizeZSlider.setValue(1.0);
            jxSlider.setValue(-0.5);
            jySlider.setValue(0.3);
            jzSlider.setValue(0.2);
            deOffsetSlider.setValue(0.01);
            zOffsetSlider.setValue(0.8);
        });

        javafx.scene.layout.HBox presetBox = new javafx.scene.layout.HBox(5);
        presetBox.getChildren().addAll(defaultBtn, coralBtn, crystalBtn);

        pseudoKleinianControls.getChildren().addAll(
            titleLabel, infoLabel,
            iterLabel, iterSlider,
            sizeLabel, sizeSlider,
            new Separator(),
            csizeLabel,
            csizeXLabel, csizeXSlider,
            csizeYLabel, csizeYSlider,
            csizeZLabel, csizeZSlider,
            new Separator(),
            juliaLabel,
            jxLabel, jxSlider,
            jyLabel, jySlider,
            jzLabel, jzSlider,
            new Separator(),
            deLabel,
            deOffsetLabel, deOffsetSlider,
            zOffsetLabel, zOffsetSlider,
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
}
