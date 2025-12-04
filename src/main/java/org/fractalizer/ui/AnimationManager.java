package org.fractalizer.ui;

import org.fractalizer.animation.AnimationTrack;
import org.fractalizer.animation.Easing;
import org.fractalizer.animation.Timeline;
import org.fractalizer.config.FractalConfig;
import org.fractalizer.engine.Camera;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.ui.timeline.TimelineWidget;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages animation timeline and keyframe operations.
 * Extracted from GLSLFractalizerApp to simplify the main application class.
 */
public class AnimationManager {

    private final Timeline timeline;
    private final TimelineWidget timelineWidget;
    private final Supplier<AbstractFractalParams> paramsSupplier;
    private final Runnable onRenderRequest;
    private final Consumer<String> statusUpdater;

    // Playback state
    private long lastPlaybackTime = 0;

    public AnimationManager(Supplier<AbstractFractalParams> paramsSupplier,
                            Runnable onRenderRequest,
                            Runnable onPositionUpdate,
                            Consumer<String> statusUpdater) {
        this.paramsSupplier = paramsSupplier;
        this.onRenderRequest = onRenderRequest;
        this.statusUpdater = statusUpdater;

        // Create timeline with default settings
        this.timeline = new Timeline(10.0, 30.0);
        initializeTimelineTracks();

        // Create timeline widget
        this.timelineWidget = new TimelineWidget(timeline);
        timelineWidget.setOnRenderRequest(onRenderRequest);
        timelineWidget.setOnTimeChange(time -> {
            applyTimelineToParams();
            if (onPositionUpdate != null) {
                onPositionUpdate.run();
            }
        });
        timelineWidget.setOnKeyframeAdded(this::addKeyframeAtCurrentTime);
        timelineWidget.setOnKeyframeUpdated(this::updateSelectedKeyframe);
    }

    private void initializeTimelineTracks() {
        // Camera tracks
        timeline.createTrack("camPos", float[].class, new float[]{0, 0, -3});
        timeline.createTrack("camQuat", float[].class, new float[]{0, 0, 0, 1});

        // Common params
        timeline.createTrack("fov", Float.class, 60.0f);

        // Depth of Field
        timeline.createTrack("focalDistance", Float.class, 2.5f);
        timeline.createTrack("aperture", Float.class, 0.02f);

        // Light direction
        timeline.createTrack("lightDir", float[].class, new float[]{1.0f, 1.0f, 0.5f});

        // Base hue
        timeline.createTrack("baseHue", float[].class, new float[]{0.6f, 0.3f, 0.1f});
    }

    /**
     * Add a keyframe at the current time.
     * If a track is selected, only add for that track.
     * Otherwise, add for all tracks.
     */
    public void addKeyframeAtCurrentTime() {
        String selectedTrack = timelineWidget.getSelectedTrackName();
        if (selectedTrack != null) {
            // Add only for selected track
            addKeyframeForSelectedTrack();
        } else {
            // Add for all tracks
            addKeyframeForAllTracks();
        }
    }

    /**
     * Add keyframes for all tracks at the current time.
     */
    public void addKeyframeForAllTracks() {
        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        double time = timeline.getCurrentTime();
        Easing easing = timelineWidget.getSelectedEasing();

        // Add keyframes for all tracks
        addKeyframeForTrack("camPos", time, easing, params);
        addKeyframeForTrack("camQuat", time, easing, params);
        addKeyframeForTrack("fov", time, easing, params);
        addKeyframeForTrack("focalDistance", time, easing, params);
        addKeyframeForTrack("aperture", time, easing, params);
        addKeyframeForTrack("lightDir", time, easing, params);
        addKeyframeForTrack("baseHue", time, easing, params);

        timelineWidget.refresh();
        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Keyframes added at %.2fs", time));
        }
    }

    /**
     * Update only the selected track's keyframe at the current time.
     * If no track is selected, adds for all tracks.
     */
    public void updateSelectedKeyframe() {
        String selectedTrack = timelineWidget.getSelectedTrackName();
        if (selectedTrack == null) {
            // No keyframe selected, add all
            addKeyframeForAllTracks();
            return;
        }
        addKeyframeForSelectedTrack();
    }

    /**
     * Add/update keyframe only for the selected track.
     */
    private void addKeyframeForSelectedTrack() {
        String selectedTrack = timelineWidget.getSelectedTrackName();
        if (selectedTrack == null) return;

        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        double time = timeline.getCurrentTime();
        Easing easing = timelineWidget.getSelectedEasing();

        // Update only the selected track
        addKeyframeForTrack(selectedTrack, time, easing, params);

        timelineWidget.refresh();
        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Updated %s at %.2fs", selectedTrack, time));
        }
    }

    /**
     * Add a keyframe for a specific track.
     */
    private void addKeyframeForTrack(String trackName, double time, Easing easing, AbstractFractalParams params) {
        Camera camera = params.getCamera();

        switch (trackName) {
            case "camPos" -> {
                float[] pos = camera.getPosition();
                timeline.setKeyframe("camPos", time, new float[]{pos[0], pos[1], pos[2]}, easing);
            }
            case "camQuat" -> {
                float[] quat = camera.getQuaternion();
                timeline.setKeyframe("camQuat", time, new float[]{quat[0], quat[1], quat[2], quat[3]}, easing);
            }
            case "fov" -> timeline.setKeyframe("fov", time, (float) Math.toDegrees(params.getFov()), easing);
            case "focalDistance" -> timeline.setKeyframe("focalDistance", time, params.getFocalDistance(), easing);
            case "aperture" -> timeline.setKeyframe("aperture", time, params.getAperture(), easing);
            case "lightDir" -> timeline.setKeyframe("lightDir", time, new float[]{
                    params.getLightX(), params.getLightY(), params.getLightZ()
            }, easing);
            case "baseHue" -> timeline.setKeyframe("baseHue", time, new float[]{
                    params.getHueR(), params.getHueG(), params.getHueB()
            }, easing);
        }
    }

    /**
     * Apply current timeline values to fractal parameters.
     */
    public void applyTimelineToParams() {
        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        Camera camera = params.getCamera();

        // Apply camera position
        if (timeline.getTrack("camPos").hasKeyframes()) {
            float[] pos = timeline.getValue("camPos");
            camera.setPosition(pos[0], pos[1], pos[2]);
        }

        // Apply camera rotation
        if (timeline.getTrack("camQuat").hasKeyframes()) {
            float[] quat = timeline.getValue("camQuat");
            camera.setQuaternion(quat[0], quat[1], quat[2], quat[3]);
        }

        // Apply FOV
        if (timeline.getTrack("fov").hasKeyframes()) {
            float fovDegrees = timeline.getValue("fov");
            params.fov(fovDegrees);
        }

        // Apply DoF
        if (timeline.getTrack("focalDistance").hasKeyframes()) {
            params.setFocalDistance(timeline.getValue("focalDistance"));
        }
        if (timeline.getTrack("aperture").hasKeyframes()) {
            params.setAperture(timeline.getValue("aperture"));
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
    }

    /**
     * Update playback state. Call this from the render loop.
     * @param now Current time in nanoseconds
     * @return true if a render is needed
     */
    public boolean updatePlayback(long now) {
        if (!timeline.isPlaying()) {
            lastPlaybackTime = 0;
            return false;
        }

        if (lastPlaybackTime == 0) {
            lastPlaybackTime = now;
            return false;
        }

        double deltaTime = (now - lastPlaybackTime) / 1_000_000_000.0;
        lastPlaybackTime = now;
        timeline.update(deltaTime);
        applyTimelineToParams();
        timelineWidget.refresh();
        return true;
    }

    /**
     * Check if animation is currently playing.
     */
    public boolean isPlaying() {
        return timeline.isPlaying();
    }

    /**
     * Get the timeline widget for adding to the UI.
     */
    public TimelineWidget getWidget() {
        return timelineWidget;
    }

    /**
     * Get the timeline for export operations.
     */
    public Timeline getTimeline() {
        return timeline;
    }

    /**
     * Refresh the timeline widget display.
     */
    public void refresh() {
        timelineWidget.refresh();
    }

    // ========================================================================
    // Animation Save/Load Support
    // ========================================================================

    /**
     * Export timeline to AnimationConfig for saving.
     * Returns null if no keyframes exist.
     */
    public FractalConfig.AnimationConfig exportAnimation() {
        // Check if any track has keyframes
        boolean hasKeyframes = false;
        for (var track : timeline.getTracks()) {
            if (track.hasKeyframes()) {
                hasKeyframes = true;
                break;
            }
        }
        if (!hasKeyframes) {
            return null;
        }

        FractalConfig.AnimationConfig config = new FractalConfig.AnimationConfig();
        config.duration = timeline.getDuration();
        config.frameRate = timeline.getFrameRate();
        config.looping = timeline.isLooping();

        for (var track : timeline.getTracks()) {
            if (!track.hasKeyframes()) continue;

            FractalConfig.TrackConfig trackConfig = new FractalConfig.TrackConfig();
            trackConfig.name = track.getName();
            trackConfig.valueType = track.getValueType().getSimpleName();
            trackConfig.defaultValue = convertValueForJson(track.getDefaultValue());

            for (var keyframe : track.getKeyframes()) {
                FractalConfig.KeyframeConfig kfConfig = new FractalConfig.KeyframeConfig();
                kfConfig.time = keyframe.getTime();
                kfConfig.value = convertValueForJson(keyframe.getValue());
                kfConfig.easing = keyframe.getEasing().name();
                trackConfig.keyframes.add(kfConfig);
            }

            config.tracks.add(trackConfig);
        }

        return config;
    }

    /**
     * Import animation from AnimationConfig.
     */
    public void importAnimation(FractalConfig.AnimationConfig config) {
        if (config == null) return;

        // Clear existing keyframes but keep track definitions
        for (var track : timeline.getTracks()) {
            track.clear();
        }

        // Apply timeline settings
        timeline.setDuration(config.duration);
        timeline.setFrameRate(config.frameRate);
        timeline.setLooping(config.looping);

        // Import tracks
        for (FractalConfig.TrackConfig trackConfig : config.tracks) {
            var track = timeline.getTrack(trackConfig.name);
            if (track == null) {
                // Create track if it doesn't exist
                Class<?> valueType = getTypeFromName(trackConfig.valueType);
                if (valueType == null) continue;
                track = createTrackForType(trackConfig.name, valueType, trackConfig.defaultValue);
            }

            // Import keyframes
            for (FractalConfig.KeyframeConfig kfConfig : trackConfig.keyframes) {
                Easing easing = Easing.LINEAR;
                try {
                    easing = Easing.valueOf(kfConfig.easing);
                } catch (IllegalArgumentException ignored) {}

                Object value = convertValueFromJson(kfConfig.value, track.getValueType());
                setKeyframeTyped(track, kfConfig.time, value, easing);
            }
        }

        timeline.setCurrentTime(0);
        timelineWidget.refresh();
    }

    /**
     * Check if timeline has any keyframes.
     */
    public boolean hasKeyframes() {
        for (var track : timeline.getTracks()) {
            if (track.hasKeyframes()) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> AnimationTrack<T> createTrackForType(String name, Class<?> valueType, Object defaultValue) {
        Object converted = convertValueFromJson(defaultValue, valueType);
        return (AnimationTrack<T>) timeline.createTrack(name, (Class<T>) valueType, (T) converted);
    }

    @SuppressWarnings("unchecked")
    private <T> void setKeyframeTyped(AnimationTrack<?> track, double time, Object value, Easing easing) {
        ((AnimationTrack<Object>) track).setKeyframe(time, value, easing);
    }

    private Object convertValueForJson(Object value) {
        if (value instanceof float[] arr) {
            // Convert float[] to List<Double> for JSON
            java.util.List<Double> list = new java.util.ArrayList<>();
            for (float v : arr) {
                list.add((double) v);
            }
            return list;
        }
        return value;
    }

    private Object convertValueFromJson(Object value, Class<?> targetType) {
        if (targetType == float[].class && value instanceof java.util.List<?> list) {
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = ((Number) list.get(i)).floatValue();
            }
            return arr;
        } else if (targetType == Float.class && value instanceof Number n) {
            return n.floatValue();
        } else if (targetType == Double.class && value instanceof Number n) {
            return n.doubleValue();
        } else if (targetType == Integer.class && value instanceof Number n) {
            return n.intValue();
        }
        return value;
    }

    private Class<?> getTypeFromName(String typeName) {
        return switch (typeName) {
            case "Float" -> Float.class;
            case "Double" -> Double.class;
            case "Integer" -> Integer.class;
            case "float[]" -> float[].class;
            default -> null;
        };
    }
}
