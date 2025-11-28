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
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.MandelbulbParams;
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

    // Navigation state
    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private Camera camera;
    private MandelbulbParams params;

    // Mouse drag state
    private boolean isDragging = false;
    private double dragStartX, dragStartY;

    // Rendering state
    private boolean needsRender = true;
    private long lastRenderTime = 0;
    private static final long RENDER_DELAY_MS = 100;

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

        // Right: Controls panel
        VBox controlPanel = createControlPanel();
        root.setRight(controlPanel);

        // Bottom: Status bar
        HBox statusBar = createStatusBar();
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1200, 700);

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

    private void setupKeyboardControls(Scene scene) {
        // Use event filter to capture keys before UI components
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            // Track navigation keys and consume them to prevent UI interaction
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
            }
            if (e.getCode() == KeyCode.E) {
                camera.roll(1);
                needsRender = true;
            }
        });

        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> {
            pressedKeys.remove(e.getCode());
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

    private VBox createControlPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(300);
        panel.setStyle("-fx-background-color: #f0f0f0;");

        // Fractal type selector
        Label typeLabel = new Label("Fractal Type:");
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("Mandelbulb", "Mandelbox (TODO)", "Julia 3D (TODO)");
        typeCombo.setValue("Mandelbulb");

        // Power control
        Label powerLabel = new Label("Power: 8.0");
        powerSlider = new Slider(2, 16, 8);
        powerSlider.setShowTickLabels(true);
        powerSlider.setShowTickMarks(true);
        powerSlider.valueProperty().addListener((obs, old, val) -> {
            powerLabel.setText(String.format("Power: %.1f", val.doubleValue()));
            params.power(val.floatValue());
            needsRender = true;
        });

        // Iterations
        Label iterLabel = new Label("Iterations: 15");
        iterationsSlider = new Slider(5, 30, 15);
        iterationsSlider.setShowTickLabels(true);
        iterationsSlider.valueProperty().addListener((obs, old, val) -> {
            iterLabel.setText(String.format("Iterations: %d", val.intValue()));
            params.iterations(val.intValue());
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

        // Output size
        Label sizeLabel = new Label("Output Size:");
        HBox sizeBox = new HBox(5);
        widthField = new TextField("1920");
        widthField.setPrefWidth(70);
        heightField = new TextField("1080");
        heightField.setPrefWidth(70);
        sizeBox.getChildren().addAll(widthField, new Label("x"), heightField);

        // Preset sizes
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("1920x1080 (Full HD)", "3840x2160 (4K)", "7680x4320 (8K)", "Custom");
        presetCombo.setValue("1920x1080 (Full HD)");
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

        // Buttons
        Button renderBtn = new Button("Render Full (Space)");
        renderBtn.setOnAction(e -> renderFull());
        renderBtn.setPrefWidth(270);

        Button exportBtn = new Button("Export PNG...");
        exportBtn.setOnAction(e -> exportImage());
        exportBtn.setPrefWidth(270);

        Button resetBtn = new Button("Reset Camera (R)");
        resetBtn.setOnAction(e -> {
            camera.reset();
            needsRender = true;
            updatePositionLabel();
        });
        resetBtn.setPrefWidth(270);

        panel.getChildren().addAll(
            typeLabel, typeCombo,
            new Separator(),
            powerLabel, powerSlider,
            iterLabel, iterationsSlider,
            speedLabel, speedSlider,
            new Separator(),
            positionLabel,
            new Separator(),
            navLabel, helpLabel,
            new Separator(),
            sizeLabel, presetCombo, sizeBox,
            new Separator(),
            renderBtn,
            exportBtn,
            resetBtn
        );

        return panel;
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

        controller.renderPreview(
            image -> imageView.setImage(image),
            progress -> progressBar.setProgress(progress)
        );
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
