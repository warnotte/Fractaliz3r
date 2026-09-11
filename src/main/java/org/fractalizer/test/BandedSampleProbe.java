package org.fractalizer.test;

import javafx.application.Platform;
import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.fractals.AbstractFractalParams;
import org.fractalizer.fractals.FractalType;
import org.fractalizer.ui.GLSLFractalizerController;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * A sample drawn in strips must be the same sample.
 *
 * The viewport scheduler draws a refinement sample dearer than its step budget through the
 * engine's slicing API ({@code beginSample}, {@code drawRows}, {@code commitSample} /
 * {@code discardSample}) so it can change its mind between strips. This probe renders the
 * same first sample whole and in 1, 3, 8 and 16 strips and compares the raw accumulation
 * buffers, then discards a sample after its first strip and checks that the next sample
 * starts from a cleared buffer rather than on top of the partial one.
 *
 *   mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.BandedSampleProbe"
 *
 * Exit code 1 on any pixel difference or a partial sample surviving a discard.
 */
public class BandedSampleProbe {

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();

        GLSLFractalizerController controller = new GLSLFractalizerController();
        controller.loadAllShaders((m, p) -> {});
        controller.setViewportSize(320, 180);
        controller.setFractalType(FractalType.MANDELBOX);
        AbstractFractalParams params = (AbstractFractalParams) controller.getParams();
        params.setPathTracingEnabled(true);          // the noisiest path: per-sample jitter must match too
        controller.renderStill(32, 18, 1, () -> false);   // compile
        GLSLEngine engine = controller.getEngine();
        engine.resize(320, 180);
        Map<String, Object> uniforms = controller.buildUniformsForProbe();
        final int H = 180;

        boolean ok = true;
        engine.resetAccumulation();
        engine.renderSamples(uniforms, 1);
        float[] whole = engine.readRawImage();
        System.out.println("=== BandedSampleProbe: one sample, whole vs strips, 320x180 Mandelbox path traced ===");
        for (int strips : new int[]{1, 3, 8, 16}) {
            engine.resetAccumulation();
            final int n = strips;
            engine.postAndWait(() -> {
                engine.beginSample(uniforms);
                for (int b = 0; b < n; b++) {
                    int y0 = H * b / n, y1 = H * (b + 1) / n;
                    engine.drawRows(y0, y1 - y0);
                    engine.fence().await();
                }
                engine.commitSample();
            });
            float[] banded = engine.readRawImage();
            int diff = 0;
            for (int i = 0; i < whole.length; i++) if (whole[i] != banded[i]) diff++;
            System.out.printf("  %2d strips: differing values %d / %d  samples=%d%n", strips, diff, whole.length, engine.getSampleCount());
            ok &= diff == 0 && engine.getSampleCount() == 1;
        }

        // Discard after the first strip: the count must not move, and the next whole sample
        // must start from a cleared buffer (equal to a fresh single sample).
        engine.resetAccumulation();
        engine.postAndWait(() -> {
            engine.beginSample(uniforms);
            engine.drawRows(0, H / 8);
            engine.fence().await();
            engine.discardSample();
        });
        int countAfterDiscard = engine.getSampleCount();
        engine.renderSamples(uniforms, 1);
        float[] afterDiscard = engine.readRawImage();
        int diff = 0;
        for (int i = 0; i < whole.length; i++) if (whole[i] != afterDiscard[i]) diff++;
        System.out.printf("  discard after strip 1: samples after discard=%d  next sample differs in %d values%n",
                countAfterDiscard, diff);
        ok &= countAfterDiscard == 0 && diff == 0 && engine.getSampleCount() == 1;

        // A strip, then another GL task that touches the state (a palette upload, as a preset
        // load posts one), then the discard a preempting request makes: the next whole sample
        // must still be a whole sample, not the last strip's rectangle (the scissor test must
        // not survive the discard).
        engine.resetAccumulation();
        engine.postAndWait(() -> {
            engine.beginSample(uniforms);
            engine.drawRows(H / 4, H / 8);
            engine.fence().await();
        });
        engine.updatePaletteTexture(new float[]{0.2f, 0.4f, 0.6f, 0.8f, 0.6f, 0.4f}, 2);
        engine.postAndWait(engine::discardSample);
        engine.renderSamples(uniforms, 1);
        float[] afterTouch = engine.readRawImage();
        diff = 0;
        for (int i = 0; i < whole.length; i++) if (whole[i] != afterTouch[i]) diff++;
        System.out.printf("  discard after a strip and a palette upload: next sample differs in %d values%n", diff);
        ok &= diff == 0 && engine.getSampleCount() == 1;

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        controller.close();
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }
}
