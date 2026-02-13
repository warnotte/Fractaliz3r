package org.fractalizer.audio;

/**
 * Audio analysis engine for reactive fractal rendering.
 * <p>
 * Receives FFT magnitudes from JavaFX AudioSpectrumListener,
 * extracts 8 frequency bands, detects beats and onsets,
 * and publishes smoothed data via an immutable record.
 */
public class AudioReactiveEngine {

    // 8 named frequency bands (sub-bass → air)
    private static final int NUM_BANDS = 8;

    // Smoothed band levels
    private final float[] bands = new float[NUM_BANDS];

    // Beat detection
    private final float[] energyHistory = new float[30]; // ~1s at 30fps
    private int energyIndex = 0;
    private float beatValue = 0f;
    private float beatDecay = 0.85f;

    // Onset detection (spectral flux)
    private float[] previousMagnitudes = null;
    private float onsetValue = 0f;
    private float onsetDecay = 0.8f;

    // Overall level
    private float level = 0f;

    // Configurable parameters
    private float attack = 0.7f;        // EMA factor when signal rises (0 = instant, 0.99 = very slow)
    private float release = 0.7f;       // EMA factor when signal falls (0 = instant, 0.99 = very slow)
    private float sensitivity = 0.5f;   // Beat detection sensitivity (0-1)

    // Exposed beat detection state (for visualization)
    private float lastBeatThreshold = 0f;
    private float lastBeatEnergy = 0f;

    // Latest published data
    private volatile AudioData latestData = AudioData.SILENT;

    /**
     * Immutable snapshot of audio analysis results.
     */
    public record AudioData(
            float[] bands,   // 8 frequency bands [0..1]
            float level,     // Overall RMS level [0..1]
            float beat,      // Beat pulse [0..1], decays between beats
            float onset      // Onset/transient pulse [0..1]
    ) {
        public static final AudioData SILENT = new AudioData(
                new float[NUM_BANDS], 0f, 0f, 0f
        );
    }

    /**
     * Process spectrum data from AudioSpectrumListener.
     *
     * @param magnitudes Array of magnitude values in dB (negative, typically -60 to 0)
     * @param phases     Array of phase values (unused for now, kept for future use)
     */
    public void processSpectrum(float[] magnitudes, float[] phases) {
        if (magnitudes == null || magnitudes.length == 0) return;

        int numBins = magnitudes.length;

        // --- 1. Extract 8 bands ---
        // Band boundaries as fractions of the spectrum (logarithmic-ish distribution)
        // Sub-bass, Bass, Low-mid, Mid, Upper-mid, Presence, Brilliance, Air
        float[] boundaries = {0.0f, 0.02f, 0.05f, 0.1f, 0.2f, 0.35f, 0.55f, 0.75f, 1.0f};

        float totalEnergy = 0f;
        float[] rawBands = new float[NUM_BANDS];

        for (int b = 0; b < NUM_BANDS; b++) {
            int startBin = (int) (boundaries[b] * numBins);
            int endBin = (int) (boundaries[b + 1] * numBins);
            if (endBin <= startBin) endBin = startBin + 1;
            if (endBin > numBins) endBin = numBins;

            float sum = 0f;
            int count = 0;
            for (int i = startBin; i < endBin; i++) {
                // Convert from dB to linear (magnitudes are typically -60..0 dB)
                float linear = dbToLinear(magnitudes[i]);
                sum += linear;
                count++;
            }

            float avg = (count > 0) ? sum / count : 0f;
            rawBands[b] = avg;
            totalEnergy += avg;
        }

        // --- 2. Smooth bands with attack/release EMA ---
        for (int b = 0; b < NUM_BANDS; b++) {
            float coeff = (rawBands[b] > bands[b]) ? attack : release;
            bands[b] = coeff * bands[b] + (1f - coeff) * rawBands[b];
        }

        // --- 3. Overall level ---
        float rawLevel = totalEnergy / NUM_BANDS;
        float levelCoeff = (rawLevel > level) ? attack : release;
        level = levelCoeff * level + (1f - levelCoeff) * rawLevel;

        // --- 4. Beat detection (energy-based) ---
        float currentEnergy = rawBands[0] + rawBands[1]; // Sub-bass + Bass
        energyHistory[energyIndex % energyHistory.length] = currentEnergy;
        energyIndex++;

        float avgEnergy = 0f;
        for (float e : energyHistory) avgEnergy += e;
        avgEnergy /= energyHistory.length;

        // Threshold: higher sensitivity = easier to trigger
        float threshold = avgEnergy * (2.0f - sensitivity * 1.5f);
        lastBeatThreshold = threshold;
        lastBeatEnergy = currentEnergy;
        if (currentEnergy > threshold && avgEnergy > 0.01f) {
            beatValue = Math.min(1.0f, currentEnergy / Math.max(avgEnergy, 0.01f) - 0.5f);
        } else {
            beatValue *= beatDecay;
        }

        // --- 5. Onset detection (spectral flux) ---
        if (previousMagnitudes != null && previousMagnitudes.length == numBins) {
            float flux = 0f;
            for (int i = 0; i < numBins; i++) {
                float diff = dbToLinear(magnitudes[i]) - dbToLinear(previousMagnitudes[i]);
                if (diff > 0) flux += diff; // Only positive flux (onsets, not offsets)
            }
            float normalizedFlux = Math.min(1.0f, flux * (1.0f + sensitivity * 3.0f));
            onsetValue = Math.max(onsetValue * onsetDecay, normalizedFlux);
        }
        previousMagnitudes = magnitudes.clone();

        // --- 6. Publish immutable snapshot ---
        latestData = new AudioData(bands.clone(), level, beatValue, onsetValue);
    }

    /**
     * Get the latest audio analysis data.
     */
    public AudioData getLatestData() {
        return latestData;
    }

    /**
     * Reset to silent state.
     */
    public void reset() {
        for (int i = 0; i < NUM_BANDS; i++) bands[i] = 0f;
        for (int i = 0; i < energyHistory.length; i++) energyHistory[i] = 0f;
        energyIndex = 0;
        beatValue = 0f;
        onsetValue = 0f;
        level = 0f;
        previousMagnitudes = null;
        latestData = AudioData.SILENT;
    }

    public float getAttack() {
        return attack;
    }

    public void setAttack(float attack) {
        this.attack = Math.max(0f, Math.min(0.99f, attack));
    }

    public float getRelease() {
        return release;
    }

    public void setRelease(float release) {
        this.release = Math.max(0f, Math.min(0.99f, release));
    }

    /**
     * Convenience method: sets both attack and release to the same value.
     * Used by AudioPreAnalyzer for offline export compatibility.
     */
    public void setSmoothing(float smoothing) {
        float clamped = Math.max(0f, Math.min(0.99f, smoothing));
        this.attack = clamped;
        this.release = clamped;
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = Math.max(0f, Math.min(1f, sensitivity));
    }

    /** Get the last computed beat detection threshold (for visualization). */
    public float getLastBeatThreshold() {
        return lastBeatThreshold;
    }

    /** Get the last beat energy value (for visualization). */
    public float getLastBeatEnergy() {
        return lastBeatEnergy;
    }

    /**
     * Convert decibel value to perceptual linear [0..1].
     * Maps the full dB range linearly for audio-reactive use:
     *   -60 dB → 0.0 (silence), 0 dB → 1.0 (maximum).
     * This gives values in a useful range for modulation (~0.3-0.7 for typical music)
     * instead of the physical pow(10, dB/20) which clusters everything near 0.
     */
    private static float dbToLinear(float db) {
        float clamped = Math.max(-60f, Math.min(0f, db));
        return (clamped + 60f) / 60f;
    }
}
