package org.fractalizer;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.geometry.Orientation;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.AnimationManager;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.NavigationController;
import org.fractalizer.ui.panels.*;
import org.fractalizer.ui.timeline.TimelineWidget;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GLSL-based JavaFX application for Fractaliz3r.
 * Uses progressive rendering with OpenGL shaders.
 */
public class GLSLFractalizerApp extends Application {

    // Core components
    private GLSLFractalizerController controller;
    private NavigationController navigation;
    private Stage primaryStage;

    // UI components
    private ImageView imageView;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label sampleLabel;
    private StackPane imageContainer;

    // File handling
    private File currentConfigFile;
    private final FileChooser fileChooser = new FileChooser();

    // Panels
    private FractalPanel fractalPanel;
    private LightingPanel lightingPanel;
    private MaterialPanel materialPanel;
    private QualityPanel qualityPanel;
    private ExportPanel exportPanel;
    private GLSLDevicePanel devicePanel;
    private PostProcessingPanel postProcessPanel;
    private EnvironmentPanel environmentPanel;

    // List of panels that need refreshing when config changes
    private final List<Refreshable> refreshablePanels = new ArrayList<>();

    // Animation manager (handles timeline and keyframes)
    private AnimationManager animationManager;

    // Rendering state
    private boolean needsRender = true;
    private long lastRenderTime = 0;
    private static final long RENDER_DELAY_MS = 100;
    private boolean autoFullQuality = true;
    private volatile boolean exportingAnimation = false;

    @Override
    public void start(Stage primaryStage) {
        try {
            controller = new GLSLFractalizerController();
        } catch (Exception e) {
            e.printStackTrace();
            showError("Failed to initialize GLSL Engine", e.getMessage());
            Platform.exit();
            return;
        }

        // Initialize params
        AbstractFractalParams initialParams = new MandelbulbParams();
        controller.setParams(initialParams);

        // Store stage reference
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Fractaliz3r GLSL - 3D Fractal Renderer");

        // Initialize file chooser
        fileChooser.setTitle("Fractal Configuration");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(FractalConfigManager.getFileFilterDescription(), "*" + FractalConfigManager.getFileExtension()),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        // Center: Image view - fills available space dynamically
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setManaged(false);

        imageContainer = new StackPane();
        imageContainer.setStyle("-fx-background-color: #1a1a2e;");
        imageContainer.setFocusTraversable(true);
        imageContainer.getChildren().add(imageView);

        // Position and size ImageView manually when container resizes
        imageContainer.layoutBoundsProperty().addListener((obs, old, bounds) -> {
            double w = bounds.getWidth();
            double h = bounds.getHeight();
            imageView.setFitWidth(w);
            imageView.setFitHeight(h);
            imageView.setLayoutX(0);
            imageView.setLayoutY(0);
        });

        // Right: Controls panel with tabs
        TabPane controlTabs = createControlTabs(initialParams);
        controlTabs.setMinWidth(250);
        controlTabs.setPrefWidth(320);

        // Horizontal SplitPane: 3D View | Parameters
        SplitPane horizontalSplit = new SplitPane();
        horizontalSplit.setOrientation(Orientation.HORIZONTAL);
        horizontalSplit.getItems().addAll(imageContainer, controlTabs);
        horizontalSplit.setDividerPositions(0.75);
        SplitPane.setResizableWithParent(controlTabs, false);

        // Set minimum sizes to prevent crashes during resize
        imageContainer.setMinWidth(100);
        imageContainer.setMinHeight(100);
        controlTabs.setMinWidth(200);

        // Timeline panel (separate from status bar)
        TimelineWidget timelinePanel = createTimelinePanel();
        timelinePanel.setMinHeight(100);
        timelinePanel.setPrefHeight(180);

        // Vertical SplitPane: Top (3D + params) | Bottom (Timeline only)
        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(horizontalSplit, timelinePanel);
        verticalSplit.setDividerPositions(0.72);
        SplitPane.setResizableWithParent(timelinePanel, false);

        // Minimum size for top area
        horizontalSplit.setMinHeight(150);

        // Status bar - always at bottom, outside SplitPane
        HBox statusBar = createStatusBar();

        // Create menu bar
        MenuBar menuBar = createMenuBar();

        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(0, 5, 0, 5));  // No top/bottom padding
        root.setTop(menuBar);
        root.setCenter(verticalSplit);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1400, 850);

        // Setup navigation controller
        navigation = new NavigationController(
            imageContainer,
            () -> fractalPanel.getCamera(),
            this::requestRender,
            this::renderFull,
            this::resetCamera,
            newSpeed -> fractalPanel.setSpeedSliderValue(newSpeed)
        );

        // Setup click-to-focus handler (middle click or Ctrl+click)
        setupFocusPickHandler();

        primaryStage.setScene(scene);
        primaryStage.show();

        // Focus the image container for keyboard input
        imageContainer.requestFocus();

        // Initial status
        statusLabel.setText("GPU: " + controller.getDeviceName());

        // Connect export panel to timeline (after animationManager is created)
        exportPanel.setTimelineSupplier(() -> animationManager.getTimeline());
        exportPanel.setAnimationExportCallback((file, width, height, samples) -> {
            animationManager.applyTimelineToParams();
            javafx.scene.image.Image image = controller.exportAnimationFrame(file, width, height, samples);
            updateImage(image);
        });
        exportPanel.setMotionBlurExportCallback((file, width, height, samples, frameTime, fps, shutterAngle) -> {
            // Motion blur: render samples at jittered times
            javafx.scene.image.Image image = controller.exportAnimationFrameWithMotionBlur(
                file, width, height, samples, frameTime, fps, shutterAngle,
                time -> {
                    // Apply animation params at the jittered time
                    animationManager.getTimeline().setCurrentTime(time);
                    animationManager.applyTimelineToParams();
                });
            updateImage(image);
        });
        exportPanel.setExportStateCallback(exporting -> {
            this.exportingAnimation = exporting;
            if (!exporting) {
                updateViewportSize();
                needsRender = true;
            }
        });

        // Listen for viewport size changes
        setupViewportSizeListener();

        // Start render loop
        startRenderLoop();

        // Initial viewport size update (after layout is done)
        Platform.runLater(() -> {
            updateViewportSize();
            renderPreview();
        });
    }

    /**
     * Setup listeners for viewport size changes.
     * Automatically updates the controller and triggers re-render.
     * Ignored during animation export to prevent conflicts.
     */
    private void setupViewportSizeListener() {
        // Listen for layout bounds changes (single listener for both dimensions)
        imageContainer.layoutBoundsProperty().addListener((obs, old, bounds) -> {
            // Ignore size changes during animation export
            if (exportingAnimation) return;

            if (bounds.getWidth() > 0 && bounds.getHeight() > 0) {
                updateViewportSize();
                requestRender();
            }
        });
    }

    /**
     * Update the controller's viewport size from the image container.
     * Minimum size enforced to prevent OpenGL crashes.
     */
    private void updateViewportSize() {
        int width = (int) imageContainer.getLayoutBounds().getWidth();
        int height = (int) imageContainer.getLayoutBounds().getHeight();
        // Enforce minimum size to prevent OpenGL crashes during SplitPane resize
        if (width >= 64 && height >= 64) {
            controller.setViewportSize(width, height);
            exportPanel.updateViewportInfo();
        }
    }

    /**
     * Setup click-to-focus handler for Depth of Field.
     * Middle-click or Ctrl+click on the image to pick focal distance.
     */
    private void setupFocusPickHandler() {
        imageContainer.setOnMouseClicked(event -> {
            // Middle-click or Ctrl+click to pick focal distance
            boolean isMiddleClick = event.getButton() == javafx.scene.input.MouseButton.MIDDLE;
            boolean isCtrlClick = event.isControlDown() && event.getButton() == javafx.scene.input.MouseButton.PRIMARY;

            if (isMiddleClick || isCtrlClick) {
                int x = (int) event.getX();
                int y = (int) event.getY();

                float distance = controller.pickFocalDistance(x, y);

                if (distance > 0) {
                    statusLabel.setText(String.format("Focal distance: %.3f", distance));
                    // Update quality panel if it has a focal distance control
                    qualityPanel.updateFocalDistanceDisplay(distance);
                    requestRender();
                } else {
                    statusLabel.setText("No surface at click position");
                }

                event.consume();
            }
        });
    }

    private TabPane createControlTabs(AbstractFractalParams initialParams) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Fractal tab
        fractalPanel = new FractalPanel(controller, initialParams, this::requestRender);
        refreshablePanels.add(fractalPanel);
        Tab fractalTab = new Tab("Fractal", fractalPanel);

        // Lighting tab
        lightingPanel = new LightingPanel(
            () -> fractalPanel.getParams(),
            this::requestRender
        );
        refreshablePanels.add(lightingPanel);
        Tab lightingTab = new Tab("Lighting", lightingPanel);

        // Material tab (New Unified System)
        materialPanel = new MaterialPanel(
            () -> fractalPanel.getParams(),
            this::requestRender
        );
        refreshablePanels.add(materialPanel);
        Tab materialTab = new Tab("Material", materialPanel);

        // Quality tab - with samples control for GLSL
        qualityPanel = new QualityPanel(
            () -> fractalPanel.getParams(),
            this::requestRender,
            auto -> this.autoFullQuality = auto
        );
        refreshablePanels.add(qualityPanel);
        Tab qualityTab = new Tab("Quality", qualityPanel);

        // Export tab
        exportPanel = new ExportPanel(
            controller,
            this::renderFull,
            progress -> progressBar.setProgress(progress),
            status -> statusLabel.setText(status)
        );
        // Connect ExportPanel to timeline (will be set after timeline is created)
        // Note: timeline is created in createBottomPanel which is called after this
        Tab exportTab = new Tab("Export", exportPanel);

        // Device tab - GLSL info
        devicePanel = new GLSLDevicePanel(controller);
        Tab deviceTab = new Tab("Device", devicePanel);

        // Post-processing tab
        PostProcessParams postProcessParams = controller.getEngine().getPostProcessParams();
        postProcessPanel = new PostProcessingPanel(postProcessParams, this::requestRender);
        refreshablePanels.add(postProcessPanel);
        Tab postProcessTab = new Tab("FX", postProcessPanel);

        // Environment tab
        environmentPanel = new EnvironmentPanel(controller.getEngine(), () -> (AbstractFractalParams) controller.getParams(), this::requestRender);
        refreshablePanels.add(environmentPanel);
        Tab environmentTab = new Tab("Env", environmentPanel);

        tabPane.getTabs().addAll(fractalTab, lightingTab, materialTab, qualityTab, postProcessTab, environmentTab, exportTab, deviceTab);

        return tabPane;
    }

    private TimelineWidget createTimelinePanel() {
        // Create animation manager (handles timeline, tracks, and keyframes)
        animationManager = new AnimationManager(
            () -> fractalPanel.getParams(),
            this::requestRender,
            () -> fractalPanel.updatePositionLabel(),
            status -> statusLabel.setText(status)
        );

        return animationManager.getWidget();
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(3, 10, 3, 10));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setStyle("-fx-background-color: #2a2a35;");

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: #ccc;");
        sampleLabel = new Label("Samples: 0");
        sampleLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: #aaa;");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(150);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusLabel, spacer, sampleLabel, progressBar);
        return statusBar;
    }

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Skip render loop during animation export
                if (exportingAnimation) return;

                // Handle timeline playback via AnimationManager
                if (animationManager != null && animationManager.updatePlayback(now)) {
                    fractalPanel.updatePositionLabel();
                    needsRender = true;
                }

                // Process keyboard input (only when not playing animation)
                if ((animationManager == null || !animationManager.isPlaying()) && navigation.processKeyboardInput()) {
                    needsRender = true;
                    fractalPanel.updatePositionLabel();
                }

                // Update sample count display
                if (controller.isRendering()) {
                    int samples = controller.getProgressiveRenderer().getCurrentSamples();
                    sampleLabel.setText("Samples: " + samples);
                }

                // Render if needed (with throttling)
                // Cancel current render if parameters changed (needsRender is true)
                if (needsRender) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastRenderTime > RENDER_DELAY_MS) {
                        needsRender = false;
                        lastRenderTime = currentTime;
                        renderPreview();  // This will cancel any current render
                    }
                }
            }
        };
        timer.start();
    }

    private void requestRender() {
        needsRender = true;
    }

    private void resetCamera() {
        fractalPanel.getCamera().reset();
        needsRender = true;
        fractalPanel.updatePositionLabel();
    }

    private void renderPreview() {
        progressBar.setProgress(0);
        sampleLabel.setText("Samples: 0");

        if (autoFullQuality) {
            controller.renderFull(
                this::updateImage,
                progress -> progressBar.setProgress(progress),
                null
            );
        } else {
            controller.renderPreview(
                this::updateImage,
                progress -> progressBar.setProgress(progress)
            );
        }
    }

    private void renderFull() {
        statusLabel.setText("Rendering full quality...");
        progressBar.setProgress(0);
        sampleLabel.setText("Samples: 0");

        controller.renderFull(
            image -> {
                updateImage(image);
                statusLabel.setText("Render complete");
            },
            progress -> progressBar.setProgress(progress),
            null
        );
    }

    private void updateImage(Image image) {
        imageView.setImage(image);
        int samples = controller.getProgressiveRenderer().getCurrentSamples();
        sampleLabel.setText("Samples: " + samples);
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

    // ========================================================================
    // Menu Bar and File Operations
    // ========================================================================

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        // File menu
        Menu fileMenu = new Menu("_File");

        MenuItem saveItem = new MenuItem("_Save Configuration");
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        saveItem.setOnAction(e -> saveConfiguration(false));

        MenuItem saveAsItem = new MenuItem("Save Configuration _As...");
        saveAsItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        saveAsItem.setOnAction(e -> saveConfiguration(true));

        MenuItem loadItem = new MenuItem("_Load Configuration...");
        loadItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        loadItem.setOnAction(e -> loadConfiguration());

        SeparatorMenuItem sep1 = new SeparatorMenuItem();

        MenuItem exitItem = new MenuItem("E_xit");
        exitItem.setOnAction(e -> Platform.exit());

        fileMenu.getItems().addAll(saveItem, saveAsItem, loadItem, sep1, exitItem);

        menuBar.getMenus().add(fileMenu);
        return menuBar;
    }

    /**
     * Save current configuration to file.
     * @param forceDialog If true, always show file dialog; if false, use current file if set
     */
    private void saveConfiguration(boolean forceDialog) {
        try {
            File targetFile = currentConfigFile;

            if (forceDialog || targetFile == null) {
                if (currentConfigFile != null) {
                    fileChooser.setInitialDirectory(currentConfigFile.getParentFile());
                    fileChooser.setInitialFileName(currentConfigFile.getName());
                } else {
                    // Default to current working directory
                    fileChooser.setInitialDirectory(new File(System.getProperty("user.dir")));
                }
                targetFile = fileChooser.showSaveDialog(primaryStage);
                if (targetFile == null) return; // User cancelled
            }

            // Ensure .frac extension
            if (!targetFile.getName().endsWith(FractalConfigManager.getFileExtension())) {
                targetFile = new File(targetFile.getAbsolutePath() + FractalConfigManager.getFileExtension());
            }

            // Create config from current params
            AbstractFractalParams params = fractalPanel.getParams();
            FractalConfig config = FractalConfig.fromParams(params);
            config.name = targetFile.getName().replace(FractalConfigManager.getFileExtension(), "");

            // Include animation if there are keyframes
            if (animationManager != null) {
                config.animation = animationManager.exportAnimation();
            }

            // Save to file
            FractalConfigManager.save(config, targetFile);
            currentConfigFile = targetFile;

            statusLabel.setText("Saved: " + targetFile.getName());
            primaryStage.setTitle("Fractaliz3r GLSL - " + targetFile.getName());

        } catch (IOException ex) {
            showError("Save Error", "Failed to save configuration: " + ex.getMessage());
        }
    }

    /**
     * Load configuration from file.
     */
    private void loadConfiguration() {
        try {
            if (currentConfigFile != null) {
                fileChooser.setInitialDirectory(currentConfigFile.getParentFile());
            } else {
                // Default to current working directory
                fileChooser.setInitialDirectory(new File(System.getProperty("user.dir")));
            }
            File file = fileChooser.showOpenDialog(primaryStage);
            if (file == null) return; // User cancelled

            // Load config from file
            FractalConfig config = FractalConfigManager.load(file);

            // Get fractal type and switch if needed
            FractalType type = config.getFractalTypeEnum();
            if (type != controller.getFractalType()) {
                controller.setFractalType(type);
            }

            // Apply config to current params
            AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
            config.applyTo(params);

            // Update fractal panel reference
            fractalPanel.setParams(params);

            // Refresh all UI panels
            for (Refreshable pnl : refreshablePanels) {
                pnl.refreshFromParams();
            }

            // Import animation if present
            if (animationManager != null && config.animation != null) {
                animationManager.importAnimation(config.animation);
            }

            currentConfigFile = file;
            statusLabel.setText("Loaded: " + file.getName());
            primaryStage.setTitle("Fractaliz3r GLSL - " + file.getName());

            // Trigger render
            requestRender();

        } catch (IOException ex) {
            showError("Load Error", "Failed to load configuration: " + ex.getMessage());
        } catch (Exception ex) {
            showError("Load Error", "Invalid configuration file: " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
