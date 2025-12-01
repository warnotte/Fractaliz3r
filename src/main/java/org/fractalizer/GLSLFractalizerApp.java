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
import javafx.scene.layout.*;
import javafx.geometry.Orientation;
import javafx.stage.Stage;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.ui.AnimationManager;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.NavigationController;
import org.fractalizer.ui.panels.*;
import org.fractalizer.ui.timeline.TimelineWidget;

/**
 * GLSL-based JavaFX application for Fractaliz3r.
 * Uses progressive rendering with OpenGL shaders.
 */
public class GLSLFractalizerApp extends Application {

    // Core components
    private GLSLFractalizerController controller;
    private NavigationController navigation;

    // UI components
    private ImageView imageView;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label sampleLabel;
    private StackPane imageContainer;

    // Panels
    private FractalPanel fractalPanel;
    private LightingPanel lightingPanel;
    private QualityPanel qualityPanel;
    private ExportPanel exportPanel;
    private GLSLDevicePanel devicePanel;
    private PostProcessingPanel postProcessPanel;
    private EnvironmentPanel environmentPanel;

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

        primaryStage.setTitle("Fractaliz3r GLSL - 3D Fractal Renderer");

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

        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(5, 5, 0, 5));  // No bottom padding (status bar has its own)
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
        Tab fractalTab = new Tab("Fractal", fractalPanel);

        // Lighting tab
        lightingPanel = new LightingPanel(
            () -> fractalPanel.getParams(),
            this::requestRender
        );
        Tab lightingTab = new Tab("Lighting", lightingPanel);

        // Quality tab - with samples control for GLSL
        qualityPanel = new QualityPanel(
            () -> fractalPanel.getParams(),
            this::requestRender,
            auto -> this.autoFullQuality = auto
        );
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
        Tab postProcessTab = new Tab("FX", postProcessPanel);

        // Environment tab
        environmentPanel = new EnvironmentPanel(controller.getEngine(), this::requestRender);
        Tab environmentTab = new Tab("Env", environmentPanel);

        tabPane.getTabs().addAll(fractalTab, lightingTab, qualityTab, postProcessTab, environmentTab, exportTab, deviceTab);

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

    public static void main(String[] args) {
        launch(args);
    }
}
