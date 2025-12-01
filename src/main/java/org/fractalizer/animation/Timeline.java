package org.fractalizer.animation;

import java.util.*;
import java.util.function.Consumer;

/**
 * Timeline manages all animation tracks and handles playback.
 * Supports frame-based rendering for video export.
 */
public class Timeline {

    // Tracks by name
    private final Map<String, AnimationTrack<?>> tracks = new LinkedHashMap<>();

    // Playback state
    private double currentTime = 0;
    private double duration = 10.0;  // Default 10 seconds
    private double frameRate = 30.0;  // Default 30 FPS
    private boolean playing = false;
    private boolean looping = false;

    // Listeners
    private final List<Consumer<Double>> timeChangeListeners = new ArrayList<>();
    private final List<Runnable> playStateListeners = new ArrayList<>();

    public Timeline() {
    }

    public Timeline(double duration, double frameRate) {
        this.duration = duration;
        this.frameRate = frameRate;
    }

    // ========================================================================
    // Track Management
    // ========================================================================

    /**
     * Create and add a new animation track.
     */
    public <T> AnimationTrack<T> createTrack(String name, Class<T> valueType, T defaultValue) {
        AnimationTrack<T> track = new AnimationTrack<>(name, valueType, defaultValue);
        tracks.put(name, track);
        return track;
    }

    /**
     * Get a track by name.
     */
    @SuppressWarnings("unchecked")
    public <T> AnimationTrack<T> getTrack(String name) {
        return (AnimationTrack<T>) tracks.get(name);
    }

    /**
     * Check if a track exists.
     */
    public boolean hasTrack(String name) {
        return tracks.containsKey(name);
    }

    /**
     * Remove a track.
     */
    public void removeTrack(String name) {
        tracks.remove(name);
    }

    /**
     * Get all track names.
     */
    public Set<String> getTrackNames() {
        return Collections.unmodifiableSet(tracks.keySet());
    }

    /**
     * Get all tracks.
     */
    public Collection<AnimationTrack<?>> getTracks() {
        return Collections.unmodifiableCollection(tracks.values());
    }

    // ========================================================================
    // Value Access
    // ========================================================================

    /**
     * Get the current value of a track (at current time).
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String trackName) {
        AnimationTrack<T> track = (AnimationTrack<T>) tracks.get(trackName);
        if (track == null) {
            throw new IllegalArgumentException("Unknown track: " + trackName);
        }
        return track.getValue(currentTime);
    }

    /**
     * Get the value of a track at a specific time.
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String trackName, double time) {
        AnimationTrack<T> track = (AnimationTrack<T>) tracks.get(trackName);
        if (track == null) {
            throw new IllegalArgumentException("Unknown track: " + trackName);
        }
        return track.getValue(time);
    }

    /**
     * Get current values of all tracks as a map.
     */
    public Map<String, Object> getAllValues() {
        return getAllValues(currentTime);
    }

    /**
     * Get values of all tracks at a specific time.
     */
    public Map<String, Object> getAllValues(double time) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, AnimationTrack<?>> entry : tracks.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getValue(time));
        }
        return values;
    }

    // ========================================================================
    // Keyframe Shortcuts
    // ========================================================================

    /**
     * Set a keyframe on a track at the current time.
     */
    @SuppressWarnings("unchecked")
    public <T> void setKeyframe(String trackName, T value) {
        setKeyframe(trackName, currentTime, value, Easing.LINEAR);
    }

    /**
     * Set a keyframe on a track.
     */
    @SuppressWarnings("unchecked")
    public <T> void setKeyframe(String trackName, double time, T value) {
        setKeyframe(trackName, time, value, Easing.LINEAR);
    }

    /**
     * Set a keyframe on a track with easing.
     */
    @SuppressWarnings("unchecked")
    public <T> void setKeyframe(String trackName, double time, T value, Easing easing) {
        AnimationTrack<T> track = (AnimationTrack<T>) tracks.get(trackName);
        if (track == null) {
            throw new IllegalArgumentException("Unknown track: " + trackName);
        }
        track.setKeyframe(time, value, easing);
    }

    // ========================================================================
    // Time Control
    // ========================================================================

    public double getCurrentTime() {
        return currentTime;
    }

    public void setCurrentTime(double time) {
        this.currentTime = Math.max(0, Math.min(duration, time));
        notifyTimeChange();
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = Math.max(0.1, duration);
        if (currentTime > this.duration) {
            setCurrentTime(this.duration);
        }
    }

    public double getFrameRate() {
        return frameRate;
    }

    public void setFrameRate(double frameRate) {
        this.frameRate = Math.max(1, Math.min(120, frameRate));
    }

    public int getTotalFrames() {
        return (int) Math.ceil(duration * frameRate);
    }

    public int getCurrentFrame() {
        return (int) (currentTime * frameRate);
    }

    public void setCurrentFrame(int frame) {
        setCurrentTime(frame / frameRate);
    }

    public double getFrameDuration() {
        return 1.0 / frameRate;
    }

    // ========================================================================
    // Playback
    // ========================================================================

    public boolean isPlaying() {
        return playing;
    }

    public void play() {
        if (!playing) {
            playing = true;
            notifyPlayStateChange();
        }
    }

    public void pause() {
        if (playing) {
            playing = false;
            notifyPlayStateChange();
        }
    }

    public void stop() {
        playing = false;
        currentTime = 0;
        notifyPlayStateChange();
        notifyTimeChange();
    }

    public void togglePlay() {
        if (playing) {
            pause();
        } else {
            play();
        }
    }

    public boolean isLooping() {
        return looping;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    /**
     * Advance time by delta seconds.
     * Call this from your main loop when playing.
     */
    public void update(double deltaTime) {
        if (!playing) return;

        currentTime += deltaTime;

        if (currentTime >= duration) {
            if (looping) {
                currentTime = currentTime % duration;
            } else {
                currentTime = duration;
                playing = false;
                notifyPlayStateChange();
            }
        }

        notifyTimeChange();
    }

    /**
     * Advance to the next frame.
     */
    public void nextFrame() {
        setCurrentTime(currentTime + getFrameDuration());
    }

    /**
     * Go to the previous frame.
     */
    public void previousFrame() {
        setCurrentTime(currentTime - getFrameDuration());
    }

    /**
     * Go to the first frame.
     */
    public void goToStart() {
        setCurrentTime(0);
    }

    /**
     * Go to the last frame.
     */
    public void goToEnd() {
        setCurrentTime(duration);
    }

    // ========================================================================
    // Frame Export Helper
    // ========================================================================

    /**
     * Iterate over all frames for export.
     * @param frameConsumer Called for each frame with frame index and time
     */
    public void forEachFrame(FrameConsumer frameConsumer) {
        int totalFrames = getTotalFrames();
        for (int frame = 0; frame < totalFrames; frame++) {
            double time = frame / frameRate;
            frameConsumer.accept(frame, time, getAllValues(time));
        }
    }

    @FunctionalInterface
    public interface FrameConsumer {
        void accept(int frameIndex, double time, Map<String, Object> values);
    }

    // ========================================================================
    // Listeners
    // ========================================================================

    public void addTimeChangeListener(Consumer<Double> listener) {
        timeChangeListeners.add(listener);
    }

    public void removeTimeChangeListener(Consumer<Double> listener) {
        timeChangeListeners.remove(listener);
    }

    public void addPlayStateListener(Runnable listener) {
        playStateListeners.add(listener);
    }

    public void removePlayStateListener(Runnable listener) {
        playStateListeners.remove(listener);
    }

    private void notifyTimeChange() {
        for (Consumer<Double> listener : timeChangeListeners) {
            listener.accept(currentTime);
        }
    }

    private void notifyPlayStateChange() {
        for (Runnable listener : playStateListeners) {
            listener.run();
        }
    }

    // ========================================================================
    // Utility
    // ========================================================================

    /**
     * Calculate actual duration from keyframes (time of last keyframe across all tracks).
     */
    public double calculateDurationFromKeyframes() {
        double maxTime = 0;
        for (AnimationTrack<?> track : tracks.values()) {
            maxTime = Math.max(maxTime, track.getEndTime());
        }
        return maxTime > 0 ? maxTime : duration;
    }

    /**
     * Clear all tracks.
     */
    public void clear() {
        tracks.clear();
        currentTime = 0;
        playing = false;
    }

    /**
     * Create a copy of this timeline.
     */
    public Timeline copy() {
        Timeline copy = new Timeline(duration, frameRate);
        copy.looping = looping;
        for (Map.Entry<String, AnimationTrack<?>> entry : tracks.entrySet()) {
            copy.tracks.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    @Override
    public String toString() {
        return String.format("Timeline[%.1fs, %.0ffps, %d tracks, t=%.3f%s]",
            duration, frameRate, tracks.size(), currentTime, playing ? " PLAYING" : "");
    }
}
