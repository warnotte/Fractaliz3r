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
import javafx.stage.Stage;
import org.fractalizer.engine.GLSLEngine.PostProcessParams;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.ui.GLSLFractalizerController;
import org.fractalizer.ui.NavigationController;
import org.fractalizer.ui.panels.*;

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
    private AnimationPanel animationPanel;
    private PostProcessingPanel postProcessPanel;
    private EnvironmentPanel environmentPanel;

    // Rendering state
    private boolean needsRender = true;
    private long lastRenderTime = 0;
    private static final long RENDER_DELAY_MS = 100;
    private boolean autoFullQuality = true;

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

        // Main layout
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Center: Image view - fills available space dynamically
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        // Prevent ImageView from affecting layout calculations
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
            // Center the image
            imageView.setLayoutX(0);
            imageView.setLayoutY(0);
        });

        root.setCenter(imageContainer);

        // Right: Controls panel with tabs
        TabPane controlTabs = createControlTabs(initialParams);
        controlTabs.setPrefWidth(320);
        controlTabs.setMinWidth(320);
        controlTabs.setMaxWidth(320);
        root.setRight(controlTabs);

        // Bottom: Status bar
        HBox statusBar = createStatusBar();
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1280, 750);

        // Setup navigation controller
        navigation = new NavigationController(
            imageContainer,
            () -> fractalPanel.getCamera(),
            this::requestRender,
            this::renderFull,
            this::resetCamera,
            newSpeed -> fractalPanel.setSpeedSliderValue(newSpeed)
        );

        primaryStage.setScene(scene);
        primaryStage.show();

        // Focus the image container for keyboard input
        imageContainer.requestFocus();

        // Initial status
        statusLabel.setText("GPU: " + controller.getDeviceName());

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
     */
    private void setupViewportSizeListener() {
        // Listen for layout bounds changes (single listener for both dimensions)
        imageContainer.layoutBoundsProperty().addListener((obs, old, bounds) -> {
            if (bounds.getWidth() > 0 && bounds.getHeight() > 0) {
                updateViewportSize();
                requestRender();
            }
        });
    }

    /**
     * Update the controller's viewport size from the image container.
     */
    private void updateViewportSize() {
        int width = (int) imageContainer.getLayoutBounds().getWidth();
        int height = (int) imageContainer.getLayoutBounds().getHeight();
        if (width > 0 && height > 0) {
            controller.setViewportSize(width, height);
            exportPanel.updateViewportInfo();
        }
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
        Tab exportTab = new Tab("Export", exportPanel);

        // Device tab - GLSL info
        devicePanel = new GLSLDevicePanel(controller);
        Tab deviceTab = new Tab("Device", devicePanel);

        // Animation tab
        animationPanel = new AnimationPanel(
            () -> fractalPanel.getParams(),
            time -> fractalPanel.updatePositionLabel(),
            this::requestRender
        );
        Tab animationTab = new Tab("Anim", animationPanel);

        // Post-processing tab
        PostProcessParams postProcessParams = controller.getEngine().getPostProcessParams();
        postProcessPanel = new PostProcessingPanel(postProcessParams, this::requestRender);
        Tab postProcessTab = new Tab("FX", postProcessPanel);

        // Environment tab
        environmentPanel = new EnvironmentPanel(controller.getEngine(), this::requestRender);
        Tab environmentTab = new Tab("Env", environmentPanel);

        tabPane.getTabs().addAll(fractalTab, lightingTab, qualityTab, postProcessTab, environmentTab, animationTab, exportTab, deviceTab);

        return tabPane;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5));
        statusBar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready");
        sampleLabel = new Label("Samples: 0");
        sampleLabel.setStyle("-fx-font-family: monospace;");
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(200);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusBar.getChildren().addAll(statusLabel, spacer, sampleLabel, progressBar);
        return statusBar;
    }

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Process keyboard input
                if (navigation.processKeyboardInput()) {
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
