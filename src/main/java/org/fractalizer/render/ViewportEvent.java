package org.fractalizer.render;

/**
 * What the viewport scheduler tells the UI. Delivered on the JavaFX thread.
 */
public final class ViewportEvent {

    private ViewportEvent() {}

    /** A frame ready for display: BGRA bytes, top row first. */
    public record ViewportImage(int width, int height, byte[] bgra, int samples) {}

    public enum Phase { COMPILING, PREVIEW, REFINING, DONE, ERROR, PAUSED }

    /**
     * Where the viewport is. {@code samples}/{@code target} describe the refinement;
     * {@code elapsedMs} is the refinement's duration on DONE; {@code message} is the
     * driver's text on ERROR.
     */
    public record Status(Phase phase, int samples, int target, long elapsedMs, String message) {
        public static Status compiling() { return new Status(Phase.COMPILING, 0, 0, 0, null); }
        public static Status preview() { return new Status(Phase.PREVIEW, 0, 0, 0, null); }
        public static Status refining(int samples, int target) { return new Status(Phase.REFINING, samples, target, 0, null); }
        public static Status done(int samples, long elapsedMs) { return new Status(Phase.DONE, samples, samples, elapsedMs, null); }
        public static Status error(String message) { return new Status(Phase.ERROR, 0, 0, 0, message); }
        public static Status paused() { return new Status(Phase.PAUSED, 0, 0, 0, null); }
        public double progress() { return target > 0 ? Math.min(1.0, (double) samples / target) : 0; }
    }

    public interface Listener {
        void onImage(ViewportImage image);
        void onStatus(Status status);
    }
}
