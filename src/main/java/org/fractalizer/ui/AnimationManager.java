package org.fractalizer.ui;

import org.fractalizer.animation.Easing;
import org.fractalizer.animation.Timeline;
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
     * Add a keyframe at the current time with all animatable parameters.
     */
    public void addKeyframeAtCurrentTime() {
        AbstractFractalParams params = paramsSupplier.get();
        if (params == null) return;

        double time = timeline.getCurrentTime();
        Easing easing = timelineWidget.getSelectedEasing();
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

        // Base hue
        timeline.setKeyframe("baseHue", time, new float[]{
                params.getHueR(), params.getHueG(), params.getHueB()
        }, easing);

        timelineWidget.refresh();
        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Keyframe added at %.2fs", time));
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
}
