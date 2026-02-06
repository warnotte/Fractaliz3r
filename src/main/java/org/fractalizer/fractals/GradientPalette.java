package org.fractalizer.fractals;

import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Custom gradient palette defined by color stops.
 * Generates a 1D texture for GPU sampling when paletteIndex == 6.
 */
public class GradientPalette {

    /**
     * A single color stop in the gradient.
     */
    public record ColorStop(double position, Color color) {
        public ColorStop {
            if (position < 0 || position > 1) {
                throw new IllegalArgumentException("Position must be in [0, 1]: " + position);
            }
        }
    }

    private final List<ColorStop> stops = new ArrayList<>();

    /**
     * Create a default gradient (black to white).
     */
    public GradientPalette() {
        stops.add(new ColorStop(0.0, Color.BLACK));
        stops.add(new ColorStop(1.0, Color.WHITE));
    }

    /**
     * Create a gradient from an existing list of stops.
     */
    public GradientPalette(List<ColorStop> stops) {
        if (stops.size() < 2) {
            throw new IllegalArgumentException("Gradient needs at least 2 stops");
        }
        this.stops.addAll(stops);
        this.stops.sort(Comparator.comparingDouble(ColorStop::position));
    }

    public List<ColorStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    public int getStopCount() {
        return stops.size();
    }

    /**
     * Add a color stop at the given position.
     * Returns the index of the inserted stop.
     */
    public int addStop(double position, Color color) {
        ColorStop stop = new ColorStop(Math.max(0, Math.min(1, position)), color);
        stops.add(stop);
        stops.sort(Comparator.comparingDouble(ColorStop::position));
        return stops.indexOf(stop);
    }

    /**
     * Remove a stop by index. Must keep at least 2 stops.
     */
    public boolean removeStop(int index) {
        if (stops.size() <= 2 || index < 0 || index >= stops.size()) {
            return false;
        }
        stops.remove(index);
        return true;
    }

    /**
     * Move a stop to a new position. Returns the new index after re-sorting.
     */
    public int moveStop(int index, double newPosition) {
        if (index < 0 || index >= stops.size()) return index;
        Color color = stops.get(index).color();
        stops.set(index, new ColorStop(Math.max(0, Math.min(1, newPosition)), color));
        stops.sort(Comparator.comparingDouble(ColorStop::position));
        // Find the new index
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).color() == color && Math.abs(stops.get(i).position() - newPosition) < 0.0001) {
                return i;
            }
        }
        return index;
    }

    /**
     * Update the color of a stop.
     */
    public void setStopColor(int index, Color color) {
        if (index < 0 || index >= stops.size()) return;
        double pos = stops.get(index).position();
        stops.set(index, new ColorStop(pos, color));
    }

    /**
     * Create a deep copy of this gradient.
     */
    public GradientPalette copy() {
        List<ColorStop> copied = new ArrayList<>();
        for (ColorStop s : stops) {
            copied.add(new ColorStop(s.position(), s.color()));
        }
        return new GradientPalette(copied);
    }

    /**
     * Generate a 1D texture as float[resolution * 3] (RGB).
     * Linear interpolation between stops.
     */
    public float[] toTextureData(int resolution) {
        float[] data = new float[resolution * 3];
        for (int i = 0; i < resolution; i++) {
            double t = (double) i / (resolution - 1);
            Color c = sampleAt(t);
            data[i * 3] = (float) c.getRed();
            data[i * 3 + 1] = (float) c.getGreen();
            data[i * 3 + 2] = (float) c.getBlue();
        }
        return data;
    }

    /**
     * Sample the gradient at position t in [0, 1].
     */
    public Color sampleAt(double t) {
        t = Math.max(0, Math.min(1, t));
        if (stops.isEmpty()) return Color.BLACK;
        if (stops.size() == 1) return stops.get(0).color();

        // Find the two surrounding stops
        ColorStop left = stops.get(0);
        ColorStop right = stops.get(stops.size() - 1);

        for (int i = 0; i < stops.size() - 1; i++) {
            if (t >= stops.get(i).position() && t <= stops.get(i + 1).position()) {
                left = stops.get(i);
                right = stops.get(i + 1);
                break;
            }
        }

        if (t <= left.position()) return left.color();
        if (t >= right.position()) return right.color();

        double range = right.position() - left.position();
        double frac = (range > 0) ? (t - left.position()) / range : 0;

        return left.color().interpolate(right.color(), frac);
    }

    // ========================================================================
    // Presets
    // ========================================================================

    public static GradientPalette createMagma() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(0, 0, 0)));
        s.add(new ColorStop(0.25, Color.rgb(80, 10, 30)));
        s.add(new ColorStop(0.5, Color.rgb(200, 60, 10)));
        s.add(new ColorStop(0.75, Color.rgb(255, 180, 30)));
        s.add(new ColorStop(1.0, Color.rgb(255, 255, 200)));
        return new GradientPalette(s);
    }

    public static GradientPalette createIce() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(5, 5, 30)));
        s.add(new ColorStop(0.3, Color.rgb(10, 50, 120)));
        s.add(new ColorStop(0.6, Color.rgb(40, 150, 200)));
        s.add(new ColorStop(0.85, Color.rgb(160, 220, 240)));
        s.add(new ColorStop(1.0, Color.rgb(240, 250, 255)));
        return new GradientPalette(s);
    }

    public static GradientPalette createForest() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(20, 10, 5)));
        s.add(new ColorStop(0.3, Color.rgb(50, 80, 20)));
        s.add(new ColorStop(0.5, Color.rgb(30, 130, 50)));
        s.add(new ColorStop(0.75, Color.rgb(120, 180, 60)));
        s.add(new ColorStop(1.0, Color.rgb(200, 220, 150)));
        return new GradientPalette(s);
    }

    public static GradientPalette createNeon() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(10, 0, 20)));
        s.add(new ColorStop(0.25, Color.rgb(180, 0, 200)));
        s.add(new ColorStop(0.5, Color.rgb(0, 200, 255)));
        s.add(new ColorStop(0.75, Color.rgb(255, 0, 100)));
        s.add(new ColorStop(1.0, Color.rgb(255, 255, 50)));
        return new GradientPalette(s);
    }

    public static GradientPalette createSpectral() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(100, 50, 150)));
        s.add(new ColorStop(0.2, Color.rgb(50, 100, 200)));
        s.add(new ColorStop(0.4, Color.rgb(50, 180, 100)));
        s.add(new ColorStop(0.6, Color.rgb(200, 200, 50)));
        s.add(new ColorStop(0.8, Color.rgb(220, 120, 30)));
        s.add(new ColorStop(1.0, Color.rgb(180, 50, 50)));
        return new GradientPalette(s);
    }

    public static GradientPalette createSunset() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(25, 10, 60)));
        s.add(new ColorStop(0.25, Color.rgb(120, 30, 120)));
        s.add(new ColorStop(0.5, Color.rgb(220, 60, 60)));
        s.add(new ColorStop(0.75, Color.rgb(255, 160, 50)));
        s.add(new ColorStop(1.0, Color.rgb(255, 230, 120)));
        return new GradientPalette(s);
    }

    public static GradientPalette createOcean() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(2, 5, 25)));
        s.add(new ColorStop(0.25, Color.rgb(10, 30, 90)));
        s.add(new ColorStop(0.5, Color.rgb(0, 100, 130)));
        s.add(new ColorStop(0.75, Color.rgb(30, 180, 170)));
        s.add(new ColorStop(1.0, Color.rgb(150, 240, 220)));
        return new GradientPalette(s);
    }

    public static GradientPalette createAurora() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(5, 5, 30)));
        s.add(new ColorStop(0.2, Color.rgb(20, 80, 60)));
        s.add(new ColorStop(0.4, Color.rgb(50, 200, 100)));
        s.add(new ColorStop(0.6, Color.rgb(80, 180, 220)));
        s.add(new ColorStop(0.8, Color.rgb(120, 80, 200)));
        s.add(new ColorStop(1.0, Color.rgb(200, 150, 255)));
        return new GradientPalette(s);
    }

    public static GradientPalette createPastel() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(255, 180, 200)));
        s.add(new ColorStop(0.25, Color.rgb(200, 180, 255)));
        s.add(new ColorStop(0.5, Color.rgb(180, 220, 255)));
        s.add(new ColorStop(0.75, Color.rgb(180, 255, 210)));
        s.add(new ColorStop(1.0, Color.rgb(255, 255, 180)));
        return new GradientPalette(s);
    }

    public static GradientPalette createMonochrome() {
        List<ColorStop> s = new ArrayList<>();
        s.add(new ColorStop(0.0, Color.rgb(10, 10, 15)));
        s.add(new ColorStop(0.25, Color.rgb(50, 50, 60)));
        s.add(new ColorStop(0.5, Color.rgb(120, 120, 130)));
        s.add(new ColorStop(0.75, Color.rgb(190, 190, 200)));
        s.add(new ColorStop(1.0, Color.rgb(245, 245, 250)));
        return new GradientPalette(s);
    }
}
