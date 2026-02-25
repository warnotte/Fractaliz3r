package org.fractalizer.ui.panels;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.fractalizer.animation.Easing;
import org.fractalizer.animation.Timeline;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * UI Panel for animation timeline control.
 * Allows creating keyframes and playing animations.
 * Export functionality is in ExportPanel.
 */
public class AnimationPanel extends VBox {

    private final Timeline timeline;
    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final Consumer<Double> onTimeChange;
    private final Runnable onRenderRequest;

    // UI Components
    private Slider timeSlider;
    private Label timeLabel;
    private Label frameLabel;
    private Button playButton;
    private Spinner<Double> durationSpinner;
    private Spinner<Integer> fpsSpinner;
    private CheckBox loopCheckbox;
    private ListView<String> keyframeList;
    private ComboBox<Easing> easingCombo;

    // Playback
    private AnimationTimer playbackTimer;
    private long lastFrameTime;

    public AnimationPanel(Supplier<AbstractFractalParams> paramsSupplier,
                          Consumer<Double> onTimeChange,
                          Runnable onRenderRequest) {
        this.timeline = new Timeline(10.0, 30.0);
        this.paramsSupplier = paramsSupplier;
        this.onTimeChange = onTimeChange;
        this.onRenderRequest = onRenderRequest;

        initializeTracks();
        buildUI();
        setupPlaybackTimer();
    }

    private void initializeTracks() {
        // Camera tracks
        timeline.createTrack("camPos", float[].class, new float[]{0, 0, -3});
        timeline.createTrack("camQuat", float[].class, new float[]{0, 0, 0, 1});

        // Common params
        timeline.createTrack("fov", Float.class, 60.0f);

        // Depth of Field (animatable)
        timeline.createTrack("focalDistance", Float.class, 2.5f);
        timeline.createTrack("aperture", Float.class, 0.02f);

        // Light direction (vec3)
        timeline.createTrack("lightDir", float[].class, new float[]{1.0f, 1.0f, 0.5f});

        // Base hue / material hue (vec3)
        timeline.createTrack("baseHue", float[].class, new float[]{0.6f, 0.3f, 0.1f});
    }

    private void buildUI() {
        setSpacing(10);
        setPadding(new Insets(10));

        // Timeline settings section
        add(createSettingsSection());

        // Transport controls
        add(createTransportSection());

        // Time slider
        add(createTimeSliderSection());

        // Keyframe controls
        add(createKeyframeSection());

        // Keyframe list
        add(createKeyframeListSection());

        // Info about export
        Label exportInfo = new Label("Use the Export tab to export animation frames.");
        exportInfo.getStyleClass().add("hint-label");
        add(exportInfo);
    }

    private TitledPane createSettingsSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        // Duration
        grid.add(new Label("Duration (s):"), 0, 0);
        durationSpinner = new Spinner<>(1.0, 300.0, timeline.getDuration(), 1.0);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(80);
        durationSpinner.valueProperty().addListener((obs, old, val) -> {
            timeline.setDuration(val);
            updateTimeSlider();
        });
        grid.add(durationSpinner, 1, 0);

        // FPS
        grid.add(new Label("FPS:"), 0, 1);
        fpsSpinner = new Spinner<>(1, 120, (int) timeline.getFrameRate(), 1);
        fpsSpinner.setPrefWidth(80);
        fpsSpinner.valueProperty().addListener((obs, old, val) -> timeline.setFrameRate(val));
        grid.add(fpsSpinner, 1, 1);

        // Loop
        loopCheckbox = new CheckBox("Loop");
        loopCheckbox.setSelected(timeline.isLooping());
        loopCheckbox.selectedProperty().addListener((obs, old, val) -> timeline.setLooping(val));
        grid.add(loopCheckbox, 0, 2, 2, 1);

        TitledPane pane = new TitledPane("Settings", grid);
        pane.setCollapsible(true);
        return pane;
    }

    private HBox createTransportSection() {
        HBox transport = new HBox(5);
        transport.setAlignment(Pos.CENTER);

        Button startBtn = new Button("\u23EE");
        startBtn.setOnAction(e -> {
            timeline.goToStart();
            updateUI();
            triggerRender();
        });

        Button prevFrameBtn = new Button("\u25C0");
        prevFrameBtn.setOnAction(e -> {
            timeline.previousFrame();
            updateUI();
            triggerRender();
        });

        playButton = new Button("\u25B6");
        playButton.setPrefWidth(50);
        playButton.setOnAction(e -> {
            if (timeline.isPlaying()) {
                timeline.pause();
            } else {
                timeline.play();
            }
            updatePlayButton();
        });

        Button stopButton = new Button("\u23F9");
        stopButton.setOnAction(e -> {
            timeline.stop();
            updatePlayButton();
            updateUI();
            triggerRender();
        });

        Button nextFrameBtn = new Button("\u25B6");
        nextFrameBtn.setOnAction(e -> {
            timeline.nextFrame();
            updateUI();
            triggerRender();
        });

        Button endBtn = new Button("\u23ED");
        endBtn.setOnAction(e -> {
            timeline.goToEnd();
            updateUI();
            triggerRender();
        });

        transport.getChildren().addAll(startBtn, prevFrameBtn, playButton, stopButton, nextFrameBtn, endBtn);
        return transport;
    }

    private VBox createTimeSliderSection() {
        VBox box = new VBox(5);

        HBox labels = new HBox();
        labels.setAlignment(Pos.CENTER);
        timeLabel = new Label("0.000s");
        timeLabel.getStyleClass().add("mono-label");
        frameLabel = new Label(" (Frame 0/0)");
        frameLabel.getStyleClass().add("mono-label");
        labels.getChildren().addAll(timeLabel, frameLabel);

        timeSlider = new Slider(0, timeline.getDuration(), 0);
        timeSlider.setBlockIncrement(timeline.getFrameDuration());
        timeSlider.valueProperty().addListener((obs, old, val) -> {
            if (timeSlider.isValueChanging() || Math.abs(val.doubleValue() - old.doubleValue()) > 0.001) {
                timeline.setCurrentTime(val.doubleValue());
                updateTimeLabels();
                if (!timeline.isPlaying()) {
                    applyTimelineToParams();
                    triggerRender();
                }
            }
        });

        box.getChildren().addAll(labels, timeSlider);
        return box;
    }

    private TitledPane createKeyframeSection() {
        VBox box = new VBox(5);

        // Easing selection
        HBox easingBox = new HBox(5);
        easingBox.setAlignment(Pos.CENTER_LEFT);
        easingBox.getChildren().add(new Label("Easing:"));
        easingCombo = new ComboBox<>();
        easingCombo.getItems().addAll(Easing.values());
        easingCombo.setValue(Easing.EASE_IN_OUT_CUBIC);
        easingBox.getChildren().add(easingCombo);
        box.getChildren().add(easingBox);

        // Keyframe buttons
        HBox buttons = new HBox(5);
        buttons.setAlignment(Pos.CENTER);

        Button addKeyBtn = new Button("+ Add Keyframe");
        addKeyBtn.setOnAction(e -> addKeyframeAtCurrentTime());

        Button removeKeyBtn = new Button("- Remove");
        removeKeyBtn.setOnAction(e -> removeSelectedKeyframe());

        buttons.getChildren().addAll(addKeyBtn, removeKeyBtn);
        box.getChildren().add(buttons);

        // Info about what gets saved
        Label infoLabel = new Label("Saves: Camera, FOV, DoF, Light Dir, Base Hue");
        infoLabel.getStyleClass().add("hint-label");
        box.getChildren().add(infoLabel);

        TitledPane pane = new TitledPane("Keyframes", box);
        pane.setCollapsible(true);
        return pane;
    }

    private VBox createKeyframeListSection() {
        VBox box = new VBox(5);

        keyframeList = new ListView<>();
        keyframeList.setPrefHeight(150);
        keyframeList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                jumpToSelectedKeyframe();
            }
        });

        box.getChildren().add(new Label("Keyframes (double-click to jump):"));
        box.getChildren().add(keyframeList);
        return box;
    }

    private void setupPlaybackTimer() {
        playbackTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!timeline.isPlaying()) return;

                if (lastFrameTime == 0) {
                    lastFrameTime = now;
                    return;
                }

                double deltaTime = (now - lastFrameTime) / 1_000_000_000.0;
                lastFrameTime = now;

                timeline.update(deltaTime);
                updateUI();
                applyTimelineToParams();
                triggerRender();

                if (!timeline.isPlaying()) {
                    updatePlayButton();
                }
            }
        };
        playbackTimer.start();

        timeline.addPlayStateListener(() -> {
            if (timeline.isPlaying()) {
                lastFrameTime = 0;
            }
        });
    }

    private void addKeyframeAtCurrentTime() {
        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        double time = timeline.getCurrentTime();
        Easing easing = easingCombo.getValue();
        Camera camera = params.getCamera();

        // Camera position
        float[] pos = camera.getPosition();
        timeline.setKeyframe("camPos", time, new float[]{pos[0], pos[1], pos[2]}, easing);

        // Camera rotation
        float[] quat = camera.getQuaternion();
        timeline.setKeyframe("camQuat", time, new float[]{quat[0], quat[1], quat[2], quat[3]}, easing);

        // FOV
        timeline.setKeyframe("fov", time, (float) Math.toDegrees(params.getFov()), easing);

        // Depth of Field
        timeline.setKeyframe("focalDistance", time, params.getFocalDistance(), easing);
        timeline.setKeyframe("aperture", time, params.getAperture(), easing);

        // Light direction
        timeline.setKeyframe("lightDir", time, new float[]{
                params.getLightX(), params.getLightY(), params.getLightZ()
        }, easing);

        // Base hue / material hue
        timeline.setKeyframe("baseHue", time, new float[]{
                params.getHueR(), params.getHueG(), params.getHueB()
        }, easing);

        updateKeyframeList();
    }

    private void removeSelectedKeyframe() {
        String selected = keyframeList.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        // Parse time from selection (format: "0.000s - Camera, FOV, ...")
        try {
            String timeStr = selected.split("s")[0].trim();
            double time = Double.parseDouble(timeStr);

            for (String trackName : timeline.getTrackNames()) {
                timeline.getTrack(trackName).removeKeyframe(time);
            }
            updateKeyframeList();
        } catch (Exception e) {
            // Ignore parse errors
        }
    }

    private void jumpToSelectedKeyframe() {
        String selected = keyframeList.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        try {
            String timeStr = selected.split("s")[0].trim();
            double time = Double.parseDouble(timeStr);
            timeline.setCurrentTime(time);
            updateUI();
            applyTimelineToParams();
            triggerRender();
        } catch (Exception e) {
            // Ignore parse errors
        }
    }

    private void updateKeyframeList() {
        // Collect all unique keyframe times
        java.util.Set<Double> times = new java.util.TreeSet<>();
        for (String trackName : timeline.getTrackNames()) {
            for (var kf : timeline.getTrack(trackName).getKeyframes()) {
                times.add(kf.getTime());
            }
        }

        // Build new list fully before touching the ListView (avoids intermediate empty state
        // that causes IndexOutOfBoundsException when a stale cell receives a click)
        java.util.List<String> newItems = new java.util.ArrayList<>();
        for (double time : times) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%.3fs - ", time));

            java.util.List<String> tracks = new java.util.ArrayList<>();
            for (String trackName : timeline.getTrackNames()) {
                if (timeline.getTrack(trackName).getKeyframeAt(time) != null) {
                    tracks.add(trackName);
                }
            }
            sb.append(String.join(", ", tracks));
            newItems.add(sb.toString());
        }

        keyframeList.getSelectionModel().clearSelection();
        keyframeList.getItems().setAll(newItems);
    }

    /**
     * Apply timeline values to fractal parameters.
     * This is called during playback and when setting timeline time.
     */
    public void applyTimelineToParams() {
        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        Camera camera = params.getCamera();

        // Apply camera position if track has keyframes
        if (timeline.getTrack("camPos").hasKeyframes()) {
            float[] pos = timeline.getValue("camPos");
            camera.setPosition(pos[0], pos[1], pos[2]);
        }

        // Apply camera rotation if track has keyframes
        if (timeline.getTrack("camQuat").hasKeyframes()) {
            float[] quat = timeline.getValue("camQuat");
            camera.setQuaternion(quat[0], quat[1], quat[2], quat[3]);
        }

        // Apply FOV
        if (timeline.getTrack("fov").hasKeyframes()) {
            float fovDegrees = timeline.getValue("fov");
            params.fov(fovDegrees);
        }

        // Apply DoF parameters
        if (timeline.getTrack("focalDistance").hasKeyframes()) {
            float focalDist = timeline.getValue("focalDistance");
            params.setFocalDistance(focalDist);
        }

        if (timeline.getTrack("aperture").hasKeyframes()) {
            float apt = timeline.getValue("aperture");
            params.setAperture(apt);
        }

        // Apply light direction
        if (timeline.getTrack("lightDir").hasKeyframes()) {
            float[] lightDir = timeline.getValue("lightDir");
            params.lightDirection(lightDir[0], lightDir[1], lightDir[2]);
        }

        // Apply base hue
        if (timeline.getTrack("baseHue").hasKeyframes()) {
            float[] hue = timeline.getValue("baseHue");
            params.materialHue(hue[0], hue[1], hue[2]);
        }

        // Notify time change
        if (onTimeChange != null) {
            onTimeChange.accept(timeline.getCurrentTime());
        }
    }

    // ========================================================================
    // UI Updates
    // ========================================================================

    private void updateUI() {
        updateTimeSlider();
        updateTimeLabels();
    }

    private void updateTimeSlider() {
        timeSlider.setMax(timeline.getDuration());
        timeSlider.setBlockIncrement(timeline.getFrameDuration());
        if (!timeSlider.isValueChanging()) {
            timeSlider.setValue(timeline.getCurrentTime());
        }
    }

    private void updateTimeLabels() {
        timeLabel.setText(String.format("%.3fs", timeline.getCurrentTime()));
        frameLabel.setText(String.format(" (Frame %d/%d)", timeline.getCurrentFrame(), timeline.getTotalFrames()));
    }

    private void updatePlayButton() {
        playButton.setText(timeline.isPlaying() ? "\u23F8" : "\u25B6");
    }

    private void triggerRender() {
        if (onRenderRequest != null) {
            onRenderRequest.run();
        }
    }

    public Timeline getTimeline() {
        return timeline;
    }

    private void add(javafx.scene.Node node) {
        getChildren().add(node);
    }
}
