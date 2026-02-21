package org.fractalizer.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.function.Consumer;

/**
 * Modal dialog that displays export progress with an image preview,
 * progress bars, status text, and a cancel/close button.
 * Supports both single-image mode (1 bar) and animation mode (2 bars).
 */
public class ExportProgressDialog extends Stage {

    private final ImageView previewView;
    private final StackPane previewContainer;
    private final ProgressBar frameBar;
    private final Label frameLabel;
    private final ProgressBar totalBar;
    private final Label totalLabel;
    private final Label statusLabel;
    private final Button pauseButton;
    private final Button actionButton;

    private static final double DIALOG_WIDTH = 480;
    private static final double PREVIEW_HEIGHT = 260;

    // Animation mode shows both bars; image mode shows only frame bar
    private boolean animationMode = false;
    private Runnable onCancelRequested;
    private Consumer<Boolean> onPauseToggled;
    private boolean finished = false;
    private boolean paused = false;

    public ExportProgressDialog(Window owner, String title) {
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle(title);
        setResizable(false);

        VBox root = new VBox(10);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setStyle("-fx-background-color: #1e1e24;");
        root.setPrefWidth(DIALOG_WIDTH);

        // Preview image in a fixed-height container
        previewView = new ImageView();
        previewView.setFitWidth(DIALOG_WIDTH - 40);
        previewView.setFitHeight(PREVIEW_HEIGHT);
        previewView.setPreserveRatio(true);
        previewView.setSmooth(true);

        previewContainer = new StackPane(previewView);
        previewContainer.setPrefHeight(PREVIEW_HEIGHT);
        previewContainer.setMinHeight(PREVIEW_HEIGHT);
        previewContainer.setMaxHeight(PREVIEW_HEIGHT);
        previewContainer.setStyle("-fx-background-color: #111116; -fx-background-radius: 4;");

        // Frame progress (always visible)
        frameLabel = new Label("Initializing...");
        frameLabel.setStyle("-fx-text-fill: #ccc; -fx-font-family: monospace; -fx-font-size: 12px;");
        frameLabel.setMaxWidth(Double.MAX_VALUE);

        frameBar = new ProgressBar(0);
        frameBar.setMaxWidth(Double.MAX_VALUE);
        frameBar.setPrefHeight(18);
        frameBar.setStyle("-fx-accent: #4a90e2;");

        // Total progress (animation mode only)
        totalLabel = new Label("");
        totalLabel.setStyle("-fx-text-fill: #ccc; -fx-font-family: monospace; -fx-font-size: 12px;");
        totalLabel.setMaxWidth(Double.MAX_VALUE);

        totalBar = new ProgressBar(0);
        totalBar.setMaxWidth(Double.MAX_VALUE);
        totalBar.setPrefHeight(18);
        totalBar.setStyle("-fx-accent: #e29a4a;");

        // Status line (elapsed / ETA)
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        // Pause / Resume button (animation mode only)
        pauseButton = new Button("Pause");
        pauseButton.setPrefWidth(120);
        pauseButton.setPrefHeight(30);
        pauseButton.setOnAction(e -> setPausedInternal(!paused, true));

        // Cancel / Close button
        actionButton = new Button("Cancel");
        actionButton.setPrefWidth(140);
        actionButton.setPrefHeight(30);
        actionButton.setOnAction(e -> {
            if (finished) {
                close();
            } else if (onCancelRequested != null) {
                onCancelRequested.run();
                setPausedInternal(false, false);
                pauseButton.setDisable(true);
                actionButton.setDisable(true);
                actionButton.setText("Cancelling...");
            }
        });

        HBox buttonBox = new HBox(10, pauseButton, actionButton);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(4, 0, 0, 0));

        root.getChildren().addAll(
                previewContainer,
                frameLabel, frameBar,
                totalLabel, totalBar,
                statusLabel,
                buttonBox
        );

        // Default: image mode — hide total bar and preview
        totalLabel.setVisible(false);
        totalLabel.setManaged(false);
        totalBar.setVisible(false);
        totalBar.setManaged(false);
        previewContainer.setVisible(false);
        previewContainer.setManaged(false);
        pauseButton.setVisible(false);
        pauseButton.setManaged(false);
        pauseButton.setDisable(true);

        Scene scene = new Scene(root, DIALOG_WIDTH, -1);
        // Inherit stylesheets from owner
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        setScene(scene);

        // Close request triggers cancel
        setOnCloseRequest(e -> {
            if (!finished) {
                e.consume();
                if (onCancelRequested != null) {
                    onCancelRequested.run();
                    setPausedInternal(false, false);
                    pauseButton.setDisable(true);
                    actionButton.setDisable(true);
                    actionButton.setText("Cancelling...");
                }
            }
        });

        // Set explicit size after scene is set
        setWidth(DIALOG_WIDTH);
        applyHeight();
    }

    private void applyHeight() {
        // Image mode: 1 bar + status + button ≈ 180
        // Animation mode: preview + 2 bars + status + button ≈ 510
        double h = animationMode ? 510 : 180;
        setHeight(h);
    }

    /**
     * Configure for animation mode (2 progress bars + preview) or image mode (1 bar, no preview).
     */
    public void setAnimationMode(boolean animationMode) {
        this.animationMode = animationMode;
        totalLabel.setVisible(animationMode);
        totalLabel.setManaged(animationMode);
        totalBar.setVisible(animationMode);
        totalBar.setManaged(animationMode);
        previewContainer.setVisible(animationMode);
        previewContainer.setManaged(animationMode);
        pauseButton.setVisible(animationMode);
        pauseButton.setManaged(animationMode);
        setPauseEnabled(animationMode);
        applyHeight();
    }

    /**
     * Update the frame-level progress bar and label.
     * For image mode: "Sample 42 / 128"
     * For animation mode: "Frame 12/300 - Sample 8/16"
     */
    public void updateFrameProgress(double progress, String label) {
        frameBar.setProgress(progress);
        frameLabel.setText(label);
    }

    /**
     * Update the total progress bar and label (animation mode only).
     * e.g. "Total: 12 / 300 frames"
     */
    public void updateTotalProgress(double progress, String label) {
        totalBar.setProgress(progress);
        totalLabel.setText(label);
    }

    /**
     * Update the status text (elapsed time, ETA, etc.).
     */
    public void updateStatus(String text) {
        statusLabel.setText(text);
    }

    /**
     * Update the preview image thumbnail.
     */
    public void updatePreview(Image image) {
        if (image != null) {
            previewView.setImage(image);
        }
    }

    /**
     * Set progress to indeterminate with a label (e.g. for MP4 encoding).
     */
    public void setIndeterminate(String label) {
        frameBar.setProgress(-1);
        frameLabel.setText(label);
    }

    /**
     * Set the cancel handler.
     */
    public void setOnCancelRequested(Runnable handler) {
        this.onCancelRequested = handler;
    }

    /**
     * Re-enable the cancel button for FFmpeg encoding phase.
     * After animation cancel, the button is disabled — this restores it
     * with a new handler and appropriate label.
     */
    public void enableCancelForFFmpeg(Runnable handler) {
        this.onCancelRequested = handler;
        actionButton.setText("Cancel FFmpeg");
        actionButton.setDisable(false);
    }

    /**
     * Set the pause toggle handler (true = paused, false = resumed).
     */
    public void setOnPauseToggled(Consumer<Boolean> handler) {
        this.onPauseToggled = handler;
    }

    /**
     * Enable or disable pause/resume control.
     */
    public void setPauseEnabled(boolean enabled) {
        if (!enabled) {
            setPausedInternal(false, false);
        }
        pauseButton.setDisable(!enabled || finished || !animationMode);
    }

    private void setPausedInternal(boolean paused, boolean notify) {
        if (this.paused == paused) return;
        this.paused = paused;
        pauseButton.setText(paused ? "Resume" : "Pause");
        if (notify && onPauseToggled != null) {
            onPauseToggled.accept(paused);
        }
    }

    /**
     * Show a success message and switch button to "Close".
     */
    public void showSuccess(String message) {
        finished = true;
        statusLabel.setText(message);
        frameBar.setProgress(1.0);
        if (animationMode) totalBar.setProgress(1.0);
        setPausedInternal(false, false);
        pauseButton.setDisable(true);
        actionButton.setText("Close");
        actionButton.setDisable(false);
    }

    /**
     * Show a cancellation message and switch button to "Close".
     */
    public void showCancelled(String message) {
        finished = true;
        statusLabel.setText(message);
        setPausedInternal(false, false);
        pauseButton.setDisable(true);
        actionButton.setText("Close");
        actionButton.setDisable(false);
    }
}
