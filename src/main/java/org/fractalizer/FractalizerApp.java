package org.fractalizer;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.FractalizerController;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Main JavaFX application for Fractaliz3r.
 * Supports FPS-style navigation with arrow keys and click+drag mouse.
 */
public class FractalizerApp extends Application {

    private FractalizerController controller;
    private ImageView imageView;
    private ProgressBar progressBar;
    private Label statusLabel;
    private StackPane imageContainer;

    // Parameter controls
    private Slider powerSlider;
    private Slider iterationsSlider;
    private Slider speedSlider;
    private TextField widthField, heightField;
    private Label positionLabel;

    // Lighting controls
    private Slider lightXSlider, lightYSlider, lightZSlider;
    private ColorPicker lightColorPicker;
    private Slider lightIntensitySlider;
    private ColorPicker ambientColorPicker;
    private Slider ambientIntensitySlider;
    private Slider hueOffsetSlider;

    // Quality controls
    private Slider shadowSoftnessSlider;
    private Slider aoIntensitySlider;
    private Slider specularIntensitySlider;
    private Slider specularPowerSlider;
    private Slider glowIntensitySlider;

    // Navigation state
    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private Camera camera;
    private AbstractFractalParams params;

    // Fractal-specific control containers
    private VBox mandelbulbControls;
    private VBox mandelboxControls;
    private VBox mengerControls;
    private VBox kaleidoscopicControls;

    // Mouse drag state
    private boolean isDragging = false;
    private double dragStartX, dragStartY;

    // Rendering state
    private boolean needsRender = true;
    private long lastRenderTime = 0;
    private static final long RENDER_DELAY_MS = 100;
    private boolean autoFullQuality = false;

    @Override
    public void start(Stage primaryStage) {
        try {
            controller = new FractalizerController();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to initialize OpenCL", e.getMessage());
            Platform.exit();
            return;
        }

        // Initialize camera and params
        params = new MandelbulbParams();
        camera = params.getCamera();
        controller.setParams(params);

        primaryStage.setTitle("Fractaliz3r - 3D Fractal Renderer");

        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Center: Image view
        imageView = new ImageView();
        imageView.setFitWidth(800);
        imageView.setFitHeight(600);
        imageView.setPreserveRatio(true);

        imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #1a1a2e;");
        imageContainer.setFocusTraversable(true);
        root.setCenter(imageContainer);

        // Right: Controls panel with tabs
        TabPane controlTabs = createControlTabs();
        controlTabs.setPrefWidth(320);
        root.setRight(controlTabs);

        // Bottom: Status bar
        HBox statusBar = createStatusBar();
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1280, 750);

        // Setup controls
        setupKeyboardControls(scene);
        setupMouseControls();

        primaryStage.setScene(scene);
        primaryStage.show();

        // Focus the image container for keyboard input
        imageContainer.requestFocus();

        // Initial status
        statusLabel.setText("GPU: " + controller.getDeviceName());

        // Start render loop
        startRenderLoop();

        // Initial render
        renderPreview();
    }

    private TabPane createControlTabs() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Fractal tab
        Tab fractalTab = new Tab("Fractal");
        fractalTab.setContent(createFractalPanel());

        // Lighting tab
        Tab lightingTab = new Tab("Lighting");
        lightingTab.setContent(createLightingPanel());

        // Quality tab
        Tab qualityTab = new Tab("Quality");
        qualityTab.setContent(createQualityPanel());

        // Export tab
        Tab exportTab = new Tab("Export");
        exportTab.setContent(createExportPanel());

        tabPane.getTabs().addAll(fractalTab, lightingTab, qualityTab, exportTab);

        return tabPane;
    }

    private ScrollPane createFractalPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Fractal type selector
        Label typeLabel = new Label("Fractal Type:");
        ComboBox<FractalType> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(FractalType.MANDELBULB, FractalType.MANDELBOX,
                                    FractalType.MENGER_SPONGE, FractalType.KALEIDOSCOPIC_IFS);
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

        // === Mandelbulb-specific controls ===
        mandelbulbControls = new VBox(8);

        Label powerLabel = new Label("Power: 8.0");
        powerSlider = new Slider(2, 16, 8);
        powerSlider.setShowTickLabels(true);
        powerSlider.setShowTickMarks(true);
        powerSlider.valueProperty().addListener((obs, old, val) -> {
            powerLabel.setText(String.format("Power: %.1f", val.doubleValue()));
            if (params instanceof MandelbulbParams mbParams) {
                mbParams.power(val.floatValue());
                needsRender = true;
            }
        });

        Label iterLabel = new Label("Iterations: 15");
        iterationsSlider = new Slider(5, 30, 15);
        iterationsSlider.setShowTickLabels(true);
        iterationsSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MandelbulbParams mbParams) {
                mbParams.iterations(val.intValue());
                needsRender = true;
            }
        });

        mandelbulbControls.getChildren().addAll(
            powerLabel, powerSlider,
            iterLabel, iterationsSlider
        );

        // === Mandelbox-specific controls ===
        mandelboxControls = new VBox(8);

        Label scaleLabel = new Label("Scale: 2.0");
        Slider scaleSlider = new Slider(-3, 3, 2);
        scaleSlider.setShowTickLabels(true);
        scaleSlider.valueProperty().addListener((obs, old, val) -> {
            scaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setScale(val.floatValue());
                needsRender = true;
            }
        });

        Label minRadiusLabel = new Label("Min Radius: 0.25");
        Slider minRadiusSlider = new Slider(0.01, 1.0, 0.25);
        minRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            minRadiusLabel.setText(String.format("Min Radius: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setMinRadius(val.floatValue());
                needsRender = true;
            }
        });

        Label fixedRadiusLabel = new Label("Fixed Radius: 1.0");
        Slider fixedRadiusSlider = new Slider(0.5, 2.0, 1.0);
        fixedRadiusSlider.valueProperty().addListener((obs, old, val) -> {
            fixedRadiusLabel.setText(String.format("Fixed Radius: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setFixedRadius(val.floatValue());
                needsRender = true;
            }
        });

        Label foldingLimitLabel = new Label("Folding Limit: 1.0");
        Slider foldingLimitSlider = new Slider(0.5, 2.0, 1.0);
        foldingLimitSlider.valueProperty().addListener((obs, old, val) -> {
            foldingLimitLabel.setText(String.format("Folding Limit: %.2f", val.doubleValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setFoldingLimit(val.floatValue());
                needsRender = true;
            }
        });

        Label mbxIterLabel = new Label("Iterations: 15");
        Slider mbxIterSlider = new Slider(5, 30, 15);
        mbxIterSlider.setShowTickLabels(true);
        mbxIterSlider.valueProperty().addListener((obs, old, val) -> {
            mbxIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MandelboxParams mbxParams) {
                mbxParams.setMaxIterations(val.intValue());
                needsRender = true;
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

        // === Menger Sponge-specific controls ===
        mengerControls = new VBox(8);

        Label mengerIterLabel = new Label("Iterations: 6");
        Slider mengerIterSlider = new Slider(2, 10, 6);
        mengerIterSlider.setShowTickLabels(true);
        mengerIterSlider.valueProperty().addListener((obs, old, val) -> {
            mengerIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof MengerSpongeParams msParams) {
                msParams.setMaxIterations(val.intValue());
                needsRender = true;
            }
        });

        Label mengerScaleLabel = new Label("Scale: 3.0");
        Slider mengerScaleSlider = new Slider(2.0, 4.0, 3.0);
        mengerScaleSlider.valueProperty().addListener((obs, old, val) -> {
            mengerScaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof MengerSpongeParams msParams) {
                msParams.setScale(val.floatValue());
                needsRender = true;
            }
        });

        mengerControls.getChildren().addAll(
            mengerIterLabel, mengerIterSlider,
            mengerScaleLabel, mengerScaleSlider
        );
        mengerControls.setVisible(false);
        mengerControls.setManaged(false);

        // === Kaleidoscopic IFS-specific controls ===
        kaleidoscopicControls = new VBox(8);

        Label kIterLabel = new Label("Iterations: 12");
        Slider kIterSlider = new Slider(4, 20, 12);
        kIterSlider.setShowTickLabels(true);
        kIterSlider.valueProperty().addListener((obs, old, val) -> {
            kIterLabel.setText(String.format("Iterations: %d", val.intValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setMaxIterations(val.intValue());
                needsRender = true;
            }
        });

        Label kScaleLabel = new Label("Scale: 2.0");
        Slider kScaleSlider = new Slider(1.5, 3.0, 2.0);
        kScaleSlider.valueProperty().addListener((obs, old, val) -> {
            kScaleLabel.setText(String.format("Scale: %.2f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setScale(val.floatValue());
                needsRender = true;
            }
        });

        Label kFoldXLabel = new Label("Fold Angle X: 77");
        Slider kFoldXSlider = new Slider(30, 120, 77);
        kFoldXSlider.valueProperty().addListener((obs, old, val) -> {
            kFoldXLabel.setText(String.format("Fold Angle X: %.0f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setFoldAngleX(val.floatValue());
                needsRender = true;
            }
        });

        Label kFoldYLabel = new Label("Fold Angle Y: 77");
        Slider kFoldYSlider = new Slider(30, 120, 77);
        kFoldYSlider.valueProperty().addListener((obs, old, val) -> {
            kFoldYLabel.setText(String.format("Fold Angle Y: %.0f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setFoldAngleY(val.floatValue());
                needsRender = true;
            }
        });

        Label kMinRadLabel = new Label("Min Radius: 0.5");
        Slider kMinRadSlider = new Slider(0, 1.0, 0.5);
        kMinRadSlider.valueProperty().addListener((obs, old, val) -> {
            kMinRadLabel.setText(String.format("Min Radius: %.2f", val.doubleValue()));
            if (params instanceof KaleidoscopicIFSParams kParams) {
                kParams.setMinRadius(val.floatValue());
                needsRender = true;
            }
        });

        kaleidoscopicControls.getChildren().addAll(
            kIterLabel, kIterSlider,
            kScaleLabel, kScaleSlider,
            kFoldXLabel, kFoldXSlider,
            kFoldYLabel, kFoldYSlider,
            kMinRadLabel, kMinRadSlider
        );
        kaleidoscopicControls.setVisible(false);
        kaleidoscopicControls.setManaged(false);

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

            // Show only the relevant controls
            switch (selectedType) {
                case MANDELBULB:
                    mandelbulbControls.setVisible(true);
                    mandelbulbControls.setManaged(true);
                    break;
                case MANDELBOX:
                    mandelboxControls.setVisible(true);
                    mandelboxControls.setManaged(true);
                    break;
                case MENGER_SPONGE:
                    mengerControls.setVisible(true);
                    mengerControls.setManaged(true);
                    break;
                case KALEIDOSCOPIC_IFS:
                    kaleidoscopicControls.setVisible(true);
                    kaleidoscopicControls.setManaged(true);
                    break;
            }

            needsRender = true;
        });

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
            needsRender = true;
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
            speedLabel, speedSlider,
            new Separator(),
            positionLabel,
            new Separator(),
            navLabel, helpLabel,
            new Separator(),
            resetBtn
        );

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private ScrollPane createLightingPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // === Light Direction ===
        Label dirLabel = new Label("Light Direction:");
        dirLabel.setStyle("-fx-font-weight: bold;");

        Label lightXLabel = new Label("X: 2.0");
        lightXSlider = new Slider(-5, 5, 2);
        lightXSlider.valueProperty().addListener((obs, old, val) -> {
            lightXLabel.setText(String.format("X: %.1f", val.doubleValue()));
            params.lightDirection(val.floatValue(), (float) lightYSlider.getValue(), (float) lightZSlider.getValue());
            needsRender = true;
        });

        Label lightYLabel = new Label("Y: 3.0");
        lightYSlider = new Slider(-5, 5, 3);
        lightYSlider.valueProperty().addListener((obs, old, val) -> {
            lightYLabel.setText(String.format("Y: %.1f", val.doubleValue()));
            params.lightDirection((float) lightXSlider.getValue(), val.floatValue(), (float) lightZSlider.getValue());
            needsRender = true;
        });

        Label lightZLabel = new Label("Z: -2.0");
        lightZSlider = new Slider(-5, 5, -2);
        lightZSlider.valueProperty().addListener((obs, old, val) -> {
            lightZLabel.setText(String.format("Z: %.1f", val.doubleValue()));
            params.lightDirection((float) lightXSlider.getValue(), (float) lightYSlider.getValue(), val.floatValue());
            needsRender = true;
        });

        // === Light Color ===
        Label lightColLabel = new Label("Light Color:");
        lightColLabel.setStyle("-fx-font-weight: bold;");

        lightColorPicker = new ColorPicker(Color.rgb(255, 242, 230));
        lightColorPicker.setMaxWidth(Double.MAX_VALUE);
        lightColorPicker.setOnAction(e -> {
            Color c = lightColorPicker.getValue();
            params.lightColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
            needsRender = true;
        });

        Label lightIntLabel = new Label("Light Intensity: 1.2");
        lightIntensitySlider = new Slider(0, 3, 1.2);
        lightIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            lightIntLabel.setText(String.format("Light Intensity: %.1f", val.doubleValue()));
            params.lightIntensity(val.floatValue());
            needsRender = true;
        });

        // === Ambient ===
        Label ambientLabel = new Label("Ambient Color:");
        ambientLabel.setStyle("-fx-font-weight: bold;");

        ambientColorPicker = new ColorPicker(Color.rgb(26, 38, 64));
        ambientColorPicker.setMaxWidth(Double.MAX_VALUE);
        ambientColorPicker.setOnAction(e -> {
            Color c = ambientColorPicker.getValue();
            params.ambientColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
            needsRender = true;
        });

        Label ambientIntLabel = new Label("Ambient Intensity: 0.3");
        ambientIntensitySlider = new Slider(0, 1, 0.3);
        ambientIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            ambientIntLabel.setText(String.format("Ambient Intensity: %.2f", val.doubleValue()));
            params.ambientIntensity(val.floatValue());
            needsRender = true;
        });

        // === Material Hue ===
        Label hueLabel = new Label("Material Hue Offset:");
        hueLabel.setStyle("-fx-font-weight: bold;");

        Label hueOffsetLabel = new Label("Hue: 0.33");
        hueOffsetSlider = new Slider(0, 1, 0.33);
        hueOffsetSlider.valueProperty().addListener((obs, old, val) -> {
            hueOffsetLabel.setText(String.format("Hue: %.2f", val.doubleValue()));
            // Cycle through different hue combinations
            float h = val.floatValue();
            params.materialHue(h * 0.3f, h, (1.0f - h) * 0.67f + 0.33f);
            needsRender = true;
        });

        // Preset colors
        Label presetLabel = new Label("Color Presets:");
        HBox presetBox = new HBox(5);
        Button preset1 = new Button("Blue");
        preset1.setOnAction(e -> {
            params.materialHue(0.0f, 0.33f, 0.67f);
            hueOffsetSlider.setValue(0.33);
            needsRender = true;
        });
        Button preset2 = new Button("Fire");
        preset2.setOnAction(e -> {
            params.materialHue(0.0f, 0.1f, 0.2f);
            lightColorPicker.setValue(Color.rgb(255, 200, 150));
            params.lightColor(1.0f, 0.78f, 0.59f);
            needsRender = true;
        });
        Button preset3 = new Button("Ice");
        preset3.setOnAction(e -> {
            params.materialHue(0.5f, 0.6f, 0.7f);
            lightColorPicker.setValue(Color.rgb(200, 220, 255));
            params.lightColor(0.78f, 0.86f, 1.0f);
            needsRender = true;
        });
        Button preset4 = new Button("Gold");
        preset4.setOnAction(e -> {
            params.materialHue(0.1f, 0.15f, 0.0f);
            lightColorPicker.setValue(Color.rgb(255, 230, 180));
            params.lightColor(1.0f, 0.9f, 0.7f);
            needsRender = true;
        });
        presetBox.getChildren().addAll(preset1, preset2, preset3, preset4);

        panel.getChildren().addAll(
            dirLabel,
            lightXLabel, lightXSlider,
            lightYLabel, lightYSlider,
            lightZLabel, lightZSlider,
            new Separator(),
            lightColLabel, lightColorPicker,
            lightIntLabel, lightIntensitySlider,
            new Separator(),
            ambientLabel, ambientColorPicker,
            ambientIntLabel, ambientIntensitySlider,
            new Separator(),
            hueLabel, hueOffsetLabel, hueOffsetSlider,
            new Separator(),
            presetLabel, presetBox
        );

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private ScrollPane createQualityPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // === Render Pass Visualization ===
        Label passLabel = new Label("Render Pass:");
        passLabel.setStyle("-fx-font-weight: bold;");

        ComboBox<String> passCombo = new ComboBox<>();
        passCombo.getItems().addAll(
            "Final (Complete)",
            "Normals",
            "Depth",
            "Ambient Occlusion",
            "Shadows",
            "Diffuse",
            "Specular",
            "Orbit Trap (Colors)",
            "Iterations"
        );
        passCombo.setValue("Final (Complete)");
        passCombo.setMaxWidth(Double.MAX_VALUE);
        passCombo.setOnAction(e -> {
            int mode = passCombo.getSelectionModel().getSelectedIndex();
            params.setRenderMode(mode);
            needsRender = true;
        });

        // === Auto Full Quality ===
        CheckBox autoFullQualityCheck = new CheckBox("Auto Full Quality (slower)");
        autoFullQualityCheck.setSelected(false);
        autoFullQualityCheck.setOnAction(e -> {
            autoFullQuality = autoFullQualityCheck.isSelected();
            needsRender = true;
        });

        // === Shadows ===
        Label shadowLabel = new Label("Shadows:");
        shadowLabel.setStyle("-fx-font-weight: bold;");

        Label shadowSoftLabel = new Label("Shadow Softness: 16");
        shadowSoftnessSlider = new Slider(1, 64, 16);
        shadowSoftnessSlider.valueProperty().addListener((obs, old, val) -> {
            shadowSoftLabel.setText(String.format("Shadow Softness: %.0f", val.doubleValue()));
            params.shadowSoftness(val.floatValue());
            needsRender = true;
        });

        Label shadowStepsLabel = new Label("Shadow Steps: 128");
        Slider shadowStepsSlider = new Slider(32, 256, 128);
        shadowStepsSlider.setMajorTickUnit(64);
        shadowStepsSlider.setShowTickLabels(true);
        shadowStepsSlider.valueProperty().addListener((obs, old, val) -> {
            int steps = val.intValue();
            shadowStepsLabel.setText(String.format("Shadow Steps: %d", steps));
            params.setShadowSteps(steps);
            needsRender = true;
        });

        // === Ambient Occlusion ===
        Label aoLabel = new Label("Ambient Occlusion:");
        aoLabel.setStyle("-fx-font-weight: bold;");

        Label aoIntLabel = new Label("AO Intensity: 0.5");
        aoIntensitySlider = new Slider(0, 1, 0.5);
        aoIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            aoIntLabel.setText(String.format("AO Intensity: %.2f", val.doubleValue()));
            params.aoIntensity(val.floatValue());
            needsRender = true;
        });

        // === Specular ===
        Label specLabel = new Label("Specular:");
        specLabel.setStyle("-fx-font-weight: bold;");

        Label specIntLabel = new Label("Specular Intensity: 0.5");
        specularIntensitySlider = new Slider(0, 2, 0.5);
        specularIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            specIntLabel.setText(String.format("Specular Intensity: %.2f", val.doubleValue()));
            params.specularIntensity(val.floatValue());
            needsRender = true;
        });

        Label specPowLabel = new Label("Specular Power: 32");
        specularPowerSlider = new Slider(4, 128, 32);
        specularPowerSlider.valueProperty().addListener((obs, old, val) -> {
            specPowLabel.setText(String.format("Specular Power: %.0f", val.doubleValue()));
            params.specularPower(val.floatValue());
            needsRender = true;
        });

        // === Glow ===
        Label glowLabel = new Label("Glow:");
        glowLabel.setStyle("-fx-font-weight: bold;");

        Label glowIntLabel = new Label("Glow Intensity: 0.15");
        glowIntensitySlider = new Slider(0, 1, 0.15);
        glowIntensitySlider.valueProperty().addListener((obs, old, val) -> {
            glowIntLabel.setText(String.format("Glow Intensity: %.2f", val.doubleValue()));
            params.glowIntensity(val.floatValue());
            needsRender = true;
        });

        // === Depth of Field ===
        Label dofLabel = new Label("Depth of Field:");
        dofLabel.setStyle("-fx-font-weight: bold;");

        CheckBox dofEnabledCheck = new CheckBox("Enable DoF");
        dofEnabledCheck.setSelected(false);
        dofEnabledCheck.setOnAction(e -> {
            params.setDofEnabled(dofEnabledCheck.isSelected());
            needsRender = true;
        });

        Label focalDistLabel = new Label("Focal Distance: 2.5");
        Slider focalDistSlider = new Slider(0.1, 10, 2.5);
        focalDistSlider.valueProperty().addListener((obs, old, val) -> {
            focalDistLabel.setText(String.format("Focal Distance: %.2f", val.doubleValue()));
            params.setFocalDistance(val.floatValue());
            needsRender = true;
        });

        Label apertureLabel = new Label("Aperture: 0.02");
        Slider apertureSlider = new Slider(0, 0.2, 0.02);
        apertureSlider.valueProperty().addListener((obs, old, val) -> {
            apertureLabel.setText(String.format("Aperture: %.3f", val.doubleValue()));
            params.setAperture(val.floatValue());
            needsRender = true;
        });

        Label dofSamplesLabel = new Label("DoF Samples: 16");
        Slider dofSamplesSlider = new Slider(4, 64, 16);
        dofSamplesSlider.setMajorTickUnit(16);
        dofSamplesSlider.setShowTickLabels(true);
        dofSamplesSlider.valueProperty().addListener((obs, old, val) -> {
            int samples = val.intValue();
            dofSamplesLabel.setText(String.format("DoF Samples: %d", samples));
            params.setDofSamples(samples);
            needsRender = true;
        });

        Label dofInfoLabel = new Label("Note: DoF is slow. Use low samples\nfor preview, increase for final.");
        dofInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");

        // Quality presets
        Label presetLabel = new Label("Quality Presets:");
        presetLabel.setStyle("-fx-font-weight: bold;");

        HBox presetBox = new HBox(5);
        Button fastBtn = new Button("Fast");
        fastBtn.setOnAction(e -> {
            shadowSoftnessSlider.setValue(8);
            aoIntensitySlider.setValue(0.3);
            specularIntensitySlider.setValue(0.3);
            iterationsSlider.setValue(10);
        });
        Button balancedBtn = new Button("Balanced");
        balancedBtn.setOnAction(e -> {
            shadowSoftnessSlider.setValue(16);
            aoIntensitySlider.setValue(0.5);
            specularIntensitySlider.setValue(0.5);
            iterationsSlider.setValue(15);
        });
        Button highBtn = new Button("High");
        highBtn.setOnAction(e -> {
            shadowSoftnessSlider.setValue(32);
            aoIntensitySlider.setValue(0.7);
            specularIntensitySlider.setValue(0.6);
            iterationsSlider.setValue(20);
        });
        presetBox.getChildren().addAll(fastBtn, balancedBtn, highBtn);

        panel.getChildren().addAll(
            passLabel, passCombo,
            autoFullQualityCheck,
            new Separator(),
            shadowLabel, shadowSoftLabel, shadowSoftnessSlider,
            shadowStepsLabel, shadowStepsSlider,
            new Separator(),
            aoLabel, aoIntLabel, aoIntensitySlider,
            new Separator(),
            specLabel,
            specIntLabel, specularIntensitySlider,
            specPowLabel, specularPowerSlider,
            new Separator(),
            glowLabel, glowIntLabel, glowIntensitySlider,
            new Separator(),
            dofLabel, dofEnabledCheck,
            focalDistLabel, focalDistSlider,
            apertureLabel, apertureSlider,
            dofSamplesLabel, dofSamplesSlider,
            dofInfoLabel,
            new Separator(),
            presetLabel, presetBox
        );

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private ScrollPane createExportPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // Output size
        Label sizeLabel = new Label("Output Size:");
        sizeLabel.setStyle("-fx-font-weight: bold;");

        // Preset sizes
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("1920x1080 (Full HD)", "3840x2160 (4K)", "7680x4320 (8K)", "Custom");
        presetCombo.setValue("1920x1080 (Full HD)");
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        presetCombo.setOnAction(e -> {
            String preset = presetCombo.getValue();
            if (preset.startsWith("1920")) {
                widthField.setText("1920"); heightField.setText("1080");
            } else if (preset.startsWith("3840")) {
                widthField.setText("3840"); heightField.setText("2160");
            } else if (preset.startsWith("7680")) {
                widthField.setText("7680"); heightField.setText("4320");
            }
            updateOutputSize();
        });

        HBox sizeBox = new HBox(5);
        sizeBox.setAlignment(Pos.CENTER_LEFT);
        widthField = new TextField("1920");
        widthField.setPrefWidth(80);
        heightField = new TextField("1080");
        heightField.setPrefWidth(80);
        sizeBox.getChildren().addAll(widthField, new Label("x"), heightField);

        // Buttons
        Button renderBtn = new Button("Render Full Quality (Space)");
        renderBtn.setOnAction(e -> renderFull());
        renderBtn.setMaxWidth(Double.MAX_VALUE);
        renderBtn.setStyle("-fx-font-weight: bold;");

        Button exportBtn = new Button("Export PNG...");
        exportBtn.setOnAction(e -> exportImage());
        exportBtn.setMaxWidth(Double.MAX_VALUE);

        // Info
        Label infoLabel = new Label(
            "Tips:\n" +
            "- Use preview to find a good angle\n" +
            "- Press Space for full quality render\n" +
            "- Higher iterations = more detail\n" +
            "- 4K/8K may take several minutes"
        );
        infoLabel.setStyle("-fx-font-size: 11px;");
        infoLabel.setWrapText(true);

        panel.getChildren().addAll(
            sizeLabel, presetCombo, sizeBox,
            new Separator(),
            renderBtn,
            exportBtn,
            new Separator(),
            infoLabel
        );

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void setupKeyboardControls(Scene scene) {
        // Only capture navigation keys when imageContainer has focus
        imageContainer.setOnKeyPressed(e -> {
            // Track navigation keys
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN ||
                e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT ||
                e.getCode() == KeyCode.PAGE_UP || e.getCode() == KeyCode.PAGE_DOWN) {
                pressedKeys.add(e.getCode());
                e.consume();
            }

            // R to reset camera
            if (e.getCode() == KeyCode.R) {
                camera.reset();
                needsRender = true;
                updatePositionLabel();
                e.consume();
            }

            // Space to render full quality
            if (e.getCode() == KeyCode.SPACE) {
                renderFull();
                e.consume();
            }

            // Q/E for roll
            if (e.getCode() == KeyCode.Q) {
                camera.roll(-1);
                needsRender = true;
                e.consume();
            }
            if (e.getCode() == KeyCode.E) {
                camera.roll(1);
                needsRender = true;
                e.consume();
            }
        });

        imageContainer.setOnKeyReleased(e -> {
            pressedKeys.remove(e.getCode());
        });

        // Clear pressed keys when focus is lost to prevent stuck keys
        imageContainer.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                pressedKeys.clear();
            }
        });
    }

    private void setupMouseControls() {
        // Start drag on mouse press
        imageContainer.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                isDragging = true;
                dragStartX = e.getScreenX();
                dragStartY = e.getScreenY();
                imageContainer.setCursor(Cursor.CLOSED_HAND);
            }
        });

        // Handle drag movement
        imageContainer.setOnMouseDragged(e -> {
            if (isDragging) {
                double deltaX = e.getScreenX() - dragStartX;
                double deltaY = e.getScreenY() - dragStartY;

                // Apply rotation
                camera.rotate((float) deltaX, (float) deltaY);
                needsRender = true;

                // Update start position for next delta
                dragStartX = e.getScreenX();
                dragStartY = e.getScreenY();
            }
        });

        // End drag on mouse release
        imageContainer.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                isDragging = false;
                imageContainer.setCursor(Cursor.OPEN_HAND);
            }
        });

        // Change cursor when entering/leaving image area
        imageContainer.setOnMouseEntered(e -> {
            if (!isDragging) {
                imageContainer.setCursor(Cursor.OPEN_HAND);
            }
            imageContainer.requestFocus();
        });

        imageContainer.setOnMouseExited(e -> {
            if (!isDragging) {
                imageContainer.setCursor(Cursor.DEFAULT);
            }
        });

        // Scroll for movement speed adjustment
        imageContainer.setOnScroll(e -> {
            double delta = e.getDeltaY() > 0 ? 1.1 : 0.9;
            float newSpeed = camera.getMoveSpeed() * (float) delta;
            newSpeed = Math.max(0.001f, Math.min(1.0f, newSpeed));
            camera.setMoveSpeed(newSpeed);
            speedSlider.setValue(newSpeed);
        });
    }

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Process keyboard input
                processKeyboardInput();

                // Render if needed (with throttling)
                if (needsRender && !controller.isRendering()) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastRenderTime > RENDER_DELAY_MS) {
                        needsRender = false;
                        lastRenderTime = currentTime;
                        renderPreview();
                    }
                }
            }
        };
        timer.start();
    }

    private void processKeyboardInput() {
        boolean moved = false;

        // Arrow keys for movement
        if (pressedKeys.contains(KeyCode.UP)) {
            camera.moveForward(1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.DOWN)) {
            camera.moveForward(-1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.LEFT)) {
            camera.strafe(-1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.RIGHT)) {
            camera.strafe(1);
            moved = true;
        }

        // Page Up/Down for vertical movement
        if (pressedKeys.contains(KeyCode.PAGE_UP)) {
            camera.moveUp(1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.PAGE_DOWN)) {
            camera.moveUp(-1);
            moved = true;
        }

        if (moved) {
            needsRender = true;
            updatePositionLabel();
        }
    }

    private void updatePositionLabel() {
        float[] pos = camera.getPosition();
        positionLabel.setText(String.format("Pos: (%.2f, %.2f, %.2f)", pos[0], pos[1], pos[2]));
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(200);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusLabel, spacer, progressBar);
        return statusBar;
    }

    private void updateOutputSize() {
        try {
            int width = Integer.parseInt(widthField.getText());
            int height = Integer.parseInt(heightField.getText());
            controller.setOutputSize(width, height);
        } catch (NumberFormatException e) {
            // Keep current size
        }
    }

    private void renderPreview() {
        updateOutputSize();
        progressBar.setProgress(0);

        if (autoFullQuality) {
            // Use full quality render instead of preview
            controller.renderFull(
                image -> imageView.setImage(image),
                progress -> progressBar.setProgress(progress),
                null
            );
        } else {
            controller.renderPreview(
                image -> imageView.setImage(image),
                progress -> progressBar.setProgress(progress)
            );
        }
    }

    private void renderFull() {
        updateOutputSize();
        statusLabel.setText("Rendering full quality...");
        progressBar.setProgress(0);

        controller.renderFull(
            image -> {
                imageView.setImage(image);
                statusLabel.setText("Render complete");
            },
            progress -> progressBar.setProgress(progress),
            null
        );
    }

    private void exportImage() {
        updateOutputSize();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Image");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fileChooser.setInitialFileName("fractal.png");

        File file = fileChooser.showSaveDialog(imageView.getScene().getWindow());
        if (file != null) {
            statusLabel.setText("Exporting...");
            progressBar.setProgress(0);

            controller.exportToPNG(file, progress -> progressBar.setProgress(progress))
                .thenRun(() -> Platform.runLater(() ->
                    statusLabel.setText("Exported to: " + file.getName())
                ))
                .exceptionally(e -> {
                    Platform.runLater(() -> showError("Export failed", e.getMessage()));
                    return null;
                });
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}