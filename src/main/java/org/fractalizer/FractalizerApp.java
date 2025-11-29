package org.fractalizer;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.MandelbulbParams;
import org.fractalizer.ui.FractalizerController;
import org.fractalizer.ui.NavigationController;
import org.fractalizer.ui.panels.*;

/**
 * Main JavaFX application for Fractaliz3r.
 * Orchestrates the UI components and render loop.
 */
public class FractalizerApp extends Application {

    // Core components
    private FractalizerController controller;
    private NavigationController navigation;

    // UI components
    private ImageView imageView;
    private ProgressBar progressBar;
    private Label statusLabel;
    private StackPane imageContainer;

    // Panels
    private FractalPanel fractalPanel;
    private LightingPanel lightingPanel;
    private QualityPanel qualityPanel;
    private ExportPanel exportPanel;

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

        // Initialize params
        AbstractFractalParams initialParams = new MandelbulbParams();
        controller.setParams(initialParams);

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
        TabPane controlTabs = createControlTabs(initialParams);
        controlTabs.setPrefWidth(320);
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

        // Start render loop
        startRenderLoop();

        // Initial render
        renderPreview();
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

        // Quality tab
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

        tabPane.getTabs().addAll(fractalTab, lightingTab, qualityTab, exportTab);

        return tabPane;
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

    private void startRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                // Process keyboard input
                if (navigation.processKeyboardInput()) {
                    needsRender = true;
                    fractalPanel.updatePositionLabel();
                }

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

    private void requestRender() {
        needsRender = true;
    }

    private void resetCamera() {
        fractalPanel.getCamera().reset();
        needsRender = true;
        fractalPanel.updatePositionLabel();
    }

    private void renderPreview() {
        exportPanel.updateOutputSize();
        progressBar.setProgress(0);

        if (autoFullQuality) {
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
        exportPanel.updateOutputSize();
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
