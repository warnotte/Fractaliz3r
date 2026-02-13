package org.fractalizer.audio;

import org.fractalizer.audio.AudioReactiveEngine.AudioData;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Offline audio pre-analyzer. Decodes an audio file to raw PCM via FFmpeg,
 * then computes FFT at each frame position to produce an AudioData array
 * that exactly matches what AudioSpectrumListener would have provided.
 * <p>
 * This enables offline, frame-by-frame rendering with perfect audio sync.
 */
public class AudioPreAnalyzer {

    private static final int SAMPLE_RATE = 44100;
    // FFT size must match JavaFX AudioSpectrumListener (interval=0.033s → ~1455 samples → next pow2 = 2048)
    private static final int FFT_SIZE = 2048;
    private static final int NUM_SPECTRUM_BANDS = 128; // Same as AudioSpectrumListener config
    private static final float SPECTRUM_THRESHOLD = -60f; // dB floor

    /**
     * Pre-analyze an entire audio file, producing one AudioData per frame.
     *
     * @param audioFile   Audio file (MP3, WAV, AAC)
     * @param fps         Target frames per second
     * @param maxDuration Maximum duration in seconds (0 = full file)
     * @param smoothing   EMA smoothing factor (0-0.99)
     * @param sensitivity Beat detection sensitivity (0-1)
     * @param progress    Progress callback (0.0 to 1.0), called on caller's thread
     * @return Array of AudioData, one per frame
     * @throws IOException If FFmpeg fails or file cannot be read
     */
    public static AudioData[] analyze(File audioFile, double fps, double maxDuration,
                                       float smoothing, float sensitivity,
                                       Consumer<Double> progress) throws IOException {
        // Phase 1: Decode audio to PCM
        float[] pcm = decodeToMono(audioFile);
        if (pcm.length == 0) {
            throw new IOException("FFmpeg produced no audio data");
        }

        double totalDuration = (double) pcm.length / SAMPLE_RATE;
        if (maxDuration > 0 && maxDuration < totalDuration) {
            totalDuration = maxDuration;
        }

        int totalFrames = (int) Math.ceil(totalDuration * fps);
        if (totalFrames <= 0) {
            throw new IOException("No frames to analyze (duration: " + totalDuration + "s)");
        }

        System.out.printf("[AudioPreAnalyzer] %d samples (%.1fs), %d frames at %.1f fps%n",
                pcm.length, totalDuration, totalFrames, fps);

        // Phase 2: FFT analysis frame by frame
        AudioReactiveEngine engine = new AudioReactiveEngine();
        engine.setSmoothing(smoothing);
        engine.setSensitivity(sensitivity);

        AudioData[] results = new AudioData[totalFrames];
        double[] windowedSamples = new double[FFT_SIZE];
        double[] imaginary = new double[FFT_SIZE];
        float[] magnitudesDb = new float[NUM_SPECTRUM_BANDS];

        // Pre-compute Hann window coefficients
        double[] hannWindow = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            hannWindow[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }

        for (int frame = 0; frame < totalFrames; frame++) {
            double frameTime = frame / fps;
            int centerSample = (int) (frameTime * SAMPLE_RATE);
            int startSample = centerSample - FFT_SIZE / 2;

            // Extract window and apply Hann
            for (int i = 0; i < FFT_SIZE; i++) {
                int sampleIdx = startSample + i;
                if (sampleIdx >= 0 && sampleIdx < pcm.length) {
                    windowedSamples[i] = pcm[sampleIdx] * hannWindow[i];
                } else {
                    windowedSamples[i] = 0.0;
                }
                imaginary[i] = 0.0;
            }

            // FFT
            fft(windowedSamples, imaginary);

            // Convert to magnitude spectrum in dB, mapped to NUM_SPECTRUM_BANDS bins.
            // Matches GStreamer's spectrum plugin algorithm used by JavaFX:
            //   1. Average POWER (magnitude²) over bins in each band
            //   2. Convert to dB: 10*log10(avgPower)
            //   3. Normalize by FFT size: subtract 20*log10(N)
            int halfFFT = FFT_SIZE / 2;
            int binsPerBand = halfFFT / NUM_SPECTRUM_BANDS;
            double fftNormDb = 20.0 * Math.log10(FFT_SIZE);

            for (int b = 0; b < NUM_SPECTRUM_BANDS; b++) {
                int binStart = b * binsPerBand;
                int binEnd = binStart + binsPerBand;
                if (binEnd > halfFFT) binEnd = halfFFT;

                double sumPower = 0.0;
                int count = 0;
                for (int i = binStart; i < binEnd; i++) {
                    double re = windowedSamples[i];
                    double im = imaginary[i];
                    sumPower += re * re + im * im;
                    count++;
                }

                double avgPower = (count > 0) ? sumPower / count : 0.0;
                float db;
                if (avgPower > 1e-20) {
                    db = (float) (10.0 * Math.log10(avgPower) - fftNormDb);
                    db = Math.max(SPECTRUM_THRESHOLD, Math.min(0f, db));
                } else {
                    db = SPECTRUM_THRESHOLD;
                }
                magnitudesDb[b] = db;
            }

            // Feed to AudioReactiveEngine (same path as real-time)
            engine.processSpectrum(magnitudesDb, null);
            results[frame] = engine.getLatestData();

            // Progress callback
            if (progress != null && frame % 10 == 0) {
                progress.accept((double) frame / totalFrames);
            }
        }

        if (progress != null) {
            progress.accept(1.0);
        }

        // Diagnostic: print band value ranges so we can compare with real-time path
        float[] maxBands = new float[8];
        float maxLevel = 0, maxBeat = 0, maxOnset = 0;
        for (AudioData d : results) {
            for (int b = 0; b < Math.min(8, d.bands().length); b++) {
                maxBands[b] = Math.max(maxBands[b], d.bands()[b]);
            }
            maxLevel = Math.max(maxLevel, d.level());
            maxBeat = Math.max(maxBeat, d.beat());
            maxOnset = Math.max(maxOnset, d.onset());
        }
        System.out.printf("[AudioPreAnalyzer] Analysis complete: %d frames%n", totalFrames);
        System.out.printf("[AudioPreAnalyzer] Peak bands: [%.4f, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f, %.4f]%n",
                maxBands[0], maxBands[1], maxBands[2], maxBands[3],
                maxBands[4], maxBands[5], maxBands[6], maxBands[7]);
        System.out.printf("[AudioPreAnalyzer] Peak level=%.4f  beat=%.4f  onset=%.4f%n",
                maxLevel, maxBeat, maxOnset);

        return results;
    }

    /**
     * Decode an audio file to mono float32 PCM at 44100Hz using FFmpeg.
     */
    static float[] decodeToMono(File audioFile) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(audioFile.getAbsolutePath());
        command.add("-f");
        command.add("f32le");       // Raw 32-bit float, little-endian
        command.add("-acodec");
        command.add("pcm_f32le");
        command.add("-ac");
        command.add("1");           // Mono
        command.add("-ar");
        command.add(String.valueOf(SAMPLE_RATE));
        command.add("-v");
        command.add("error");       // Suppress info output on stderr
        command.add("pipe:1");      // Output to stdout

        System.out.println("[AudioPreAnalyzer] Decoding: " + audioFile.getName());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false); // Keep stderr separate
        Process process = pb.start();

        // Read raw bytes from stdout
        byte[] rawBytes;
        try (InputStream is = process.getInputStream()) {
            rawBytes = is.readAllBytes();
        }

        // Consume stderr to prevent blocking
        try (InputStream err = process.getErrorStream()) {
            String errOutput = new String(err.readAllBytes());
            if (!errOutput.isBlank()) {
                System.err.println("[AudioPreAnalyzer] FFmpeg: " + errOutput.trim());
            }
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("FFmpeg decode failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg decode interrupted");
        }

        // Convert bytes to floats (little-endian float32)
        int numSamples = rawBytes.length / 4;
        float[] samples = new float[numSamples];
        ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < numSamples; i++) {
            samples[i] = buffer.getFloat();
        }

        System.out.printf("[AudioPreAnalyzer] Decoded %d samples (%.1fs)%n",
                numSamples, (double) numSamples / SAMPLE_RATE);
        return samples;
    }

    /**
     * In-place Cooley-Tukey radix-2 FFT.
     * Arrays must have length that is a power of 2.
     */
    private static void fft(double[] re, double[] im) {
        int n = re.length;

        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double temp = re[i]; re[i] = re[j]; re[j] = temp;
                temp = im[i]; im[i] = im[j]; im[j] = temp;
            }
        }

        // FFT butterfly operations
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);

            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                int half = len / 2;

                for (int j = 0; j < half; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i + j + half] * curRe - im[i + j + half] * curIm;
                    double vIm = re[i + j + half] * curIm + im[i + j + half] * curRe;

                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + half] = uRe - vRe;
                    im[i + j + half] = uIm - vIm;

                    double newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }
    }
}
