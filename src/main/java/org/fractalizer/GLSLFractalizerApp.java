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
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setCache(true);

        imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: black;");
        
        // Left: Controls
        TabPane controlTabs = createControlTabs(initialParams);
        controlTabs.setMinWidth(350);
        controlTabs.setPrefWidth(380);

        // Bottom: Timeline
        TimelineWidget timelineWidget = createTimelinePanel();

        // Layout with SplitPanes
        SplitPane horizontalSplit = new SplitPane();
        horizontalSplit.getItems().addAll(imageContainer, controlTabs);
        horizontalSplit.setDividerPositions(0.75);

        SplitPane verticalSplit = new SplitPane();
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.getItems().addAll(horizontalSplit, timelineWidget);
        verticalSplit.setDividerPositions(0.85);
        
        // Prevent timeline from disappearing
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
        postProcessPanel = new PostProcessingPanel(postProcessParams, () -> (AbstractFractalParams) controller.getParams(), this::requestRender);
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
        menuBar.getMenus().add(fileMenu);

        return menuBar;
    }

    private void requestRender() {
        needsRender = true;
        controller.cancelRender();
    }

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Process keyboard input
                if (navigation != null && navigation.processKeyboardInput()) {
                    requestRender();
                    fractalPanel.updatePositionLabel();
                }

                if (needsRender && (System.currentTimeMillis() - lastRenderTime > RENDER_DELAY_MS)) {
                    renderPreview();
                    needsRender = false;
                    lastRenderTime = System.currentTimeMillis();
                }
            }
        };
        timer.start();
    }

    private void renderPreview() {
        controller.renderPreview(this::updateImage, progress -> {
            progressBar.setProgress(progress);
            statusLabel.setText("Rendering Preview...");
        });
    }

    private void renderFull() {
        controller.renderFull(this::updateImage, progress -> {
            progressBar.setProgress(progress);
            statusLabel.setText("Rendering High Quality...");
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
            statusLabel.setText("Render convergence: " + controller.getEngine().getSampleCount() + " samples");
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