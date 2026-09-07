package org.fractalizer.explore;

import java.awt.image.BufferedImage;

/**
 * Scores one rendered view for "is this a good framing of fine detail".
 *
 * Detail is the variance of the luminance Laplacian over surface pixels, coverage the
 * fraction of pixels that hit a surface, and centroidDist where the |Laplacian| energy
 * sits in the frame (0 = centre, 1 = a corner). {@link FrameScore#aesthetic()} composes
 * them: detail wins, but only inside a coverage band around 55% and with the detail
 * near the centre. This is the judge the navigator harness and the Explore dialog
 * share — the same numbers, from files or from memory.
 *
 * Coverage percentage alone is a bad judge of detail: a smooth surface too close is
 * 100% hit and zero detail, a sparse gasket at 21% is the richest view. That is why
 * detail is measured on the image and not inferred from the depth.
 */
public final class FrameScorer {

    private FrameScorer() {}

    public record FrameScore(double detail, double coverage, double centroidDist) {
        public double aesthetic() {
            double covBand = Math.exp(-Math.pow((coverage - 0.55) / 0.30, 2)); // peak ~55% coverage
            double centering = 1.0 - 0.6 * Math.min(1.0, centroidDist);        // detail near centre wins
            return detail * covBand * centering;
        }
    }

    /** Depth values at or below this are background (the AOV encodes a miss as 0). */
    public static final double BACKGROUND = 0.02;

    /**
     * @param rgb   packed RGB ints, row-major, {@code w * h}
     * @param depth one value per pixel in [0, 1], same layout; {@code <= BACKGROUND} = background
     */
    public static FrameScore score(int[] rgb, float[] depth, int w, int h) {
        double[] lum = new double[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = rgb[i];
            lum[i] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }
        double sum = 0, sum2 = 0, cx = 0, cy = 0, wsum = 0;
        long n = 0, surf = 0, tot = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            tot++;
            if (depth[y * w + x] <= BACKGROUND) continue;
            surf++;
            double lap = -4 * lum[y*w+x] + lum[y*w+x-1] + lum[y*w+x+1] + lum[(y-1)*w+x] + lum[(y+1)*w+x];
            double al = Math.abs(lap);
            sum += lap; sum2 += lap * lap; n++;
            cx += al * x; cy += al * y; wsum += al;
        }
        double detail = (n < 100) ? 0 : (sum2 / n - (sum / n) * (sum / n));
        double coverage = (tot == 0) ? 0 : (double) surf / tot;
        double cdist = 1.0;
        if (wsum > 1e-6) {
            double dx = (cx / wsum - w / 2.0) / (w / 2.0), dy = (cy / wsum - h / 2.0) / (h / 2.0);
            cdist = Math.sqrt(dx * dx + dy * dy) / Math.sqrt(2.0);
        }
        return new FrameScore(detail, coverage, cdist);
    }

    public static FrameScore score(BufferedImage img, float[] depth) {
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        return score(rgb, depth, w, h);
    }

    /**
     * How much of a surface is surface and not dust. {@code solid} is the fraction of
     * surface pixels whose four neighbours are surface too: isolated specks score zero.
     * {@code smooth} is the fraction of those interior pixels whose depth differs from
     * every neighbour by less than {@link #SMOOTH_STEP}: a continuous sheet scores one, a
     * cloud of specks at unrelated depths scores near zero even where it is dense. The
     * product is a factor in [0, 1] to weigh a detail score by. Without it the Laplacian
     * variance rewards a ball of dust as much as a carved solid: both are busy.
     */
    public record Structure(double solid, double smooth) {
        public double factor() { return solid * smooth; }
    }

    /** Largest depth step between neighbouring pixels still read as one surface, in the
     *  AOV's encoding (1 - log(d + 0.1) / log 15): about eight pixel footprints at three
     *  units, generous for a sheet, far below the jumps between specks. */
    public static final double SMOOTH_STEP = 0.01;

    public static Structure structure(float[] depth, int w, int h) {
        long surf = 0, inner = 0, smoothN = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            float d = depth[i];
            if (d <= BACKGROUND) continue;
            surf++;
            float l = depth[i - 1], r = depth[i + 1], u = depth[i - w], dn = depth[i + w];
            if (l <= BACKGROUND || r <= BACKGROUND || u <= BACKGROUND || dn <= BACKGROUND) continue;
            inner++;
            float md = Math.max(Math.max(Math.abs(d - l), Math.abs(d - r)), Math.max(Math.abs(d - u), Math.abs(d - dn)));
            if (md < SMOOTH_STEP) smoothN++;
        }
        double solid = surf == 0 ? 0 : (double) inner / surf;
        double smooth = inner == 0 ? 0 : (double) smoothN / inner;
        return new Structure(solid, smooth);
    }

    /** Surface fraction of a depth map, on its own (the cheap probe). */
    public static double coverage(float[] depth) {
        if (depth.length == 0) return 0;
        int hit = 0;
        for (float v : depth) if (v > BACKGROUND) hit++;
        return (double) hit / depth.length;
    }
}
