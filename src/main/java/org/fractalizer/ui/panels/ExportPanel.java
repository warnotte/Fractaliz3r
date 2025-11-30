package org.fractalizer.ui.panels;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.fractalizer.animation.Timeline;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.RenderController;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel for export settings: export size and export actions.
 * Handles both single image export and animation sequence export.
 * Export size is independent from viewport size (which auto-adapts to window).
 */
public class ExportPanel extends ScrollPane {

    private final RenderController controller;
    private final Runnable renderFullCallback;
    private final Consumer<Double> progressCallback;
    private final Consumer<String> statusCallback;

    // Animation support
    private Supplier<Timeline> timelineSupplier;
    private AnimationExportCallback animationExportCallback;
    private Consumer<Image> imageUpdateCallback;
    private Consumer<Boolean> exportStateCallback;

    @FunctionalInterface
    public interface AnimationExportCallback {
        void exportFrame(File file, int width, int height, int samples);
    }

    // UI Components
    private TextField widthField;
    private TextField heightField;
    private Label viewportInfoLabel;

    // Animation export UI
    private ProgressBar frameProgress;
    private ProgressBar totalProgress;
    private Label frameProgressLabel;
    private Label animExportStatusLabel;
    private Button exportAnimButton;
    private Button cancelAnimButton;
    private Spinner<Integer> exportSamplesSpinner;
    private CheckBox createMP4Checkbox;
    private Spinner<Integer> crfSpinner;
    private Label ffmpegStatusLabel;

    private volatile boolean exportCancelled;
    private volatile boolean exporting;

    public ExportPanel(RenderController controller,
                       Runnable renderFullCallback,
                       Consumer<Double> progressCallback,
                       Consumer<String> statusCallback) {
        this.controller = controller;
        this.renderFullCallback = renderFullCallback;
        this.progressCallback = progressCallback;
        this.statusCallback = statusCallback;

        setContent(createContent());
        setFitToWidth(true);

        // Initialize export size
        updateExportSize();
    }

    /**
     * Set the timeline supplier for animation export.
     */
    public void setTimelineSupplier(Supplier<Timeline> supplier) {
        this.timelineSupplier = supplier;
    }

    /**
     * Set the callback for exporting animation frames.
     */
    public void setAnimationExportCallback(AnimationExportCallback callback) {
        this.animationExportCallback = callback;
    }

    /**
     * Set the callback for updating the image view during animation export.
     */
    public void setImageUpdateCallback(Consumer<Image> callback) {
        this.imageUpdateCallback = callback;
    }

    /**
     * Set the callback for notifying export state changes.
     * Called with true when export starts, false when it ends.
     */
    public void setExportStateCallback(Consumer<Boolean> callback) {
        this.exportStateCallback = callback;
    }

    private VBox createContent() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(10));

        // === Export Size Section ===
        panel.getChildren().add(createSizeSection());

        // === Single Image Export Section ===
        panel.getChildren().add(createImageExportSection());

        // === Animation Export Section ===
        panel.getChildren().add(createAnimationExportSection());

        return panel;
    }

    private TitledPane createSizeSection() {
        VBox box = new VBox(8);

        // Viewport info (read-only)
        viewportInfoLabel = new Label("Viewport: --");
        viewportInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Preset sizes
        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll("1920x1080 (Full HD)", "3840x2160 (4K)", "7680x4320 (8K)", "Custom");
        presetCombo.setValue("1920x1080 (Full HD)");
        presetCombo.setMaxWidth(Double.MAX_VALUE);
        presetCombo.setOnAction(e -> {
            String preset = presetCombo.getValue();
            if (preset.startsWith("1920")) {
                widthField.setText("1920");
                heightField.setText("1080");
            } else if (preset.startsWith("3840")) {
                widthField.setText("3840");
                heightField.setText("2160");
            } else if (preset.startsWith("7680")) {
                widthField.setText("7680");
                heightField.setText("4320");
            }
            updateExportSize();
        });

        HBox sizeBox = new HBox(5);
        sizeBox.setAlignment(Pos.CENTER_LEFT);
        widthField = new TextField("1920");
        widthField.setPrefWidth(80);
        heightField = new TextField("1080");
        heightField.setPrefWidth(80);
        sizeBox.getChildren().addAll(widthField, new Label("x"), heightField);

        // Apply size button
        Button applySizeBtn = new Button("Apply Size");
        applySizeBtn.setOnAction(e -> updateExportSize());
        applySizeBtn.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(
            viewportInfoLabel,
            new Label("Preset:"),
            presetCombo,
            new Label("Custom:"),
            sizeBox,
            applySizeBtn
        );

        TitledPane pane = new TitledPane("Export Size", box);
        pane.setExpanded(true);
        return pane;
    }

    private TitledPane createImageExportSection() {
        VBox box = new VBox(8);

        // Buttons
        Button renderBtn = new Button("Render Full Quality (Space)");
        renderBtn.setOnAction(e -> renderFullCallback.run());
        renderBtn.setMaxWidth(Double.MAX_VALUE);
        renderBtn.setStyle("-fx-font-weight: bold;");

        Button exportBtn = new Button("Export PNG...");
        exportBtn.setOnAction(e -> exportImage());
        exportBtn.setMaxWidth(Double.MAX_VALUE);

        // Info
        Label infoLabel = new Label(
            "Tips:\n" +
            "- Preview uses viewport size (auto)\n" +
            "- Export uses the size above\n" +
            "- 4K/8K export may take minutes"
        );
        infoLabel.setStyle("-fx-font-size: 11px;");
        infoLabel.setWrapText(true);

        box.getChildren().addAll(renderBtn, exportBtn, infoLabel);

        TitledPane pane = new TitledPane("Image", box);
        pane.setExpanded(true);
        return pane;
    }

    private TitledPane createAnimationExportSection() {
        VBox box = new VBox(8);

        // Animation info
        Label animInfoLabel = new Label("No animation");
        animInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Update info when timeline changes
        // This will be called later via a method

        // Samples per frame
        HBox samplesBox = new HBox(5);
        samplesBox.setAlignment(Pos.CENTER_LEFT);
        samplesBox.getChildren().add(new Label("Samples/frame:"));
        exportSamplesSpinner = new Spinner<>(1, 128, 16, 4);
        exportSamplesSpinner.setPrefWidth(70);
        exportSamplesSpinner.setEditable(true);
        samplesBox.getChildren().add(exportSamplesSpinner);

        // Progress indicators
        frameProgressLabel = new Label("Frame 0/0");
        frameProgressLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        frameProgressLabel.setVisible(false);

        frameProgress = new ProgressBar(0);
        frameProgress.setPrefWidth(Double.MAX_VALUE);
        frameProgress.setVisible(false);

        totalProgress = new ProgressBar(0);
        totalProgress.setPrefWidth(Double.MAX_VALUE);
        totalProgress.setVisible(false);

        animExportStatusLabel = new Label("");
        animExportStatusLabel.setStyle("-fx-font-size: 10px;");
        animExportStatusLabel.setWrapText(true);

        // MP4 options
        HBox mp4Box = new HBox(8);
        mp4Box.setAlignment(Pos.CENTER_LEFT);

        createMP4Checkbox = new CheckBox("Create MP4");
        createMP4Checkbox.setSelected(true);

        HBox crfBox = new HBox(5);
        crfBox.setAlignment(Pos.CENTER_LEFT);
        crfBox.getChildren().add(new Label("CRF:"));
        crfSpinner = new Spinner<>(0, 51, 23, 1);
        crfSpinner.setPrefWidth(60);
        crfSpinner.setEditable(true);
        crfSpinner.setTooltip(new Tooltip("Quality: 0=lossless, 18=high, 23=medium, 28=low"));
        crfBox.getChildren().add(crfSpinner);

        mp4Box.getChildren().addAll(createMP4Checkbox, crfBox);

        // FFmpeg status
        ffmpegStatusLabel = new Label();
        updateFFmpegStatus();
        ffmpegStatusLabel.setStyle("-fx-font-size: 10px;");

        // Buttons
        HBox buttonBox = new HBox(5);
        buttonBox.setAlignment(Pos.CENTER);

        exportAnimButton = new Button("Export Animation...");
        exportAnimButton.setOnAction(e -> startAnimationExport());

        cancelAnimButton = new Button("Cancel");
        cancelAnimButton.setVisible(false);
        cancelAnimButton.setOnAction(e -> cancelAnimationExport());

        buttonBox.getChildren().addAll(exportAnimButton, cancelAnimButton);

        box.getChildren().addAll(
            animInfoLabel,
            samplesBox,
            frameProgressLabel,
            frameProgress,
            totalProgress,
            animExportStatusLabel,
            new Separator(),
            mp4Box,
            ffmpegStatusLabel,
            buttonBox
        );

        // Store reference to update animation info
        this.animInfoLabel = animInfoLabel;

        TitledPane pane = new TitledPane("Animation", box);
        pane.setExpanded(false);
        return pane;
    }

    private Label animInfoLabel;

    /**
     * Update animation info display.
     */
    public void updateAnimationInfo() {
        if (timelineSupplier == null || animInfoLabel == null) return;

        Timeline timeline = timelineSupplier.get();
        if (timeline == null) {
            animInfoLabel.setText("No animation");
            return;
        }

        int frames = timeline.getTotalFrames();
        double duration = timeline.getDuration();
        int fps = (int) timeline.getFrameRate();
        int w = getOutputWidth();
        int h = getOutputHeight();
        animInfoLabel.setText(String.format("%d frames (%.1fs @ %dfps) at %dx%d", frames, duration, fps, w, h));
    }

    private void updateFFmpegStatus() {
        if (FFmpegExporter.isFFmpegAvailable()) {
            String version = FFmpegExporter.getFFmpegVersion();
            ffmpegStatusLabel.setText("FFmpeg: " + version);
            ffmpegStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: green;");
            createMP4Checkbox.setDisable(false);
        } else {
            ffmpegStatusLabel.setText("FFmpeg not found in PATH");
            ffmpegStatusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: red;");
            createMP4Checkbox.setSelected(false);
            createMP4Checkbox.setDisable(true);
        }
    }

    // ========================================================================
    // Image Export
    // ========================================================================

    private void exportImage() {
        updateExportSize();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Image");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fileChooser.setInitialFileName("fractal.png");

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            statusCallback.accept("Exporting...");
            progressCallback.accept(0.0);

            controller.exportToPNG(file, progressCallback::accept)
                .thenRun(() -> Platform.runLater(() ->
                    statusCallback.accept("Exported to: " + file.getName())
                ))
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Export failed");
                        alert.setContentText(e.getMessage());
                        alert.showAndWait();
                    });
                    return null;
                });
        }
    }

    // ========================================================================
    // Animation Export
    // ========================================================================

    private void startAnimationExport() {
        if (timelineSupplier == null || animationExportCallback == null) {
            showError("Export not configured", "Animation export is not configured.");
            return;
        }

        Timeline timeline = timelineSupplier.get();
        if (timeline == null) {
            showError("No Animation", "No timeline available.");
            return;
        }

        // Check if there are keyframes
        boolean hasKeyframes = false;
        for (String trackName : timeline.getTrackNames()) {
            if (timeline.getTrack(trackName).hasKeyframes()) {
                hasKeyframes = true;
                break;
            }
        }

        if (!hasKeyframes) {
            showError("No Keyframes", "Please add at least 2 keyframes in the Animation tab before exporting.");
            return;
        }

        // Choose output directory
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File outputDir = chooser.showDialog(getScene().getWindow());
        if (outputDir == null) return;

        // Create subdirectory with timestamp
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        File exportDir = new File(outputDir, "fractal_animation_" + timestamp);
        if (!exportDir.mkdirs()) {
            showError("Directory Error", "Could not create output directory: " + exportDir.getAbsolutePath());
            return;
        }

        // Get export parameters
        updateExportSize();
        int exportWidth = getOutputWidth();
        int exportHeight = getOutputHeight();
        int totalFrames = timeline.getTotalFrames();
        int exportSamples = exportSamplesSpinner.getValue();
        boolean shouldCreateMP4 = createMP4Checkbox.isSelected() && FFmpegExporter.isFFmpegAvailable();
        int crf = crfSpinner.getValue();
        int fps = (int) timeline.getFrameRate();

        // Start export
        exporting = true;
        exportCancelled = false;

        // Notify that export is starting (blocks render loop)
        if (exportStateCallback != null) {
            exportStateCallback.accept(true);
        }

        exportAnimButton.setDisable(true);
        cancelAnimButton.setVisible(true);
        frameProgress.setVisible(true);
        frameProgress.setProgress(0);
        totalProgress.setVisible(true);
        totalProgress.setProgress(0);
        frameProgressLabel.setVisible(true);

        System.out.println("[AnimExport] Starting animation export: " + totalFrames + " frames at " + exportWidth + "x" + exportHeight);

        Thread exportThread = new Thread(() -> {
            try {
                for (int frame = 0; frame < totalFrames && !exportCancelled; frame++) {
                    final int currentFrame = frame;
                    double time = frame / timeline.getFrameRate();

                    // Apply timeline values on JavaFX thread and wait
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            timeline.setCurrentTime(time);
                            // Params are applied via AnimationPanel's listener
                            frameProgressLabel.setText(String.format("Frame %d / %d", currentFrame + 1, totalFrames));
                            animExportStatusLabel.setText("Rendering frames...");
                            frameProgress.setProgress(0);
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await();

                    // Small delay to ensure params are applied
                    Thread.sleep(50);

                    // Create frame file
                    File frameFile = new File(exportDir, String.format("frame_%05d.png", currentFrame));

                    // Render and save frame (synchronous call on JavaFX thread)
                    java.util.concurrent.CountDownLatch renderLatch = new java.util.concurrent.CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            animationExportCallback.exportFrame(frameFile, exportWidth, exportHeight, exportSamples);
                            // Update image view for visual feedback
                            // This is handled in the callback now
                        } finally {
                            renderLatch.countDown();
                        }
                    });
                    renderLatch.await();

                    // Update total progress
                    final double progress = (double) (frame + 1) / totalFrames;
                    Platform.runLater(() -> {
                        totalProgress.setProgress(progress);
                        frameProgress.setProgress(1.0);
                    });
                }

                // PNG export done - now create MP4 if requested
                if (!exportCancelled && shouldCreateMP4) {
                    Platform.runLater(() -> {
                        animExportStatusLabel.setText("Creating MP4 video...");
                        frameProgressLabel.setText("Encoding...");
                        frameProgress.setProgress(-1); // Indeterminate
                    });

                    FFmpegExporter.ExportResult result = FFmpegExporter.createMP4InPlace(
                            exportDir, fps, crf,
                            progress -> Platform.runLater(() -> frameProgress.setProgress(progress))
                    );

                    final int finalTotalFrames = totalFrames;
                    Platform.runLater(() -> {
                        if (result.success) {
                            animExportStatusLabel.setText("Export complete!\n" + finalTotalFrames + " frames + MP4:\n" + result.outputFile.getAbsolutePath());
                        } else {
                            animExportStatusLabel.setText("Frames exported. MP4 failed:\n" + result.message);
                        }
                        finishAnimationExport();
                    });
                } else {
                    // Done (PNG only)
                    final int finalTotalFrames = totalFrames;
                    Platform.runLater(() -> {
                        if (exportCancelled) {
                            animExportStatusLabel.setText("Export cancelled.");
                        } else {
                            animExportStatusLabel.setText("Export complete! " + finalTotalFrames + " frames saved to:\n" + exportDir.getAbsolutePath());
                        }
                        finishAnimationExport();
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    showError("Export Error", e.getMessage());
                    finishAnimationExport();
                });
            }
        });

        exportThread.setDaemon(true);
        exportThread.start();
    }

    private void cancelAnimationExport() {
        exportCancelled = true;
    }

    private void finishAnimationExport() {
        exporting = false;
        exportAnimButton.setDisable(false);
        cancelAnimButton.setVisible(false);
        frameProgress.setVisible(false);
        totalProgress.setVisible(false);
        frameProgressLabel.setVisible(false);

        // Notify that export is finished (unblocks render loop)
        if (exportStateCallback != null) {
            exportStateCallback.accept(false);
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Update the export size in the controller.
     */
    public void updateExportSize() {
        try {
            int width = Integer.parseInt(widthField.getText());
            int height = Integer.parseInt(heightField.getText());
            controller.setExportSize(width, height);
            updateAnimationInfo();
        } catch (NumberFormatException e) {
            // Keep current size
        }
    }

    /**
     * Update the viewport info label (called when viewport resizes).
     */
    public void updateViewportInfo() {
        int w = controller.getViewportWidth();
        int h = controller.getViewportHeight();
        viewportInfoLabel.setText(String.format("Viewport: %dx%d (auto)", w, h));
    }

    public int getOutputWidth() {
        try {
            return Integer.parseInt(widthField.getText());
        } catch (NumberFormatException e) {
            return 1920;
        }
    }

    public int getOutputHeight() {
        try {
            return Integer.parseInt(heightField.getText());
        } catch (NumberFormatException e) {
            return 1080;
        }
    }

    public boolean isExporting() {
        return exporting;
    }
}
