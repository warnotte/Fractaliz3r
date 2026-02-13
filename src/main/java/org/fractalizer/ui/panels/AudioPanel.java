package org.fractalizer.ui.panels;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.fractalizer.audio.AudioPreAnalyzer;
import org.fractalizer.audio.AudioReactiveEngine;
import org.fractalizer.audio.AudioReactiveEngine.AudioData;
import org.fractalizer.render.FFmpegExporter;
import org.fractalizer.ui.ExportProgressDialog;
import org.fractalizer.ui.components.EnhancedSlider;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Audio panel for loading and playing audio files with real-time spectrum analysis.
 * Supports two modes:
 * <ul>
 *   <li><b>Preview</b>: Real-time playback with AudioSpectrumListener for parameter tuning</li>
 *   <li><b>Offline Export</b>: Pre-analyze audio via FFT, render each frame at full quality, encode MP4</li>
 * </ul>
 */
public class AudioPanel extends ScrollPane implements Refreshable {

    private final RenderCallback renderCallback;
    private final AudioReactiveEngine audioEngine = new AudioReactiveEngine();

    // MediaPlayer (for real-time preview)
    private MediaPlayer mediaPlayer;
    private File currentFile;

    // Transport controls
    private Button loadButton;
    private Button playPauseButton;
    private Button stopButton;
    private Label fileLabel;
    private Slider progressSlider;
    private Label timeLabel;
    private boolean seekDragging = false;

    // Spectrum visualizer
    private Canvas spectrumCanvas;

    // Mapping sliders
    private EnhancedSlider bassMorph;
    private EnhancedSlider midToColor;
    private EnhancedSlider trebleToGlow;
    private EnhancedSlider beatToFOV;
    private EnhancedSlider onsetPulse;
    private EnhancedSlider levelToFog;

    // Sensitivity sliders
    private EnhancedSlider attackSlider;
    private EnhancedSlider releaseSlider;
    private EnhancedSlider sensitivitySlider;

    // Animation timer for visualizer + continuous render
    private AnimationTimer visualizerTimer;
    private boolean isPlaying = false;

    // Offline export state
    private volatile boolean offlineExporting = false;
    private volatile boolean exportCancelled = false;
    private volatile boolean exportPaused = false;
    private volatile AudioData currentOfflineData = AudioData.SILENT;
    private FrameExportCallback frameExportCallback;

    // Export UI controls
    private Button exportVideoButton;
    private Spinner<Integer> exportDurationSpinner;
    private Spinner<Integer> exportSamplesSpinner;
    private Spinner<Integer> exportFpsSpinner;
    private ComboBox<String> exportResolutionCombo;

    // Beat indicator for visualizer
    private double beatFlash = 0;

    // Level history ring buffer (rolling waveform, ~5s at 30fps)
    private final float[] levelHistory = new float[150];
    private int levelHistoryIndex = 0;

    // Solo mode: -1 = all bands, 0-7 = solo one band
    private int soloBand = -1;

    // Band labels for frequency display
    private static final String[] BAND_LABELS = {
            "Sub", "Bass", "Low", "Mid", "Hi", "Pres", "Brill", "Air"
    };

    // Band colors for visualizer
    private static final Color[] BAND_COLORS = {
            Color.web("#ff0040"), // Sub-bass - red
            Color.web("#ff4400"), // Bass - orange
            Color.web("#ff8800"), // Low-mid - amber
            Color.web("#ffcc00"), // Mid - yellow
            Color.web("#88ff00"), // Upper-mid - lime
            Color.web("#00ff88"), // Presence - cyan
            Color.web("#0088ff"), // Brilliance - blue
            Color.web("#8800ff"), // Air - purple
    };

    /**
     * Callback for rendering a single frame at export quality.
     */
    @FunctionalInterface
    public interface FrameExportCallback {
        Image exportFrame(File file, int width, int height, int samples,
                          Consumer<Double> onProgress, Supplier<Boolean> cancelCheck);
    }

    public AudioPanel(RenderCallback renderCallback) {
        this.renderCallback = renderCallback;
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        VBox content = new VBox(5);
        content.setPadding(new Insets(10));

        TitledPane filePane = new TitledPane("Audio File", createFileSection());
        filePane.setExpanded(true);

        TitledPane transportPane = new TitledPane("Transport (Preview)", createTransportSection());
        transportPane.setExpanded(true);

        TitledPane vizPane = new TitledPane("Spectrum", createVisualizerSection());
        vizPane.setExpanded(true);

        TitledPane mappingsPane = new TitledPane("Audio Mappings", createMappingsSection());
        mappingsPane.setExpanded(false);

        TitledPane sensitivityPane = new TitledPane("Sensitivity", createSensitivitySection());
        sensitivityPane.setExpanded(false);

        TitledPane presetsPane = new TitledPane("Presets", createPresetsSection());
        presetsPane.setExpanded(false);

        TitledPane exportPane = new TitledPane("Offline Video Export", createExportSection());
        exportPane.setExpanded(false);

        content.getChildren().addAll(filePane, transportPane, vizPane,
                mappingsPane, sensitivityPane, presetsPane, exportPane);

        setContent(content);
        startVisualizerTimer();
    }

    // ========================================================================
    // File Section
    // ========================================================================

    private VBox createFileSection() {
        VBox section = new VBox(6);

        loadButton = new Button("Load Audio File...");
        loadButton.setMaxWidth(Double.MAX_VALUE);
        loadButton.setOnAction(e -> loadAudioFile());

        fileLabel = new Label("No file loaded");
        fileLabel.getStyleClass().add("muted-label");

        section.getChildren().addAll(loadButton, fileLabel);
        return section;
    }

    // ========================================================================
    // Transport Section
    // ========================================================================

    private VBox createTransportSection() {
        VBox section = new VBox(6);

        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_LEFT);

        playPauseButton = new Button("\u25B6"); // Play triangle
        playPauseButton.setDisable(true);
        playPauseButton.setOnAction(e -> togglePlayPause());

        stopButton = new Button("\u25A0"); // Stop square
        stopButton.setDisable(true);
        stopButton.setOnAction(e -> stopPlayback());

        timeLabel = new Label("0:00 / 0:00");
        timeLabel.getStyleClass().add("mono-label");

        buttons.getChildren().addAll(playPauseButton, stopButton, timeLabel);

        progressSlider = new Slider(0, 1, 0);
        progressSlider.setMaxWidth(Double.MAX_VALUE);
        progressSlider.setDisable(true);
        progressSlider.setOnMousePressed(e -> seekDragging = true);
        progressSlider.setOnMouseReleased(e -> {
            seekDragging = false;
            if (mediaPlayer != null) {
                mediaPlayer.seek(mediaPlayer.getTotalDuration().multiply(progressSlider.getValue()));
            }
        });

        section.getChildren().addAll(buttons, progressSlider);
        return section;
    }

    // ========================================================================
    // Visualizer Section
    // ========================================================================

    private VBox createVisualizerSection() {
        VBox section = new VBox(6);

        spectrumCanvas = new Canvas(300, 160);
        drawEmptySpectrum();

        // Click handler for solo mode (only in spectrum bar zone, y < 80)
        spectrumCanvas.setOnMouseClicked(e -> {
            if (e.getY() > 80) return;
            double barWidth = (spectrumCanvas.getWidth() - 20) / 8.0;
            int clickedBand = (int) ((e.getX() - 10) / barWidth);
            if (clickedBand < 0 || clickedBand > 7) return;
            soloBand = (clickedBand == soloBand) ? -1 : clickedBand;
        });

        section.getChildren().addAll(spectrumCanvas);
        return section;
    }

    // ========================================================================
    // Mappings Section
    // ========================================================================

    private VBox createMappingsSection() {
        VBox section = new VBox(8);

        bassMorph = new EnhancedSlider("Bass \u2192 Fractal Morph", 0, 1, 0.5, false);
        midToColor = new EnhancedSlider("Mid \u2192 Color Shift", 0, 1, 0.5, false);
        trebleToGlow = new EnhancedSlider("Treble \u2192 Glow", 0, 1, 0.3, false);
        beatToFOV = new EnhancedSlider("Beat \u2192 FOV Pulse", 0, 1, 0.3, false);
        onsetPulse = new EnhancedSlider("Onset \u2192 Emissive Pulse", 0, 1, 0.4, false);
        levelToFog = new EnhancedSlider("Level \u2192 Fog/AO", 0, 1, 0.2, false);

        section.getChildren().addAll(bassMorph, midToColor, trebleToGlow, beatToFOV, onsetPulse, levelToFog);
        return section;
    }

    // ========================================================================
    // Sensitivity Section
    // ========================================================================

    private VBox createSensitivitySection() {
        VBox section = new VBox(8);

        attackSlider = new EnhancedSlider("Attack", 0, 0.99, 0.7, false);
        attackSlider.getSlider().valueProperty().addListener((obs, old, val) ->
                audioEngine.setAttack(val.floatValue()));

        releaseSlider = new EnhancedSlider("Release", 0, 0.99, 0.7, false);
        releaseSlider.getSlider().valueProperty().addListener((obs, old, val) ->
                audioEngine.setRelease(val.floatValue()));

        sensitivitySlider = new EnhancedSlider("Beat Sensitivity", 0, 1, 0.5, false);
        sensitivitySlider.getSlider().valueProperty().addListener((obs, old, val) ->
                audioEngine.setSensitivity(val.floatValue()));

        // Reactivity presets
        HBox presets = new HBox(6);
        presets.setAlignment(Pos.CENTER_LEFT);

        Button smoothPreset = new Button("Smooth");
        smoothPreset.setOnAction(e -> { attackSlider.setValue(0.85); releaseSlider.setValue(0.85); });

        Button defaultPreset = new Button("Default");
        defaultPreset.setOnAction(e -> { attackSlider.setValue(0.7); releaseSlider.setValue(0.7); });

        Button punchyPreset = new Button("Punchy");
        punchyPreset.setOnAction(e -> { attackSlider.setValue(0.3); releaseSlider.setValue(0.8); });

        Button instantPreset = new Button("Instant");
        instantPreset.setOnAction(e -> { attackSlider.setValue(0.0); releaseSlider.setValue(0.5); });

        presets.getChildren().addAll(smoothPreset, defaultPreset, punchyPreset, instantPreset);

        section.getChildren().addAll(attackSlider, releaseSlider, sensitivitySlider, presets);
        return section;
    }

    // ========================================================================
    // Presets Section
    // ========================================================================

    private HBox createPresetsSection() {
        HBox section = new HBox(6);
        section.setAlignment(Pos.CENTER_LEFT);

        Button subtle = new Button("Subtle");
        subtle.setOnAction(e -> applyPreset(0.2, 0.2, 0.1, 0.1, 0.2, 0.1));

        Button medium = new Button("Medium");
        medium.setOnAction(e -> applyPreset(0.5, 0.5, 0.3, 0.3, 0.4, 0.2));

        Button intense = new Button("Intense");
        intense.setOnAction(e -> applyPreset(0.8, 0.8, 0.6, 0.6, 0.7, 0.4));

        Button psychedelic = new Button("Psychedelic");
        psychedelic.setOnAction(e -> applyPreset(1.0, 1.0, 1.0, 0.8, 1.0, 0.6));

        section.getChildren().addAll(subtle, medium, intense, psychedelic);
        return section;
    }

    private void applyPreset(double morph, double mid, double treble, double beat, double onset, double fog) {
        bassMorph.setValue(morph);
        midToColor.setValue(mid);
        trebleToGlow.setValue(treble);
        beatToFOV.setValue(beat);
        onsetPulse.setValue(onset);
        levelToFog.setValue(fog);
    }

    // ========================================================================
    // Export Section (Offline Rendering)
    // ========================================================================

    private VBox createExportSection() {
        VBox section = new VBox(8);

        Label info = new Label("Pre-analyzes audio, then renders each frame\nat full quality (path tracer, N samples).");
        info.getStyleClass().add("info-label");
        info.setWrapText(true);

        // Resolution
        HBox resBox = new HBox(6);
        resBox.setAlignment(Pos.CENTER_LEFT);
        resBox.getChildren().add(new Label("Resolution:"));
        exportResolutionCombo = new ComboBox<>();
        exportResolutionCombo.getItems().addAll(
                "1280x720 (HD)",
                "1920x1080 (Full HD)",
                "2560x1440 (QHD)",
                "3840x2160 (4K)"
        );
        exportResolutionCombo.setValue("1920x1080 (Full HD)");
        resBox.getChildren().add(exportResolutionCombo);

        // Samples per frame
        HBox samplesBox = new HBox(6);
        samplesBox.setAlignment(Pos.CENTER_LEFT);
        samplesBox.getChildren().add(new Label("Samples/frame:"));
        exportSamplesSpinner = new Spinner<>(1, 256, 16, 4);
        exportSamplesSpinner.setPrefWidth(70);
        exportSamplesSpinner.setEditable(true);
        exportSamplesSpinner.setTooltip(new Tooltip("More samples = better quality, slower render"));
        samplesBox.getChildren().add(exportSamplesSpinner);

        // FPS
        HBox fpsBox = new HBox(6);
        fpsBox.setAlignment(Pos.CENTER_LEFT);
        fpsBox.getChildren().add(new Label("FPS:"));
        exportFpsSpinner = new Spinner<>(10, 60, 30, 5);
        exportFpsSpinner.setPrefWidth(70);
        exportFpsSpinner.setEditable(true);
        fpsBox.getChildren().add(exportFpsSpinner);

        // Duration
        HBox durationBox = new HBox(6);
        durationBox.setAlignment(Pos.CENTER_LEFT);
        durationBox.getChildren().add(new Label("Duration:"));
        exportDurationSpinner = new Spinner<>(0, 600, 30, 5);
        exportDurationSpinner.setPrefWidth(70);
        exportDurationSpinner.setEditable(true);
        exportDurationSpinner.setTooltip(new Tooltip("Max seconds (0 = full track)"));
        Label secLabel = new Label("sec (0=full)");
        secLabel.getStyleClass().add("small-label");
        durationBox.getChildren().addAll(exportDurationSpinner, secLabel);

        // Export button
        exportVideoButton = new Button("Export Audio-Reactive Video...");
        exportVideoButton.setMaxWidth(Double.MAX_VALUE);
        exportVideoButton.getStyleClass().add("bold-label");
        exportVideoButton.setDisable(true);
        exportVideoButton.setOnAction(e -> startOfflineExport());

        // FFmpeg status
        Label ffmpegLabel = new Label();
        if (FFmpegExporter.isFFmpegAvailable()) {
            ffmpegLabel.setText("FFmpeg: " + FFmpegExporter.getFFmpegVersion());
            ffmpegLabel.getStyleClass().add("status-ok");
        } else {
            ffmpegLabel.setText("FFmpeg not found (required for export)");
            ffmpegLabel.getStyleClass().add("status-error");
        }

        section.getChildren().addAll(info,
                resBox, samplesBox, fpsBox, durationBox,
                exportVideoButton, ffmpegLabel);
        return section;
    }

    private int[] parseResolution() {
        String res = exportResolutionCombo.getValue();
        String dims = res.split(" ")[0]; // "1920x1080"
        String[] parts = dims.split("x");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private void startOfflineExport() {
        if (currentFile == null) {
            showAlert(Alert.AlertType.WARNING, "No Audio", "Please load an audio file first.");
            return;
        }
        if (frameExportCallback == null) {
            showAlert(Alert.AlertType.WARNING, "Not Ready", "Frame export not configured.");
            return;
        }
        if (!FFmpegExporter.isFFmpegAvailable()) {
            showAlert(Alert.AlertType.WARNING, "FFmpeg Required",
                    "FFmpeg is required for audio decoding and MP4 encoding.\nPlease install FFmpeg.");
            return;
        }

        // Stop real-time playback
        stopPlayback();

        // Choose output directory
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        chooser.setInitialDirectory(currentFile.getParentFile());
        File outDir = chooser.showDialog(getScene().getWindow());
        if (outDir == null) return;

        // Read export parameters
        int[] resolution = parseResolution();
        int exportWidth = resolution[0];
        int exportHeight = resolution[1];
        int exportSamples = exportSamplesSpinner.getValue();
        int fps = exportFpsSpinner.getValue();
        int maxSeconds = exportDurationSpinner.getValue();
        // For offline export, use the average of attack/release as smoothing
        float smoothing = (float) ((attackSlider.getValue() + releaseSlider.getValue()) / 2.0);
        float sensitivity = (float) sensitivitySlider.getValue();

        // Create timestamped subdirectory
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        File exportDir = new File(outDir, "audio_fractal_" + timestamp);
        if (!exportDir.mkdirs()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Could not create directory: " + exportDir);
            return;
        }

        // Lock UI
        offlineExporting = true;
        exportCancelled = false;
        exportPaused = false;
        setExportUILocked(true);
        exportVideoButton.setDisable(true);

        // Create and show progress dialog
        ExportProgressDialog dialog = new ExportProgressDialog(getScene().getWindow(), "Audio-Reactive Export");
        dialog.setAnimationMode(true);
        dialog.setOnCancelRequested(() -> {
            exportCancelled = true;
            exportPaused = false;
        });
        dialog.setOnPauseToggled(paused -> exportPaused = paused);

        long exportStartTime = System.currentTimeMillis();

        // Run everything on background thread
        final File fExportDir = exportDir;
        Thread exportThread = new Thread(() -> {
            int renderedFrames = 0;

            try {
                // ========================
                // Phase 1: Pre-analyze audio
                // ========================
                Platform.runLater(() -> {
                    dialog.updateTotalProgress(0, "Phase 1/3: Pre-analyzing audio");
                    dialog.setPauseEnabled(false);
                });

                AudioData[] audioFrames = AudioPreAnalyzer.analyze(
                        currentFile, fps, maxSeconds > 0 ? maxSeconds : 0,
                        smoothing, sensitivity,
                        p -> Platform.runLater(() ->
                                dialog.updateFrameProgress(p, String.format("Analyzing audio... %.0f%%", p * 100))
                        )
                );

                int totalFrames = audioFrames.length;
                double duration = totalFrames / (double) fps;

                Platform.runLater(() -> dialog.setPauseEnabled(true));

                System.out.printf("[AudioExport] Starting offline render: %d frames, %dx%d, %d spp%n",
                        totalFrames, exportWidth, exportHeight, exportSamples);

                // ========================
                // Phase 2: Render frames offline
                // ========================
                for (int frame = 0; frame < totalFrames && !exportCancelled; frame++) {
                    waitWhilePaused(dialog, renderedFrames, totalFrames, exportStartTime);
                    if (exportCancelled) break;

                    long frameStart = System.currentTimeMillis();

                    // Set current audio data for this frame
                    currentOfflineData = audioFrames[frame];

                    // Prepare frame on FX thread
                    final int currentFrame = frame;
                    CountDownLatch latch = new CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            dialog.updateFrameProgress(0,
                                    String.format("Frame %d / %d \u2014 Sample 0/%d", currentFrame + 1, totalFrames, exportSamples));
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await();

                    // Render frame with per-sample progress
                    File frameFile = new File(fExportDir, String.format("frame_%05d.png", frame));
                    final int fFrame = frame;

                    Image frameImage = frameExportCallback.exportFrame(
                            frameFile, exportWidth, exportHeight, exportSamples,
                            sampleProgress -> {
                                int currentSample = (int) Math.round(sampleProgress * exportSamples);
                                Platform.runLater(() ->
                                        dialog.updateFrameProgress(sampleProgress,
                                                String.format("Frame %d / %d \u2014 Sample %d/%d",
                                                        fFrame + 1, totalFrames, currentSample, exportSamples))
                                );
                            },
                            () -> exportCancelled
                    );

                    // Update preview
                    if (frameImage != null) {
                        Platform.runLater(() -> dialog.updatePreview(frameImage));
                    }

                    renderedFrames++;

                    if (exportCancelled) break;

                    // Update total progress + ETA
                    double progress = (double) (frame + 1) / totalFrames;
                    long elapsed = System.currentTimeMillis() - exportStartTime;
                    long frameMs = System.currentTimeMillis() - frameStart;
                    long estimatedTotal = (long) (elapsed / progress);
                    long remaining = estimatedTotal - elapsed;
                    final String etaText = formatMs(elapsed) + " elapsed \u2014 ~" + formatMs(remaining) + " remaining";

                    final int fFrame2 = frame;
                    Platform.runLater(() -> {
                        dialog.updateFrameProgress(1.0,
                                String.format("Frame %d / %d \u2014 done", fFrame2 + 1, totalFrames));
                        dialog.updateTotalProgress(progress,
                                String.format("Total: %d / %d frames", fFrame2 + 1, totalFrames));
                        dialog.updateStatus(etaText);
                    });

                    System.out.printf("[AudioExport] Frame %d/%d (%.1f%%) \u2014 %.1fs/frame \u2014 ETA %s%n",
                            frame + 1, totalFrames, progress * 100,
                            frameMs / 1000.0, formatMs(remaining));
                }

                // Stop offline mode
                offlineExporting = false;
                currentOfflineData = AudioData.SILENT;

                // ========================
                // Phase 3: Encode MP4
                // ========================
                if (!exportCancelled) {
                    exportPaused = false;
                    Platform.runLater(() -> {
                        dialog.setPauseEnabled(false);
                        dialog.setIndeterminate("Encoding MP4...");
                    });

                    File mp4File = new File(fExportDir.getParentFile(), fExportDir.getName() + ".mp4");
                    FFmpegExporter.ExportResult result = FFmpegExporter.createMP4WithAudio(
                            fExportDir, currentFile, mp4File,
                            duration, 20,
                            p -> Platform.runLater(() -> dialog.updateFrameProgress(p, "Encoding MP4..."))
                    );

                    long totalMs = System.currentTimeMillis() - exportStartTime;
                    final int fRendered = renderedFrames;
                    Platform.runLater(() -> {
                        if (result.success) {
                            dialog.showSuccess(fRendered + " frames + MP4 in " + formatMs(totalMs)
                                    + "\n" + result.outputFile.getAbsolutePath());
                            try {
                                if (java.awt.Desktop.isDesktopSupported()) {
                                    java.awt.Desktop.getDesktop().open(result.outputFile);
                                }
                            } catch (IOException e) {
                                // Ignore
                            }
                        } else {
                            dialog.showCancelled("MP4 encoding failed: " + result.message);
                        }
                        finishAudioExport();
                    });
                } else if (renderedFrames > 0) {
                    // Cancelled — create partial MP4 from rendered frames
                    exportPaused = false;
                    double partialDuration = renderedFrames / (double) fps;
                    Platform.runLater(() -> {
                        dialog.setPauseEnabled(false);
                        dialog.setIndeterminate("Encoding MP4 from rendered frames...");
                    });

                    File mp4File = new File(fExportDir.getParentFile(), fExportDir.getName() + ".mp4");
                    FFmpegExporter.ExportResult result = FFmpegExporter.createMP4WithAudio(
                            fExportDir, currentFile, mp4File,
                            partialDuration, 20,
                            p -> Platform.runLater(() -> dialog.updateFrameProgress(p, "Encoding MP4..."))
                    );

                    final int fRendered = renderedFrames;
                    final int fTotal = totalFrames;
                    Platform.runLater(() -> {
                        if (result.success) {
                            dialog.showCancelled("Cancelled. MP4 created from " + fRendered + "/" + fTotal + " frames:\n"
                                    + result.outputFile.getAbsolutePath());
                            try {
                                if (java.awt.Desktop.isDesktopSupported()) {
                                    java.awt.Desktop.getDesktop().open(result.outputFile);
                                }
                            } catch (IOException e) {
                                // Ignore
                            }
                        } else {
                            dialog.showCancelled("Cancelled after " + fRendered + "/" + fTotal + " frames.\nMP4 failed: " + result.message);
                        }
                        finishAudioExport();
                    });
                } else {
                    // Cancelled with no frames rendered
                    Platform.runLater(() -> {
                        dialog.showCancelled("Export cancelled before any frames were rendered.");
                        finishAudioExport();
                    });
                }

            } catch (IOException e) {
                offlineExporting = false;
                currentOfflineData = AudioData.SILENT;
                Platform.runLater(() -> {
                    dialog.showCancelled("Error: " + e.getMessage());
                    finishAudioExport();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                offlineExporting = false;
                currentOfflineData = AudioData.SILENT;
                Platform.runLater(() -> {
                    dialog.showCancelled("Export interrupted.");
                    finishAudioExport();
                });
            }
        });
        exportThread.setDaemon(true);
        exportThread.setName("AudioExport");
        exportThread.start();

        dialog.show();
    }

    private void finishAudioExport() {
        offlineExporting = false;
        exportPaused = false;
        currentOfflineData = AudioData.SILENT;
        exportVideoButton.setDisable(false);
        setExportUILocked(false);
    }

    private void waitWhilePaused(ExportProgressDialog dialog, int renderedFrames, int totalFrames, long exportStartTime)
            throws InterruptedException {
        if (!exportPaused || exportCancelled) return;

        long elapsed = System.currentTimeMillis() - exportStartTime;
        final String pausedText = String.format("Paused after %d / %d frames \u2014 %s elapsed",
                renderedFrames, totalFrames, formatMs(elapsed));
        Platform.runLater(() -> dialog.updateStatus(pausedText));
        System.out.println("[AudioExport] Paused after " + renderedFrames + "/" + totalFrames + " frames");

        while (exportPaused && !exportCancelled) {
            Thread.sleep(120);
        }

        if (!exportCancelled) {
            long resumedElapsed = System.currentTimeMillis() - exportStartTime;
            final String resumedText = formatMs(resumedElapsed) + " elapsed \u2014 resumed";
            Platform.runLater(() -> dialog.updateStatus(resumedText));
            System.out.println("[AudioExport] Resumed");
        }
    }

    private void setExportUILocked(boolean locked) {
        loadButton.setDisable(locked);
        playPauseButton.setDisable(locked);
        stopButton.setDisable(locked);
        exportResolutionCombo.setDisable(locked);
        exportSamplesSpinner.setDisable(locked);
        exportFpsSpinner.setDisable(locked);
        exportDurationSpinner.setDisable(locked);
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String formatMs(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec = sec % 60;
        if (min < 60) return String.format("%dm%02ds", min, sec);
        long hr = min / 60;
        min = min % 60;
        return String.format("%dh%02dm%02ds", hr, min, sec);
    }

    // ========================================================================
    // Audio loading and playback (real-time preview)
    // ========================================================================

    private void loadAudioFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Audio File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.aac", "*.m4a"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;

        // Check for unsupported formats
        String name = file.getName().toLowerCase();
        if (name.endsWith(".flac") || name.endsWith(".ogg")) {
            showAlert(Alert.AlertType.WARNING, "Unsupported Format",
                    "FLAC and OGG formats are not supported by JavaFX Media.\nPlease use MP3, WAV, or AAC.");
            return;
        }

        // Dispose previous player
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
        }

        try {
            Media media = new Media(file.toURI().toString());
            mediaPlayer = new MediaPlayer(media);
            currentFile = file;

            // Configure spectrum listener (same as what AudioPreAnalyzer reproduces offline)
            mediaPlayer.setAudioSpectrumNumBands(128);
            mediaPlayer.setAudioSpectrumInterval(0.033); // ~30fps
            mediaPlayer.setAudioSpectrumThreshold(-60);

            mediaPlayer.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
                    audioEngine.processSpectrum(magnitudes, phases);
                    // Diagnostic: print peak band values every ~3s for comparison with offline
                    if (System.currentTimeMillis() % 3000 < 50) {
                        var d = audioEngine.getLatestData();
                        System.out.printf("[RT Audio] bands=[%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f] lv=%.4f bt=%.4f%n",
                                d.bands()[0], d.bands()[1], d.bands()[2], d.bands()[3],
                                d.bands()[4], d.bands()[5], d.bands()[6], d.bands()[7],
                                d.level(), d.beat());
                    }
            });

            mediaPlayer.setOnReady(() -> {
                fileLabel.setText(file.getName());
                playPauseButton.setDisable(false);
                stopButton.setDisable(false);
                progressSlider.setDisable(false);
                if (exportVideoButton != null) exportVideoButton.setDisable(false);
                updateTimeLabel();
            });

            mediaPlayer.setOnEndOfMedia(() -> {
                isPlaying = false;
                playPauseButton.setText("\u25B6");
                audioEngine.reset();
            });

            mediaPlayer.setOnError(() ->
                    showAlert(Alert.AlertType.ERROR, "Playback Error",
                            "Failed to load audio: " + mediaPlayer.getError().getMessage()));

            mediaPlayer.currentTimeProperty().addListener((obs, old, val) -> {
                if (!seekDragging && val != null) {
                    Duration total = mediaPlayer.getTotalDuration();
                    if (total != null && !total.isUnknown()) {
                        progressSlider.setValue(val.toMillis() / total.toMillis());
                    }
                    updateTimeLabel();
                }
            });

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Load Error",
                    "Failed to open audio file: " + e.getMessage());
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;

        if (isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            playPauseButton.setText("\u25B6");
        } else {
            mediaPlayer.play();
            isPlaying = true;
            playPauseButton.setText("\u23F8");
        }
    }

    private void stopPlayback() {
        if (mediaPlayer == null) return;

        mediaPlayer.stop();
        isPlaying = false;
        playPauseButton.setText("\u25B6");
        audioEngine.reset();
    }

    private void updateTimeLabel() {
        if (mediaPlayer == null) return;

        Duration current = mediaPlayer.getCurrentTime();
        Duration total = mediaPlayer.getTotalDuration();

        if (current != null && total != null && !total.isUnknown()) {
            timeLabel.setText(formatDuration(current) + " / " + formatDuration(total));
        }
    }

    private String formatDuration(Duration d) {
        int totalSec = (int) d.toSeconds();
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }

    // ========================================================================
    // Visualizer
    // ========================================================================

    private void startVisualizerTimer() {
        visualizerTimer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(long now) {
                if (now - lastUpdate < 33_000_000L) return; // ~30fps
                lastUpdate = now;

                if (isPlaying) {
                    drawSpectrum();
                    renderCallback.requestRender();
                }
            }
        };
        visualizerTimer.start();
    }

    private void drawEmptySpectrum() {
        GraphicsContext gc = spectrumCanvas.getGraphicsContext2D();
        double w = spectrumCanvas.getWidth();
        double h = spectrumCanvas.getHeight();

        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, w, h);

        // Grid lines in spectrum zone (0-80)
        gc.setStroke(Color.web("#333"));
        gc.setLineWidth(0.5);
        for (int i = 0; i < 4; i++) {
            double y = 80.0 * (i + 1) / 5.0;
            gc.strokeLine(0, y, w, y);
        }

        // Band labels
        gc.setFill(Color.web("#555"));
        gc.setFont(javafx.scene.text.Font.font("monospace", 8));
        double barWidth = (w - 20) / 8.0;
        for (int i = 0; i < 8; i++) {
            double x = 10 + i * barWidth + barWidth / 2 - 8;
            gc.fillText(BAND_LABELS[i], x, 90);
        }

        gc.setFont(javafx.scene.text.Font.font("monospace", 10));
        gc.fillText("No audio", w / 2 - 25, 40);
    }

    private void drawSpectrum() {
        AudioData data = audioEngine.getLatestData();
        GraphicsContext gc = spectrumCanvas.getGraphicsContext2D();
        double w = spectrumCanvas.getWidth();

        // Update level history ring buffer
        levelHistory[levelHistoryIndex % levelHistory.length] = data.level();
        levelHistoryIndex++;

        // Clear entire canvas
        gc.setFill(Color.web("#1a1a2e"));
        gc.fillRect(0, 0, w, 160);

        // Beat flash overlay
        if (data.beat() > 0.3) {
            beatFlash = Math.max(beatFlash, data.beat());
        }
        if (beatFlash > 0.01) {
            gc.setFill(Color.rgb(255, 255, 255, beatFlash * 0.15));
            gc.fillRect(0, 0, w, 80);
            beatFlash *= 0.85;
        }

        // ---- Zone 1: Spectrum bars (0-80px) ----
        gc.setStroke(Color.web("#333"));
        gc.setLineWidth(0.5);
        for (int i = 0; i < 4; i++) {
            double y = 80.0 * (i + 1) / 5.0;
            gc.strokeLine(0, y, w, y);
        }

        float[] bands = data.bands();
        double barWidth = (w - 20) / 8.0;
        double gap = 2;

        for (int i = 0; i < 8; i++) {
            double barH = Math.min(bands[i] * 80 * 2.5, 76);
            double x = 10 + i * barWidth + gap / 2;
            double y = 80 - barH - 2;

            Color color = BAND_COLORS[i];
            if (soloBand >= 0 && i != soloBand) {
                // Non-solo'd bands: dim grey
                gc.setFill(color.deriveColor(0, 0.2, 0.3, 0.3));
                gc.fillRect(x, y, barWidth - gap, barH);
            } else {
                gc.setFill(color.deriveColor(0, 1, 0.8, 0.9));
                gc.fillRect(x, y, barWidth - gap, barH);
                gc.setFill(color);
                gc.fillRect(x, y, barWidth - gap, Math.min(3, barH));
                if (soloBand == i) {
                    // Highlight border for solo'd band
                    gc.setStroke(Color.WHITE);
                    gc.setLineWidth(1);
                    gc.strokeRect(x - 1, y - 1, barWidth - gap + 2, barH + 2);
                }
            }
        }

        // ---- Zone 2: Threshold line (superimposed on sub-bass/bass bars) ----
        float threshold = audioEngine.getLastBeatThreshold();
        if (threshold > 0.001f) {
            // Scale threshold to bar height (sub-bass + bass zone, using same scale as bars)
            double thresholdY = 80 - Math.min(threshold * 80 * 2.5 / 2.0, 76) - 2;
            gc.setStroke(Color.rgb(255, 60, 60, 0.8));
            gc.setLineWidth(1);
            gc.setLineDashes(4, 3);
            gc.strokeLine(10, thresholdY, 10 + 2 * barWidth, thresholdY);
            gc.setLineDashes(null);
            gc.setFill(Color.rgb(255, 60, 60, 0.7));
            gc.setFont(javafx.scene.text.Font.font("monospace", 7));
            gc.fillText("Thr", 10 + 2 * barWidth + 2, thresholdY + 3);
        }

        // ---- Zone 3: Band labels (80-92px) ----
        gc.setFont(javafx.scene.text.Font.font("monospace", 8));
        for (int i = 0; i < 8; i++) {
            double x = 10 + i * barWidth + barWidth / 2 - 8;
            gc.setFill((soloBand == i) ? Color.WHITE : Color.web("#888"));
            gc.fillText(BAND_LABELS[i], x, 90);
        }

        // ---- Zone 4: Level history rolling waveform (94-120px) ----
        double histY = 94;
        double histH = 26;
        // Dark background for history zone
        gc.setFill(Color.web("#111122"));
        gc.fillRect(0, histY, w, histH);

        // Grid lines at 0.25, 0.5, 0.75
        gc.setStroke(Color.web("#222244"));
        gc.setLineWidth(0.5);
        for (int g = 1; g <= 3; g++) {
            double gy = histY + histH * (1.0 - g * 0.25);
            gc.strokeLine(0, gy, w, gy);
        }

        // Draw level history polyline
        gc.setStroke(Color.web("#00ff88"));
        gc.setLineWidth(1);
        gc.beginPath();
        int histLen = levelHistory.length;
        for (int i = 0; i < histLen; i++) {
            int idx = (levelHistoryIndex - histLen + i + histLen * 2) % histLen;
            float val = Math.min(levelHistory[idx] * 3f, 1f);
            double hx = (double) i / (histLen - 1) * w;
            double hy = histY + histH * (1.0 - val);
            if (i == 0) gc.moveTo(hx, hy);
            else gc.lineTo(hx, hy);
        }
        gc.stroke();

        // ---- Zone 5: VU meter bar (122-130px) ----
        double vuY = 122;
        double vuH = 6;
        gc.setFill(Color.web("#111122"));
        gc.fillRect(10, vuY, w - 20, vuH);

        double vuLevel = Math.min(data.level() * 3.0, 1.0);
        double vuWidth = (w - 20) * vuLevel;
        if (vuWidth > 0) {
            // Green zone (0-60%)
            double greenEnd = Math.min(vuWidth, (w - 20) * 0.6);
            if (greenEnd > 0) {
                gc.setFill(Color.web("#00cc44"));
                gc.fillRect(10, vuY, greenEnd, vuH);
            }
            // Yellow zone (60-85%)
            if (vuLevel > 0.6) {
                double yellowStart = (w - 20) * 0.6;
                double yellowEnd = Math.min(vuWidth, (w - 20) * 0.85);
                gc.setFill(Color.web("#ffcc00"));
                gc.fillRect(10 + yellowStart, vuY, yellowEnd - yellowStart, vuH);
            }
            // Red zone (85-100%)
            if (vuLevel > 0.85) {
                double redStart = (w - 20) * 0.85;
                gc.setFill(Color.web("#ff3333"));
                gc.fillRect(10 + redStart, vuY, vuWidth - redStart, vuH);
            }
        }
        // VU meter border
        gc.setStroke(Color.web("#444"));
        gc.setLineWidth(0.5);
        gc.strokeRect(10, vuY, w - 20, vuH);

        // ---- Zone 6: Beat/onset indicators (132-150px) ----
        double indicatorY = 136;

        // Beat LED
        if (data.beat() > 0.1) {
            gc.setFill(Color.rgb(255, 50, 50, Math.min(data.beat(), 1.0)));
        } else {
            gc.setFill(Color.web("#331111"));
        }
        gc.fillOval(10, indicatorY, 10, 10);
        gc.setFill(Color.web("#888"));
        gc.setFont(javafx.scene.text.Font.font("monospace", 8));
        gc.fillText("Beat", 23, indicatorY + 9);

        // Onset LED
        if (data.onset() > 0.1) {
            gc.setFill(Color.rgb(50, 200, 255, Math.min(data.onset(), 1.0)));
        } else {
            gc.setFill(Color.web("#111133"));
        }
        gc.fillOval(60, indicatorY, 10, 10);
        gc.setFill(Color.web("#888"));
        gc.fillText("Onset", 73, indicatorY + 9);

        // Solo indicator
        if (soloBand >= 0) {
            gc.setFill(Color.web("#ffcc00"));
            gc.fillText("Solo: " + BAND_LABELS[soloBand], 120, indicatorY + 9);
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Whether audio is active (real-time preview OR offline export).
     * Used by buildUniforms() to decide whether to include audio uniforms.
     */
    public boolean isAudioPlaying() {
        return isPlaying || offlineExporting;
    }

    /**
     * Get the current audio data.
     * During real-time preview: returns live spectrum data.
     * During offline export: returns pre-computed data for the current frame.
     */
    public AudioData getAudioData() {
        AudioData raw = offlineExporting ? currentOfflineData : audioEngine.getLatestData();
        if (soloBand < 0) return raw;

        // Solo: zero out non-selected bands
        float[] filtered = new float[8];
        filtered[soloBand] = raw.bands()[soloBand];
        return new AudioData(filtered, raw.level(), raw.beat(), raw.onset());
    }

    /** Get mapping slider values for uniform building. */
    public float getReactMorph() { return (float) bassMorph.getValue(); }
    public float getReactColor() { return (float) midToColor.getValue(); }
    public float getReactGlow() { return (float) trebleToGlow.getValue(); }
    public float getReactFOV() { return (float) beatToFOV.getValue(); }
    public float getReactOnset() { return (float) onsetPulse.getValue(); }
    public float getReactFog() { return (float) levelToFog.getValue(); }

    /**
     * Set the callback for offline frame rendering.
     * This is wired to controller.exportAnimationFrame() by the app.
     */
    public void setFrameExportCallback(FrameExportCallback callback) {
        this.frameExportCallback = callback;
    }

    /** Whether an offline export is in progress. */
    public boolean isExporting() {
        return offlineExporting;
    }

    /** Cleanup resources when application closes. */
    public void dispose() {
        exportCancelled = true;
        if (visualizerTimer != null) {
            visualizerTimer.stop();
        }
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
    }

    @Override
    public void refreshFromParams(boolean suppressRender) {
        // Audio panel has no fractal params to refresh
    }
}
