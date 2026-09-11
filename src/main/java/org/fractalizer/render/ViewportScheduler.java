package org.fractalizer.render;

import org.fractalizer.engine.GLSLEngine;
import org.fractalizer.render.ViewportEvent.Listener;
import org.fractalizer.render.ViewportEvent.Status;
import org.fractalizer.render.ViewportEvent.ViewportImage;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The one owner of the interactive viewport: it turns requests into bounded steps of GPU
 * work on the GL thread and decides, in one place, what preempts what.
 *
 * <p>Entry points ({@link #request}, {@link #refineNow}, {@link #pause}, {@link #resume})
 * may be called from any thread and never block. Everything else runs on the GL thread as
 * short tasks posted to the engine, so other GL work (an export, a resize) interleaves
 * between steps and waits at most one step. See docs/INTERACTIVE_RENDER.md.
 *
 * <p>Policy:
 * <ol>
 * <li>the latest request wins; a request not yet started is dropped by the next;</li>
 * <li>a newer request preempts a refinement at any step, a preview only once it has shown
 *     its first image, a compile never (compiles are atomic);</li>
 * <li>a completed preview is followed, after {@link #REFINE_DELAY_MS} without a request,
 *     by a refinement of the same scene; {@link #refineNow} skips the wait;</li>
 * <li>while paused nothing runs and requests are kept; {@link #resume} replays the last.</li>
 * </ol>
 */
public final class ViewportScheduler {

    public static final long REFINE_DELAY_MS = 400;
    static final long IMAGE_INTERVAL_NS = 200_000_000L;   // refinement frames to the UI
    static final long STATUS_INTERVAL_NS = 100_000_000L;
    private static final boolean DEBUG = Boolean.getBoolean("fractalizer.debugRender");

    private final GLSLEngine engine;
    private final CostModel costs = new CostModel();
    private final Consumer<Runnable> ui;                    // how events reach the listener
    private volatile Listener listener;

    // Cross-thread mailbox
    private final AtomicReference<SceneSnapshot> pending = new AtomicReference<>();
    private final AtomicInteger pauseDepth = new AtomicInteger();
    private volatile boolean refineRequested;

    // GL-thread state
    private Job current;
    private SceneSnapshot lastShown;          // the scene on screen, for refine and resume
    private ScheduledFuture<?> refineTimer;
    private boolean stepQueued;
    private float previewScaleNow;            // the scale of the last preview, for the dead band

    public ViewportScheduler(GLSLEngine engine, Consumer<Runnable> uiExecutor) {
        this.engine = engine;
        this.ui = uiExecutor;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public CostModel costs() { return costs; }

    // ---- entry points, any thread ----------------------------------------------------

    /** The scene is now {@code scene}: show it. Replaces any request not yet started. */
    public void request(SceneSnapshot scene) {
        pending.set(scene);
        queueStep();
    }

    /** Refine the scene on screen now, without waiting for the idle delay. */
    public void refineNow() {
        refineRequested = true;
        queueStep();
    }

    /** Stop rendering the viewport until {@link #resume}; nestable. Returns after the
     *  current step: the GL thread is then free for whoever paused. Called from the
     *  thread that will use the engine, never from the GL thread itself. */
    public void pause() {
        pauseDepth.incrementAndGet();
        engine.postAndWait(() -> { dropCurrent(); emit(Status.paused()); });
    }

    public void resume() {
        if (pauseDepth.decrementAndGet() > 0) return;
        engine.post(() -> {
            if (pending.get() == null && lastShown != null) pending.compareAndSet(null, lastShown);
            step();
        });
    }

    public boolean isPaused() { return pauseDepth.get() > 0; }

    /** Whether a completed preview is followed by a refinement after the idle delay
     *  (the Quality panel's "auto full quality"); {@link #refineNow} always works. */
    public void setAutoRefine(boolean on) { autoRefine = on; }
    private volatile boolean autoRefine = true;

    /** Whether a job is running (for status displays; a snapshot in time). */
    public boolean isBusy() { return current != null || pending.get() != null; }

    public void close() {
        pauseDepth.incrementAndGet();
        engine.postAndWait(() -> { dropCurrent(); cancelRefineTimer(); });   // no refine may fire after the engine is gone
    }

    // ---- the loop, GL thread ---------------------------------------------------------

    private void queueStep() {
        engine.post(this::step);
    }

    private void step() {
        stepQueued = false;
        if (pauseDepth.get() > 0) { dropCurrent(); return; }

        SceneSnapshot req = pending.get();
        if (req != null && (current == null || current.preemptible())) {
            pending.compareAndSet(req, null);
            dropCurrent();
            cancelRefineTimer();
            current = startJob(req);
        }
        if (refineRequested) {
            refineRequested = false;
            if (current == null && lastShown != null) current = new RefineJob(lastShown);
        }
        if (current == null) return;

        boolean done;
        long t0 = DEBUG ? System.nanoTime() : 0;
        try {
            done = current.step();
            if (DEBUG) System.out.printf("[viewport] %s step %d ms%s%n", current, (System.nanoTime() - t0) / 1_000_000, done ? " done" : "");
        } catch (RuntimeException e) {
            e.printStackTrace();
            emit(Status.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            current = null;
            return;
        }
        if (done) {
            Job finished = current;
            current = null;
            finished.finished();
        }
        if (current != null || pending.get() != null) {
            if (!stepQueued) { stepQueued = true; queueStep(); }
        }
    }

    private Job startJob(SceneSnapshot scene) {
        if (!engine.hasProgram(scene.programKey(), scene.programSource(), scene.programDefines())
                || (scene.causticPhotons() > 0 && !engine.hasPhotonProgram(scene.programKey(), scene.programSource(), scene.programDefines()))) {
            return new CompileJob(scene);
        }
        return new PreviewJob(scene);
    }

    private void dropCurrent() {
        if (current != null) { current.cancel(); current = null; }
    }

    private void cancelRefineTimer() {
        if (refineTimer != null) { refineTimer.cancel(false); refineTimer = null; }
    }

    private void scheduleRefine(SceneSnapshot scene) {
        cancelRefineTimer();
        if (!autoRefine) return;
        refineTimer = engine.postDelayed(() -> {
            refineTimer = null;
            if (pauseDepth.get() > 0 || current != null || pending.get() != null) return;
            current = new RefineJob(scene);
            step();
        }, REFINE_DELAY_MS);
    }

    private void emit(Status s) {
        Listener l = listener;
        if (l != null) ui.accept(() -> l.onStatus(s));
    }

    private long lastReadbackNs;              // what the last frame cost to read back and convert

    private void emitImage() {
        Listener l = listener;
        if (l == null) return;
        long t0 = System.nanoTime();
        GLSLEngine.ViewportFrame f = engine.readViewportFrame();
        ViewportImage img = new ViewportImage(f.width(), f.height(), f.bgra(), f.samples());
        lastReadbackNs = System.nanoTime() - t0;
        if (DEBUG) System.out.printf("[viewport] readback %dx%d %d ms%n", f.width(), f.height(), lastReadbackNs / 1_000_000);
        ui.accept(() -> l.onImage(img));
    }

    /** Bind the engine to a scene at a size: program, SSBO, photons per sample, framebuffers,
     *  fresh accumulation. */
    private void bind(SceneSnapshot scene, int w, int h, int causticPhotons) {
        engine.resize(w, h);
        engine.setActiveProgram(scene.programKey());
        engine.updateMaterialSSBO(scene.materialSSBO());
        engine.setCausticPhotons(causticPhotons);
        engine.resetAccumulation();
    }

    // ---- jobs ------------------------------------------------------------------------

    private interface Job {
        /** One bounded step. @return true when the job is complete. */
        boolean step();
        /** Whether a newer request may replace this job now. */
        boolean preemptible();
        /** The job is dropped before completion. */
        void cancel();
        /** The job completed. */
        void finished();
    }

    /** Builds the program, and the photon program when the scene has caustics; atomic. On
     *  success the preview of the same scene follows. */
    private final class CompileJob implements Job {
        private final SceneSnapshot scene;
        CompileJob(SceneSnapshot scene) { this.scene = scene; emit(Status.compiling()); }

        @Override public boolean step() {
            long t0 = System.nanoTime();
            String err = engine.ensureProgram(scene.programKey(), scene.programSource(), scene.programDefines());
            if (err == null && scene.causticPhotons() > 0) err = engine.ensurePhotonProgram(scene.programKey(), scene.programSource(), scene.programDefines());
            if (err != null) {
                System.err.println("Scene shader error: " + err);
                emit(Status.error(err));
            } else {
                System.out.printf("Scene shader compiled in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
                pending.compareAndSet(null, scene);   // the preview of what was just compiled
            }
            return true;
        }
        @Override public boolean preemptible() { return false; }
        @Override public void cancel() {}
        @Override public void finished() {}
        @Override public String toString() { return "compile"; }
    }

    /** A few samples at the size the cost model picks; the first image goes out after the
     *  first step, whatever happens next. */
    private final class PreviewJob implements Job {
        private final SceneSnapshot scene;
        private final String costKey;
        private final int w, h;
        private final Map<String, Object> uniforms;
        private int samples;
        private boolean shown;

        PreviewJob(SceneSnapshot scene) {
            this.scene = scene;
            this.costKey = CostModel.keyFor(scene);
            float scale = costs.previewScale(costKey, scene.viewportWidth(), scene.viewportHeight(),
                    scene.previewScale(), previewScaleNow);
            previewScaleNow = scale;
            w = Math.max(160, Math.round(scene.viewportWidth() * scale));
            h = Math.max(90, Math.round(scene.viewportHeight() * scale));
            uniforms = scene.uniformsFor(h, true);
            bind(scene, w, h, scene.causticPhotonsFor(true, scale));
            emit(Status.preview());
        }

        @Override public boolean step() {
            int n = Math.min(costs.samplesPerStep(costKey, true, w, h, CostModel.STEP_BUDGET_NS), scene.previewSamples() - samples);
            GLSLEngine.GpuTimer timer = engine.timer();
            timer.begin();
            engine.renderSamples(uniforms, n);
            timer.end();
            engine.fence().await();
            costs.record(costKey, true, (long) w * h * n, timer.elapsedNs());
            samples += n;
            boolean done = samples >= scene.previewSamples();
            if (!shown || done) { emitImage(); shown = true; }
            return done;
        }
        @Override public String toString() { return "preview " + w + "x" + h + " " + samples + "/" + scene.previewSamples(); }
        @Override public boolean preemptible() { return shown; }
        @Override public void cancel() {}
        @Override public void finished() {
            lastShown = scene;
            scheduleRefine(scene);
        }
    }

    /**
     * Full size, full quality, many samples. A step is whole samples when they fit the
     * refinement slice, else one strip of a sample, waited for before the next: every
     * sync between two draws costs the tail of the draw before it, and keeping a strip
     * in flight did not hide it (StripCostProbe), so the slice is what bounds the price
     * and the resume latency alike. Frames go out between samples only, every
     * {@link #IMAGE_INTERVAL_NS} or five readbacks' worth, whichever is longer.
     */
    private final class RefineJob implements Job {
        private final SceneSnapshot scene;
        private final String costKey;
        private final int w, h;
        private final Map<String, Object> uniforms;
        private final long started = System.nanoTime();
        private long lastImage = 0, lastStatus = 0;
        private int samples;
        private int stripY = -1;         // >= 0 while a sliced sample is open
        private int lastRows = 0;

        RefineJob(SceneSnapshot scene) {
            this.scene = scene;
            this.costKey = CostModel.keyFor(scene);
            w = scene.viewportWidth();
            h = scene.viewportHeight();
            uniforms = scene.uniformsFor(h, false);
            bind(scene, w, h, scene.causticPhotonsFor(false, 1f));
            emit(Status.refining(0, scene.fullSamples()));
        }

        @Override public boolean step() {
            long slice = CostModel.REFINE_SLICE_NS;
            GLSLEngine.GpuTimer timer = engine.timer();
            long pixels;
            timer.begin();
            if (stripY < 0 && !costs.sampleExceedsStep(costKey, false, w, h, slice)) {
                int n = Math.min(costs.samplesPerStep(costKey, false, w, h, slice), scene.fullSamples() - samples);
                engine.renderSamples(uniforms, n);
                pixels = (long) w * h * n;
                samples += n;
                lastRows = 0;
            } else {
                if (stripY < 0) { engine.beginSample(uniforms); stripY = 0; }
                int rows = Math.min(costs.rowsPerStrip(costKey, false, w, h, lastRows, slice), h - stripY);
                engine.drawRows(stripY, rows);
                pixels = (long) w * rows;
                stripY += rows;
                lastRows = rows;
                if (stripY >= h) { engine.commitSample(); stripY = -1; samples++; }
            }
            timer.end();
            engine.fence().await();
            costs.record(costKey, false, pixels, timer.elapsedNs());

            boolean done = samples >= scene.fullSamples();
            long now = System.nanoTime();
            long interval = Math.max(IMAGE_INTERVAL_NS, 5 * lastReadbackNs);
            if (stripY < 0 && (done || now - lastImage >= interval)) { emitImage(); lastImage = System.nanoTime(); }
            if (done) emit(Status.done(samples, (System.nanoTime() - started) / 1_000_000));
            else if (now - lastStatus >= STATUS_INTERVAL_NS) { emit(Status.refining(samples, scene.fullSamples())); lastStatus = now; }
            return done;
        }

        @Override public String toString() { return "refine " + w + "x" + h + " " + samples + "/" + scene.fullSamples() + (stripY >= 0 ? " row " + stripY : ""); }
        @Override public boolean preemptible() { return true; }
        @Override public void cancel() {
            if (stripY >= 0) { engine.discardSample(); stripY = -1; }
        }
        @Override public void finished() { lastShown = scene; }
    }
}
