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
import org.fractalizer.export.GlbExporter;
import org.fractalizer.export.MarchingCubes;
import org.fractalizer.export.ObjExporter;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.ExportProgressDialog;
import org.fractalizer.ui.RenderController;
import org.fractalizer.ui.components.EnhancedSlider;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
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
        Image exportFrame(File file, int width, int height, int samples,
                          Consumer<Double> onProgress, Supplier<Boolean> cancelCheck);
    }

    @FunctionalInterface
    public interface MotionBlurExportCallback {
        Image exportFrameWithMotionBlur(File file, int width, int height, int samples,
                                        double frameTime, double fps, float shutterAngle,
                                        Consumer<Double> onProgress, Supplier<Boolean> cancelCheck);
    }

    // Motion blur callback
    private MotionBlurExportCallback motionBlurExportCallback;

    // Prepare frame callback (applies timeline params on FX thread before render)
    private Runnable prepareFrameCallback;

    // UI Components
    private TextField widthField;
    private TextField heightField;
    private Label viewportInfoLabel;
    private Spinner<Integer> imageExportSamplesSpinner;

    // Image export UI
    private Button exportImageBtn;
    private volatile boolean imageExportCancelled;

    // Animation export UI
    private Button exportAnimButton;
    private Spinner<Integer> exportSamplesSpinner;
    private Spinner<Integer> motionBlurSpinner;  // Shutter angle 0-360
    private CheckBox createMP4Checkbox;
    private Spinner<Integer> crfSpinner;
    private Label ffmpegStatusLabel;

    private volatile boolean exportCancelled;
    private volatile boolean exportPaused;
    private volatile boolean exporting;

    // AOV export
    private CheckBox exportDepthCheck;
    private CheckBox exportNormalCheck;

    // Mesh export UI
    private ComboBox<String> meshFormatCombo;
    private EnhancedSlider meshResolutionSlider;
    private EnhancedSlider meshBoundsSlider;
    private Button exportMeshBtn;
    private volatile boolean meshExportCancelled;

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

    /**
     * Set the callback for exporting animation frames with motion blur.
     */
    public void setMotionBlurExportCallback(MotionBlurExportCallback callback) {
        this.motionBlurExportCallback = callback;
    }

    /**
     * Set the callback to prepare/apply params on the FX thread before each frame render.
     */
    public void setPrepareFrameCallback(Runnable callback) {
        this.prepareFrameCallback = callback;
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

        // === 3D Mesh Export Section ===
        panel.getChildren().add(createMeshExportSection());

        return panel;
    }

    private TitledPane createSizeSection() {
        VBox box = new VBox(8);

        // Viewport info (read-only)
        viewportInfoLabel = new Label("Viewport: --");
        viewportInfoLabel.getStyleClass().add("info-label");

        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(
            "1920x1080 (Full HD)",
            "3840x2160 (4K)",
            "7680x4320 (8K)",
            "15360x8640 (16K)",
            "2048x1024 (360\u00B0 2K)",
            "4096x2048 (360\u00B0 4K)",
            "8192x4096 (360\u00B0 8K)",
            "Custom"
        );
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
            } else if (preset.startsWith("15360")) {
                widthField.setText("15360");
                heightField.setText("8640");
            } else if (preset.startsWith("2048")) {
                widthField.setText("2048");
                heightField.setText("1024");
            } else if (preset.startsWith("4096")) {
                widthField.setText("4096");
                heightField.setText("2048");
            } else if (preset.startsWith("8192")) {
                widthField.setText("8192");
                heightField.setText("4096");
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

        // Export samples
        HBox imageSamplesBox = new HBox(5);
        imageSamplesBox.setAlignment(Pos.CENTER_LEFT);
        imageSamplesBox.getChildren().add(new Label("Samples:"));
        imageExportSamplesSpinner = new Spinner<>(16, 1024, 128, 16);
        imageExportSamplesSpinner.setPrefWidth(80);
        imageExportSamplesSpinner.setEditable(true);
        imageExportSamplesSpinner.setTooltip(new Tooltip("Number of render iterations for image export"));
        imageSamplesBox.getChildren().add(imageExportSamplesSpinner);

        // Buttons
        Button renderBtn = new Button("Render Full Quality (Space)");
        renderBtn.setOnAction(e -> renderFullCallback.run());
        renderBtn.setMaxWidth(Double.MAX_VALUE);
        renderBtn.getStyleClass().add("bold-label");

        exportImageBtn = new Button("Export Image...");
        exportImageBtn.setOnAction(e -> exportImage());
        exportImageBtn.setMaxWidth(Double.MAX_VALUE);

        // AOV Passes
        exportDepthCheck = new CheckBox("Depth Map");
        exportDepthCheck.setTooltip(new Tooltip("Export 16-bit depth pass alongside the beauty image"));
        exportNormalCheck = new CheckBox("Normal Map");
        exportNormalCheck.setTooltip(new Tooltip("Export world-space normal pass alongside the beauty image"));
        HBox aovBox = new HBox(8, new Label("AOV:"), exportDepthCheck, exportNormalCheck);
        aovBox.setAlignment(Pos.CENTER_LEFT);

        // Info
        Label infoLabel = new Label(
            "Tips:\n" +
            "- Preview uses viewport size (auto)\n" +
            "- Export uses the size above\n" +
            "- 4K/8K export may take minutes"
        );
        infoLabel.getStyleClass().add("small-label");
        infoLabel.setWrapText(true);

        box.getChildren().addAll(imageSamplesBox, renderBtn, exportImageBtn, aovBox, infoLabel);

        TitledPane pane = new TitledPane("Image", box);
        pane.setExpanded(true);
        return pane;
    }

    private TitledPane createAnimationExportSection() {
        VBox box = new VBox(8);

        // Animation info
        Label animInfoLabel = new Label("No animation");
        animInfoLabel.getStyleClass().add("info-label");

        // Samples per frame
        HBox samplesBox = new HBox(5);
        samplesBox.setAlignment(Pos.CENTER_LEFT);
        samplesBox.getChildren().add(new Label("Samples/frame:"));
        exportSamplesSpinner = new Spinner<>(1, 128, 16, 4);
        exportSamplesSpinner.setPrefWidth(70);
        exportSamplesSpinner.setEditable(true);
        samplesBox.getChildren().add(exportSamplesSpinner);

        // Motion blur (shutter angle)
        HBox motionBlurBox = new HBox(5);
        motionBlurBox.setAlignment(Pos.CENTER_LEFT);
        motionBlurBox.getChildren().add(new Label("Motion blur:"));
        motionBlurSpinner = new Spinner<>(0, 360, 180, 30);
        motionBlurSpinner.setPrefWidth(70);
        motionBlurSpinner.setEditable(true);
        motionBlurSpinner.setTooltip(new Tooltip("Shutter angle: 0=none, 180=cinematic, 360=full frame blur"));
        motionBlurBox.getChildren().addAll(motionBlurSpinner, new Label("\u00B0"));

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

        // Export button
        exportAnimButton = new Button("Export Animation...");
        exportAnimButton.setOnAction(e -> startAnimationExport());
        exportAnimButton.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(
            animInfoLabel,
            samplesBox,
            motionBlurBox,
            new Separator(),
            mp4Box,
            ffmpegStatusLabel,
            exportAnimButton
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
        ffmpegStatusLabel.getStyleClass().removeAll("status-ok", "status-error");
        if (FFmpegExporter.isFFmpegAvailable()) {
            String version = FFmpegExporter.getFFmpegVersion();
            ffmpegStatusLabel.setText("FFmpeg: " + version);
            ffmpegStatusLabel.getStyleClass().add("status-ok");
            createMP4Checkbox.setDisable(false);
        } else {
            ffmpegStatusLabel.setText("FFmpeg not found in PATH");
            ffmpegStatusLabel.getStyleClass().add("status-error");
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
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("PNG Image", "*.png"),
            new FileChooser.ExtensionFilter("JPEG Image", "*.jpg", "*.jpeg")
        );
        fileChooser.setInitialFileName("fractal.png");

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            imageExportCancelled = false;
            exportImageBtn.setDisable(true);

            ExportProgressDialog dialog = new ExportProgressDialog(getScene().getWindow(), "Image Export");
            dialog.setAnimationMode(false);
            dialog.setOnCancelRequested(() -> imageExportCancelled = true);

            int samples = imageExportSamplesSpinner.getValue();
            long startTime = System.currentTimeMillis();

            // Note: this callback is already invoked on the FX thread by the controller
            // (via Platform.runLater), so no need to wrap in another Platform.runLater
            Consumer<Double> enrichedProgress = progress -> {
                int currentSample = (int) Math.round(progress * samples);
                long elapsed = System.currentTimeMillis() - startTime;
                String eta;
                if (progress > 0 && progress < 1.0) {
                    long estimatedTotal = (long) (elapsed / progress);
                    long remaining = estimatedTotal - elapsed;
                    eta = "~" + formatDuration(remaining) + " remaining";
                } else if (progress >= 1.0) {
                    eta = "finishing...";
                } else {
                    eta = "calculating...";
                }
                dialog.updateFrameProgress(progress,
                    String.format("Sample %d / %d", currentSample, samples));
                dialog.updateStatus(formatDuration(elapsed) + " elapsed \u2014 " + eta);
            };

            controller.exportToPNG(file, samples, enrichedProgress, () -> imageExportCancelled)
                .thenRun(() -> {
                    // Export AOV passes (fast, 1 sample each)
                    if (!imageExportCancelled) {
                        String baseName = file.getName().replaceFirst("\\.[^.]+$", "");
                        String dir = file.getParent();
                        if (exportDepthCheck.isSelected()) {
                            controller.exportAOV(new File(dir, baseName + "_depth.png"), 2);
                        }
                        if (exportNormalCheck.isSelected()) {
                            controller.exportAOV(new File(dir, baseName + "_normal.png"), 1);
                        }
                    }
                })
                .thenRun(() -> Platform.runLater(() -> {
                    if (imageExportCancelled) {
                        dialog.showCancelled("Export cancelled");
                        statusCallback.accept("Export cancelled");
                    } else {
                        long totalElapsed = System.currentTimeMillis() - startTime;
                        String aovInfo = "";
                        if (exportDepthCheck.isSelected() || exportNormalCheck.isSelected()) {
                            aovInfo = " + AOV passes";
                        }
                        dialog.showSuccess("Exported to: " + file.getName() + aovInfo + " in " + formatDuration(totalElapsed));
                        statusCallback.accept("Exported: " + file.getName());
                        openFile(file);
                    }
                    exportImageBtn.setDisable(false);
                }))
                .exceptionally(e -> {
                    Platform.runLater(() -> {
                        dialog.showCancelled("Export failed: " + e.getMessage());
                        statusCallback.accept("Export failed");
                        exportImageBtn.setDisable(false);
                    });
                    return null;
                });

            dialog.show();
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
        int shutterAngle = motionBlurSpinner.getValue();
        boolean shouldCreateMP4 = createMP4Checkbox.isSelected() && FFmpegExporter.isFFmpegAvailable();
        int crf = crfSpinner.getValue();
        int fps = (int) timeline.getFrameRate();
        boolean useMotionBlur = shutterAngle > 0 && motionBlurExportCallback != null;

        // Detect 360 mode from current params
        boolean is360 = false;
        if (controller.getParams() instanceof org.fractalizer.fractals.AbstractFractalParams afp) {
            is360 = (afp.getProjectionMode() == org.fractalizer.fractals.AbstractFractalParams.PROJECTION_360_EQUIRECTANGULAR);
        }
        final boolean finalIs360 = is360;

        // Start export
        exporting = true;
        exportCancelled = false;
        exportPaused = false;

        // Notify that export is starting (blocks render loop)
        if (exportStateCallback != null) {
            exportStateCallback.accept(true);
        }

        exportAnimButton.setDisable(true);

        // Create and show progress dialog
        ExportProgressDialog dialog = new ExportProgressDialog(getScene().getWindow(), "Animation Export");
        dialog.setAnimationMode(true);
        dialog.setOnCancelRequested(() -> {
            exportCancelled = true;
            exportPaused = false;
        });
        dialog.setOnPauseToggled(paused -> exportPaused = paused);

        String motionBlurInfo = useMotionBlur ? ", motion blur " + shutterAngle + "\u00B0" : "";
        System.out.println("[AnimExport] Starting: " + totalFrames + " frames at " + exportWidth + "x" + exportHeight
                + ", " + exportSamples + " samples/frame" + motionBlurInfo);

        long exportStartTime = System.currentTimeMillis();

        Thread exportThread = new Thread(() -> {
            int renderedFrames = 0;
            try {
                for (int frame = 0; frame < totalFrames && !exportCancelled; frame++) {
                    waitWhileAnimationPaused(dialog, renderedFrames, totalFrames, exportStartTime);
                    if (exportCancelled) {
                        break;
                    }

                    final int currentFrame = frame;
                    double time = frame / timeline.getFrameRate();
                    long frameStartTime = System.currentTimeMillis();

                    // Step 1: Apply params on FX thread (quick, <1ms)
                    java.util.concurrent.CountDownLatch prepareLatch = new java.util.concurrent.CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            timeline.setCurrentTime(time);
                            if (prepareFrameCallback != null) prepareFrameCallback.run();
                            dialog.updateFrameProgress(0,
                                String.format("Frame %d / %d \u2014 Sample 0/%d", currentFrame + 1, totalFrames, exportSamples));
                        } finally {
                            prepareLatch.countDown();
                        }
                    });
                    prepareLatch.await();

                    // Per-sample progress callback
                    final int fCurrentFrame = currentFrame;
                    Consumer<Double> sampleProgress = progress -> {
                        int currentSample = (int) Math.round(progress * exportSamples);
                        Platform.runLater(() -> {
                            dialog.updateFrameProgress(progress,
                                String.format("Frame %d / %d \u2014 Sample %d/%d",
                                    fCurrentFrame + 1, totalFrames, currentSample, exportSamples));
                        });
                    };

                    // Cancel check supplier
                    Supplier<Boolean> cancelSupplier = () -> exportCancelled;

                    // Step 2: Render on background thread (GL work delegates to GL thread internally)
                    File frameFile = new File(exportDir, String.format("frame_%05d.png", currentFrame));
                    final double frameTime = time;
                    Image frameImage;
                    if (useMotionBlur) {
                        frameImage = motionBlurExportCallback.exportFrameWithMotionBlur(
                            frameFile, exportWidth, exportHeight, exportSamples,
                            frameTime, fps, shutterAngle, sampleProgress, cancelSupplier);
                    } else {
                        frameImage = animationExportCallback.exportFrame(frameFile, exportWidth, exportHeight, exportSamples,
                            sampleProgress, cancelSupplier);
                    }

                    // Export AOV passes for this frame (fast, 1 sample each)
                    if (!exportCancelled) {
                        if (exportDepthCheck.isSelected()) {
                            File depthFile = new File(exportDir, String.format("frame_%05d_depth.png", currentFrame));
                            controller.exportAOV(depthFile, 2);
                        }
                        if (exportNormalCheck.isSelected()) {
                            File normalFile = new File(exportDir, String.format("frame_%05d_normal.png", currentFrame));
                            controller.exportAOV(normalFile, 1);
                        }
                    }

                    // Update preview in dialog
                    if (frameImage != null) {
                        Platform.runLater(() -> dialog.updatePreview(frameImage));
                    }

                    renderedFrames++;

                    // Check if cancelled mid-frame
                    if (exportCancelled) {
                        System.out.println("[AnimExport] Frame " + (currentFrame + 1) + "/" + totalFrames + " \u2014 cancelled by user");
                        break;
                    }

                    // Step 3: Update progress on FX thread + console log
                    final double progress = (double) (currentFrame + 1) / totalFrames;
                    long elapsed = System.currentTimeMillis() - exportStartTime;
                    long frameElapsed = System.currentTimeMillis() - frameStartTime;
                    long estimatedTotal = (long) (elapsed / progress);
                    long remaining = estimatedTotal - elapsed;
                    final String etaText = formatDuration(elapsed) + " elapsed \u2014 ~" + formatDuration(remaining) + " remaining";

                    System.out.printf("[AnimExport] Frame %d/%d (%.1f%%) \u2014 %.1fs/frame \u2014 ETA %s%n",
                            currentFrame + 1, totalFrames, progress * 100,
                            frameElapsed / 1000.0, formatDuration(remaining));

                    final int fCurrentFrame2 = currentFrame;
                    Platform.runLater(() -> {
                        dialog.updateFrameProgress(1.0,
                            String.format("Frame %d / %d \u2014 done", fCurrentFrame2 + 1, totalFrames));
                        dialog.updateTotalProgress(progress,
                            String.format("Total: %d / %d frames", fCurrentFrame2 + 1, totalFrames));
                        dialog.updateStatus(etaText);
                    });
                }

                // Summary log
                long totalElapsed = System.currentTimeMillis() - exportStartTime;
                if (exportCancelled) {
                    System.out.println("[AnimExport] Cancelled after " + renderedFrames + "/" + totalFrames
                            + " frames in " + formatDuration(totalElapsed));
                } else {
                    double avgFrame = renderedFrames > 0 ? (totalElapsed / 1000.0) / renderedFrames : 0;
                    System.out.printf("[AnimExport] Rendered %d/%d frames in %s (%.1fs avg/frame)%n",
                            renderedFrames, totalFrames, formatDuration(totalElapsed), avgFrame);
                }

                // PNG export done - now create MP4 if requested
                if (!exportCancelled && shouldCreateMP4) {
                    System.out.println("[AnimExport] Creating MP4 from " + renderedFrames + " frames...");
                    exportPaused = false;
                    Platform.runLater(() -> {
                        dialog.setPauseEnabled(false);
                        dialog.setIndeterminate("Encoding MP4...");
                    });

                    FFmpegExporter.ExportResult result = FFmpegExporter.createMP4InPlace(
                            exportDir, fps, crf, finalIs360,
                            progress -> Platform.runLater(() -> dialog.updateFrameProgress(progress, "Encoding MP4..."))
                    );

                    final int finalTotalFrames = totalFrames;
                    final int finalRenderedFrames = renderedFrames;
                    final long finalTotalElapsed = System.currentTimeMillis() - exportStartTime;
                    Platform.runLater(() -> {
                        if (result.success) {
                            System.out.println("[AnimExport] Done: " + finalRenderedFrames + "/" + finalTotalFrames
                                    + " frames in " + formatDuration(finalTotalElapsed) + " \u2014 MP4: " + result.outputFile.getAbsolutePath());
                            dialog.showSuccess(finalTotalFrames + " frames + MP4 in " + formatDuration(finalTotalElapsed)
                                    + "\n" + result.outputFile.getAbsolutePath());

                            // 360 Metadata Warning (only if not already injected by ExifTool)
                            if (finalIs360 && !result.message.contains("360 Metadata Injected")) {
                                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                                alert.setTitle("360\u00B0 Video Export");
                                alert.setHeaderText("Manual Metadata Injection Required");
                                alert.setContentText("ExifTool was not found. To be recognized by YouTube/Facebook, you must inject metadata into the MP4 using the 'Spatial Media Metadata Injector' or install ExifTool.");

                                ButtonType downloadBtn = new ButtonType("Get Injector Tool");
                                alert.getButtonTypes().add(downloadBtn);

                                alert.showAndWait().ifPresent(type -> {
                                    if (type == downloadBtn) {
                                        openUrl("https://github.com/google/spatial-media/releases");
                                    }
                                });
                            }

                            openFile(result.outputFile);
                        } else {
                            dialog.showCancelled("Frames exported. MP4 failed:\n" + result.message);
                            openFile(exportDir);
                        }
                        finishAnimationExport();
                    });
                } else if (exportCancelled && shouldCreateMP4 && renderedFrames > 0) {
                    // Cancelled but create MP4 from rendered frames
                    System.out.println("[AnimExport] Creating MP4 from " + renderedFrames + " frames...");
                    exportPaused = false;
                    Platform.runLater(() -> {
                        dialog.setPauseEnabled(false);
                        dialog.setIndeterminate("Encoding MP4 from rendered frames...");
                    });

                    FFmpegExporter.ExportResult result = FFmpegExporter.createMP4InPlace(
                            exportDir, fps, crf, finalIs360,
                            progress -> Platform.runLater(() -> dialog.updateFrameProgress(progress, "Encoding MP4..."))
                    );

                    final int finalRenderedFrames2 = renderedFrames;
                    final long finalTotalElapsed2 = System.currentTimeMillis() - exportStartTime;
                    Platform.runLater(() -> {
                        if (result.success) {
                            System.out.println("[AnimExport] Done: " + finalRenderedFrames2 + "/" + totalFrames
                                    + " frames in " + formatDuration(finalTotalElapsed2) + " \u2014 MP4: " + result.outputFile.getAbsolutePath());
                            dialog.showCancelled("Cancelled. MP4 created from " + finalRenderedFrames2 + " frames:\n" + result.outputFile.getAbsolutePath());
                            openFile(result.outputFile);
                        } else {
                            dialog.showCancelled("Cancelled. MP4 failed:\n" + result.message);
                            openFile(exportDir);
                        }
                        finishAnimationExport();
                    });
                } else {
                    // Done (PNG only) or cancelled without MP4
                    final int finalTotalFrames = totalFrames;
                    final int finalRenderedFrames = renderedFrames;
                    final long finalTotalElapsed = System.currentTimeMillis() - exportStartTime;
                    Platform.runLater(() -> {
                        if (exportCancelled) {
                            dialog.showCancelled("Cancelled after " + finalRenderedFrames + "/" + finalTotalFrames + " frames.\n" + exportDir.getAbsolutePath());
                        } else {
                            dialog.showSuccess(finalTotalFrames + " frames exported in " + formatDuration(finalTotalElapsed)
                                    + "\n" + exportDir.getAbsolutePath());
                        }
                        openFile(exportDir);
                        finishAnimationExport();
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    dialog.showCancelled("Export error: " + e.getMessage());
                    finishAnimationExport();
                });
            }
        });

        exportThread.setDaemon(true);
        exportThread.start();

        dialog.show();
    }

    private void finishAnimationExport() {
        exporting = false;
        exportPaused = false;
        exportAnimButton.setDisable(false);

        // Notify that export is finished (unblocks render loop)
        if (exportStateCallback != null) {
            exportStateCallback.accept(false);
        }
    }

    private void waitWhileAnimationPaused(ExportProgressDialog dialog, int renderedFrames, int totalFrames, long exportStartTime)
            throws InterruptedException {
        if (!exportPaused || exportCancelled) {
            return;
        }

        long elapsed = System.currentTimeMillis() - exportStartTime;
        final String pausedText = String.format(
                "Paused after %d / %d frames \u2014 %s elapsed",
                renderedFrames, totalFrames, formatDuration(elapsed));
        Platform.runLater(() -> dialog.updateStatus(pausedText));
        System.out.println("[AnimExport] Paused after " + renderedFrames + "/" + totalFrames + " frames");

        while (exportPaused && !exportCancelled) {
            Thread.sleep(120);
        }

        if (!exportCancelled) {
            long resumedElapsed = System.currentTimeMillis() - exportStartTime;
            final String resumedText = formatDuration(resumedElapsed) + " elapsed \u2014 resumed";
            Platform.runLater(() -> dialog.updateStatus(resumedText));
            System.out.println("[AnimExport] Resumed");
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
    // 3D Mesh Export
    // ========================================================================

    private TitledPane createMeshExportSection() {
        VBox box = new VBox(8);

        // Format selector
        HBox formatBox = new HBox(5);
        formatBox.setAlignment(Pos.CENTER_LEFT);
        formatBox.getChildren().add(new Label("Format:"));
        meshFormatCombo = new ComboBox<>();
        meshFormatCombo.getItems().addAll("glTF Binary (.glb)", "Wavefront OBJ (.obj)");
        meshFormatCombo.setValue("glTF Binary (.glb)");
        meshFormatCombo.setMaxWidth(Double.MAX_VALUE);
        formatBox.getChildren().add(meshFormatCombo);

        // Resolution slider
        meshResolutionSlider = new EnhancedSlider("Resolution", 32, 512, 128, true);

        // Bounds slider
        meshBoundsSlider = new EnhancedSlider("Bounds", 1.0, 5.0, 2.5, false);

        // Export button
        exportMeshBtn = new Button("Export 3D Mesh...");
        exportMeshBtn.setOnAction(e -> exportMesh());
        exportMeshBtn.setMaxWidth(Double.MAX_VALUE);

        // Info label
        Label infoLabel = new Label(
            "Extracts fractal geometry as a 3D mesh\n" +
            "using Marching Cubes. Includes vertex\n" +
            "colors from the current palette."
        );
        infoLabel.getStyleClass().add("small-label");
        infoLabel.setWrapText(true);

        box.getChildren().addAll(formatBox, meshResolutionSlider, meshBoundsSlider, exportMeshBtn, infoLabel);

        TitledPane pane = new TitledPane("3D Mesh", box);
        pane.setExpanded(false);
        return pane;
    }

    private void exportMesh() {
        // Reject non-fractal types
        AbstractFractalParams params = null;
        if (controller.getParams() instanceof AbstractFractalParams afp) {
            params = afp;
        }
        if (params == null) {
            showError("Unsupported", "3D mesh export is not available for this scene type.");
            return;
        }
        FractalType type = params.getType();
        if (type == FractalType.TEST_SCENE || type == FractalType.CORNELL_BOX) {
            showError("Unsupported", "3D mesh export is only available for fractal types, not " + type.getDisplayName() + ".");
            return;
        }

        boolean isGlb = meshFormatCombo.getValue().contains("glb");
        String ext = isGlb ? "*.glb" : "*.obj";
        String desc = isGlb ? "glTF Binary" : "Wavefront OBJ";
        String defaultName = isGlb ? "fractal.glb" : "fractal.obj";

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export 3D Mesh");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, ext));
        fileChooser.setInitialFileName(defaultName);

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        int resolution = (int) meshResolutionSlider.getValue();
        float boundsHalf = (float) meshBoundsSlider.getValue();

        meshExportCancelled = false;
        exportMeshBtn.setDisable(true);

        ExportProgressDialog dialog = new ExportProgressDialog(getScene().getWindow(), "3D Mesh Export");
        dialog.setAnimationMode(false);
        dialog.setOnCancelRequested(() -> meshExportCancelled = true);

        long startTime = System.currentTimeMillis();
        final AbstractFractalParams finalParams = params;

        Thread meshThread = new Thread(() -> {
            try {
                MarchingCubes.Mesh mesh = MarchingCubes.extract(
                        finalParams, resolution, boundsHalf,
                        progress -> Platform.runLater(() -> {
                            int zSlice = (int) (progress * resolution);
                            long elapsed = System.currentTimeMillis() - startTime;
                            String eta = progress > 0.01 && progress < 1.0
                                    ? "~" + formatDuration((long)(elapsed / progress) - elapsed) + " remaining"
                                    : progress >= 1.0 ? "finishing..." : "calculating...";
                            dialog.updateFrameProgress(progress,
                                    String.format("Z-slice %d / %d", zSlice, resolution));
                            dialog.updateStatus(formatDuration(elapsed) + " elapsed \u2014 " + eta);
                        }),
                        () -> meshExportCancelled
                );

                if (meshExportCancelled || mesh == null) {
                    Platform.runLater(() -> {
                        dialog.showCancelled("Export cancelled");
                        statusCallback.accept("Mesh export cancelled");
                        exportMeshBtn.setDisable(false);
                    });
                    return;
                }

                // Write file
                if (isGlb) {
                    GlbExporter.export(file, mesh);
                } else {
                    ObjExporter.export(file, mesh);
                }

                long totalElapsed = System.currentTimeMillis() - startTime;
                final int verts = mesh.vertexCount();
                final int tris = mesh.triangleCount();

                Platform.runLater(() -> {
                    dialog.showSuccess(String.format("%,d vertices, %,d triangles in %s\n%s",
                            verts, tris, formatDuration(totalElapsed), file.getName()));
                    statusCallback.accept("Exported: " + file.getName());
                    exportMeshBtn.setDisable(false);
                    openFile(file);
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    dialog.showCancelled("Export failed: " + e.getMessage());
                    statusCallback.accept("Mesh export failed");
                    exportMeshBtn.setDisable(false);
                });
            }
        });

        meshThread.setDaemon(true);
        meshThread.start();

        dialog.show();
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

    /**
     * Format a duration in milliseconds as a human-readable string.
     */
    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m" + String.format("%02d", seconds) + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h" + String.format("%02d", minutes) + "m";
    }

    /**
     * Open a file or directory using the system's default application.
     */
    private void openFile(File file) {
        if (file == null || !file.exists()) return;

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (IOException e) {
            System.err.println("Could not open file: " + e.getMessage());
        }
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (Exception e) {
            System.err.println("Could not open URL: " + e.getMessage());
        }
    }
}
