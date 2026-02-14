package org.fractalizer;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
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
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeType;
import javafx.geometry.Orientation;
import javafx.util.Duration;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.config.FractalConfigManager;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.fractals.*;
import org.fractalizer.ui.AnimationManager;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.NavigationController;
import org.fractalizer.ui.SplashScreen;
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
    private Circle focusRing;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label sampleLabel;
    private StackPane imageContainer;
    private SplitPane verticalSplit;
    private SplitPane horizontalSplit;

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
    private AudioPanel audioPanel;

    // List of panels that need refreshing when config changes
    private final List<Refreshable> refreshablePanels = new ArrayList<>();

    // Animation manager (handles timeline and keyframes)
    private AnimationManager animationManager;

    // Rendering state
    private boolean needsRender = true;
    private long lastRenderTime = 0;
    private long lastInteractionTime = 0; // For auto-quality debounce
    private boolean isHighQualityActive = false; // To avoid restarting HQ render repeatedly
    
    private static final long RENDER_DELAY_MS = 33; // ~30 FPS for preview
    private static final long HQ_DELAY_MS = 400;    // Wait 400ms before refining
    
    private boolean autoFullQuality = true;
    private volatile boolean exportingAnimation = false;
    
    // Eye Candy state
    private boolean turntableMode = false;
    private float turntableSpeed = 0.5f;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        
        // Show Splash Screen first
        SplashScreen splash = new SplashScreen();
        splash.show();

        // Perform initialization in background thread
        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Initializing OpenGL...");
                updateProgress(0, 100);
                
                controller = new GLSLFractalizerController();
                
                // Load shaders one by one and report progress
                controller.loadAllShaders((status, progress) -> {
                    updateMessage(status);
                    updateProgress(progress * 100, 100);
                });
                
                return null;
            }
        };

        // Sync splash screen with task progress
        initTask.messageProperty().addListener((obs, old, msg) -> splash.update(msg, initTask.getProgress()));
        initTask.progressProperty().addListener((obs, old, prog) -> splash.update(initTask.getMessage(), prog.doubleValue()));

        initTask.setOnSucceeded(e -> {
            splash.hide();
            setupMainApp();
        });

        initTask.setOnFailed(e -> {
            splash.hide();
            Throwable ex = initTask.getException();
            ex.printStackTrace();
            showError("Initialization Failed", ex.getMessage());
            Platform.exit();
        });

        new Thread(initTask).start();
    }

    private void setupMainApp() {
        // Initialize params
        AbstractFractalParams initialParams = new MandelbulbParams();
        controller.setParams(initialParams);

        primaryStage.setTitle("Fractaliz3r GLSL - 3D Fractal Renderer");

        // Initialize file chooser
        fileChooser.setTitle("Fractal Configuration");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(FractalConfigManager.getFileFilterDescription(), "*" + FractalConfigManager.getFileExtension()),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        // Center: Image view - fills available space dynamically
        imageView = new ImageView();
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.setCache(true);
        imageView.setManaged(false); // Exclude from layout calculations

        imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: black;");
        imageContainer.setMinSize(0, 0); // Allow SplitPane to resize freely

        // Initialize Focus Ring (Cyber Overlay)
        focusRing = new Circle(30);
        focusRing.setFill(Color.TRANSPARENT);
        focusRing.setStroke(Color.CYAN);
        focusRing.setStrokeWidth(2);
        focusRing.setStrokeType(StrokeType.INSIDE);
        focusRing.setOpacity(0);
        focusRing.setMouseTransparent(true); // Don't block clicks
        focusRing.setManaged(false); // Manually positioned
        imageContainer.getChildren().add(focusRing);

        // Left: Controls
        TabPane controlTabs = createControlTabs(initialParams);
        controlTabs.setMinWidth(280);
        controlTabs.setPrefWidth(380);

        // Bottom: Timeline
        TimelineWidget timelineWidget = createTimelinePanel();

        // Wire fractal type changes to animation manager
        fractalPanel.setOnFractalTypeChanged((type, params) ->
            animationManager.onFractalTypeChanged(type, params)
        );
        // Initial sync: register the current fractal type with animation manager
        animationManager.onFractalTypeChanged(initialParams.getType(), initialParams);

        // Refresh all UI panel sliders when timeline applies values (scrub/playback)
        // Skip during animation export to avoid unnecessary UI updates
        animationManager.setOnParamsApplied(() -> {
            if (!exportingAnimation) {
                for (Refreshable pnl : refreshablePanels) {
                    pnl.refreshFromParams(true);
                }
            }
        });

        // Layout with SplitPanes
        horizontalSplit = new SplitPane();
        horizontalSplit.getItems().addAll(imageContainer, controlTabs);
        horizontalSplit.setDividerPositions(0.75);

        verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(horizontalSplit, timelineWidget);
        verticalSplit.setDividerPositions(0.85);

        // Prevent timeline from disappearing
        horizontalSplit.setMinHeight(150);
        // When window resizes, extra space goes to the viewport, not timeline/params
        SplitPane.setResizableWithParent(timelineWidget, false);
        SplitPane.setResizableWithParent(controlTabs, false);

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
        
        // Apply Modern Dark Theme
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

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
        exportPanel.setPrepareFrameCallback(() -> animationManager.applyTimelineToParams());
        exportPanel.setAnimationExportCallback((file, width, height, samples, onProgress, cancelCheck) -> {
            return controller.exportAnimationFrame(file, width, height, samples, onProgress, cancelCheck);
        });
        exportPanel.setMotionBlurExportCallback((file, width, height, samples, frameTime, fps, shutterAngle, onProgress, cancelCheck) -> {
            return controller.exportAnimationFrameWithMotionBlur(
                file, width, height, samples, frameTime, fps, shutterAngle,
                time -> {
                    animationManager.getTimeline().setCurrentTime(time);
                    animationManager.applyTimelineToParams();
                },
                onProgress, cancelCheck);
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

        // Upload initial gradient texture to GPU
        controller.updatePaletteTexture(initialParams.getCustomGradient());

        // Initial viewport size update (after layout is done)
        // Double-runLater ensures SplitPane layout is finalized before we enforce positions
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                verticalSplit.setDividerPositions(0.85);
                horizontalSplit.setDividerPositions(0.75);
                updateViewportSize();
                renderPreview();
            });
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
            // Scale ImageView to fill container (no binding to avoid circular layout)
            imageView.setFitWidth(width);
            imageView.setFitHeight(height);
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
                    statusLabel.setText(String.format("Focal distance set: %.3f", distance));
                    qualityPanel.updateFocalDistanceDisplay(distance);
                    playFocusAnimation(x, y, true);
                    requestRender();
                } else {
                    statusLabel.setText("No surface at click position");
                    playFocusAnimation(x, y, false);
                }

                event.consume();
            }
        });
    }

    private void playFocusAnimation(double x, double y, boolean success) {
        focusRing.setCenterX(x);
        focusRing.setCenterY(y);
        focusRing.setStroke(success ? Color.CYAN : Color.RED);
        
        // Reset state
        focusRing.setOpacity(1.0);
        focusRing.setScaleX(1.5);
        focusRing.setScaleY(1.5);

        ScaleTransition st = new ScaleTransition(Duration.millis(300), focusRing);
        st.setToX(0.2);
        st.setToY(0.2);

        FadeTransition ft = new FadeTransition(Duration.millis(400), focusRing);
        ft.setToValue(0);

        ParallelTransition pt = new ParallelTransition(st, ft);
        pt.play();
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
        materialPanel.setOnGradientChanged(gradient -> {
            controller.updatePaletteTexture(gradient);
            requestRender();
        });
        refreshablePanels.add(materialPanel);
        Tab materialTab = new Tab("Material", materialPanel);

        // Quality tab - with samples control for GLSL
        qualityPanel = new QualityPanel(
            () -> fractalPanel.getParams(),
            this::requestRender,
            auto -> this.autoFullQuality = auto
        );
        qualityPanel.setFullSamplesCallbacks(
            controller::setFullSamples,
            controller::getFullSamples
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
        postProcessPanel = new PostProcessingPanel(postProcessParams, () -> (AbstractFractalParams) controller.getParams(), this::requestRender);
        refreshablePanels.add(postProcessPanel);
        Tab postProcessTab = new Tab("FX", postProcessPanel);

        // Environment tab
        environmentPanel = new EnvironmentPanel(controller.getEngine(), () -> (AbstractFractalParams) controller.getParams(), this::requestRender);
        refreshablePanels.add(environmentPanel);
        Tab environmentTab = new Tab("Env", environmentPanel);

        // Audio tab (audio-reactive fractals)
        audioPanel = new AudioPanel(this::requestRender);
        controller.setAudioPanel(audioPanel);
        audioPanel.setFrameExportCallback((file, width, height, samples, progress, cancel) ->
                controller.exportAnimationFrame(file, width, height, samples, progress, cancel));
        Tab audioTab = new Tab("Audio", audioPanel);

        tabPane.getTabs().addAll(fractalTab, lightingTab, materialTab, qualityTab, postProcessTab, environmentTab, audioTab, exportTab, deviceTab);

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
        sampleLabel.setStyle("-fx-text-fill: #aaa;");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(200);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusLabel, sampleLabel, spacer, progressBar);
        return statusBar;
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem loadItem = new MenuItem("Load Config...");
        loadItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        loadItem.setOnAction(e -> loadConfig());

        MenuItem saveItem = new MenuItem("Save Config...");
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        saveItem.setOnAction(e -> saveConfig());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> Platform.exit());

        fileMenu.getItems().addAll(loadItem, saveItem, new SeparatorMenuItem(), exitItem);

        Menu viewMenu = new Menu("View");
        CheckMenuItem darkThemeItem = new CheckMenuItem("Modern Dark Theme");
        darkThemeItem.setSelected(true);
        darkThemeItem.setOnAction(e -> {
            if (darkThemeItem.isSelected()) {
                primaryStage.getScene().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            } else {
                primaryStage.getScene().getStylesheets().clear();
            }
        });

        MenuItem fullscreenItem = new MenuItem("Fullscreen");
        fullscreenItem.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        fullscreenItem.setOnAction(e -> primaryStage.setFullScreen(!primaryStage.isFullScreen()));

        viewMenu.getItems().addAll(darkThemeItem, new SeparatorMenuItem(), fullscreenItem);

        Menu helpMenu = new Menu("Help");
        MenuItem shortcutsItem = new MenuItem("Keyboard Shortcuts");
        shortcutsItem.setAccelerator(new KeyCodeCombination(KeyCode.F1));
        shortcutsItem.setOnAction(e -> showKeyboardShortcuts());
        helpMenu.getItems().add(shortcutsItem);

        menuBar.getMenus().addAll(fileMenu, viewMenu, helpMenu);

        return menuBar;
    }

    private void showKeyboardShortcuts() {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle("Keyboard Shortcuts");
        dialog.setHeaderText("Fractaliz3r Shortcuts");
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(420);

        String shortcuts = """
                Navigation
                  Arrow Keys          Move forward/back/strafe
                  Page Up / Down    Move up/down
                  Mouse Drag          Look around
                  Q / E                     Roll left/right
                  Scroll Wheel          Adjust movement speed
                  R                           Reset camera

                Rendering
                  Space                    Render full quality

                Depth of Field
                  Middle Click          Pick focal distance
                  Ctrl + Click           Pick focal distance

                File
                  Ctrl + O                Load configuration
                  Ctrl + S                Save configuration

                View
                  F11                       Toggle fullscreen
                  F1                         This dialog
                """;

        Label content = new Label(shortcuts);
        content.setStyle("-fx-font-family: monospace; -fx-font-size: 12;");
        dialog.getDialogPane().setContent(content);
        dialog.initOwner(primaryStage);
        dialog.showAndWait();
    }

    private void requestRender() {
        needsRender = true;
        isHighQualityActive = false; // Stop HQ accumulation
        lastInteractionTime = System.currentTimeMillis();
        controller.cancelRender();
        primaryStage.getScene().setCursor(null); // Clear busy cursor
    }

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                long currentTime = System.currentTimeMillis();

                // Process keyboard input
                if (navigation != null && navigation.processKeyboardInput()) {
                    requestRender();
                    fractalPanel.updatePositionLabel();
                }

                // Animation playback
                if (animationManager != null && animationManager.updatePlayback(now)) {
                    requestRender();
                }

                // 1. Preview Render (Interactive) — skip during any export to avoid engine resize races
                boolean audioExporting = (audioPanel != null && audioPanel.isExporting());
                if (needsRender && !exportingAnimation && !audioExporting
                        && (currentTime - lastRenderTime > RENDER_DELAY_MS)) {
                    renderPreview();
                    needsRender = false;
                    lastRenderTime = currentTime;
                    // Reset HQ trigger
                    lastInteractionTime = currentTime;
                    isHighQualityActive = false;
                }
                
                // Audio-reactive: force continuous preview when audio is playing (not during offline export)
                if (audioPanel != null && audioPanel.isAudioPlaying() && !audioPanel.isExporting()) {
                    if (!needsRender && (currentTime - lastRenderTime > RENDER_DELAY_MS)) {
                        needsRender = true;
                    }
                }

                // 2. Auto Full Quality (Refinement after idle)
                if (!needsRender && !isHighQualityActive && autoFullQuality && !exportingAnimation) {
                    // Don't start HQ render if animation or audio is playing
                    boolean isPlaying = (animationManager != null && animationManager.isPlaying());
                    boolean isAudioPlaying = (audioPanel != null && audioPanel.isAudioPlaying());

                    if (!isPlaying && !isAudioPlaying && (currentTime - lastInteractionTime > HQ_DELAY_MS)) {
                        // User stopped moving -> Start refining
                        renderFull();
                        isHighQualityActive = true;
                    }
                }
            }
        };
        timer.start();
    }

    private void renderPreview() {
        controller.renderPreview(this::updateImage, progress -> {
            progressBar.setProgress(progress);
            statusLabel.setText("Rendering preview...");
        });
    }

    private void renderFull() {
        primaryStage.getScene().setCursor(javafx.scene.Cursor.WAIT);
        controller.renderFull(this::updateImage, progress -> {
            progressBar.setProgress(progress);
            statusLabel.setText("Rendering high quality...");
            if (progress >= 1.0) {
                primaryStage.getScene().setCursor(null);
            }
        }, null);
    }

    private void resetCamera() {
        fractalPanel.getCamera().reset();
        requestRender();
        fractalPanel.updatePositionLabel();
    }

    private void updateImage(javafx.scene.image.Image image) {
        imageView.setImage(image);
        sampleLabel.setText("Samples: " + controller.getEngine().getSampleCount());
        if (controller.getEngine().getSampleCount() >= 1) {
            statusLabel.setText("Rendered: " + controller.getEngine().getSampleCount() + " samples");
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void saveConfig() {
        File file = fileChooser.showSaveDialog(primaryStage);
        if (file != null) {
            try {
                AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
                FractalConfig config = FractalConfig.fromParams(params);
                
                // Add animation data if manager exists
                if (animationManager != null) {
                    config.animation = animationManager.exportAnimation();
                }
                
                FractalConfigManager.save(config, file);
                currentConfigFile = file;
                statusLabel.setText("Saved: " + file.getName());
            } catch (IOException ex) {
                showError("Save Error", "Failed to save configuration: " + ex.getMessage());
            }
        }
    }

    private void loadConfig() {
        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            loadConfigFile(file);
        }
    }

    private void loadConfigFile(File file) {
        try {
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

            // Upload gradient texture to GPU
            controller.updatePaletteTexture(params.getCustomGradient());

            // Sync animation manager with loaded fractal type
            if (animationManager != null) {
                animationManager.onFractalTypeChanged(params.getType(), params);

                // Import animation if present
                if (config.animation != null) {
                    animationManager.importAnimation(config.animation);
                }
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

    @Override
    public void stop() {
        if (audioPanel != null) {
            audioPanel.dispose();
        }
        if (controller != null) {
            controller.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}