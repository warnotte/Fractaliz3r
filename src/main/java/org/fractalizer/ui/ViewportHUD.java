package org.fractalizer.ui;

import javafx.animation.FadeTransition;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import org.fractalizer.engine.Camera;

/**
 * Overlay HUD for the viewport.
 * Features:
 * - 3D Orientation Compass (Axes X, Y, Z)
 * - Animated Speed Meter (Thrust indicator)
 * - Telemetry data (FOV, Move Speed)
 */
public class ViewportHUD {

    private final Canvas canvas;
    private float currentSpeed = 0.5f;
    private long lastSpeedChangeTime = 0;
    private static final long SPEED_HUD_DURATION_MS = 1500;

    // Cyber Colors
    private static final Color COLOR_X = Color.web("#ff3b30"); // Red
    private static final Color COLOR_Y = Color.web("#4cd964"); // Green
    private static final Color COLOR_Z = Color.web("#007aff"); // Blue
    private static final Color COLOR_HUD = Color.rgb(0, 255, 255, 0.8);
    private static final Color COLOR_TEXT = Color.rgb(200, 200, 200, 0.6);

    public ViewportHUD(double width, double height) {
        this.canvas = new Canvas(width, height);
        this.canvas.setMouseTransparent(true);
    }

    public Canvas getCanvas() {
        return canvas;
    }

    public void resize(double width, double height) {
        canvas.setWidth(width);
        canvas.setHeight(height);
    }

    public void updateSpeed(float newSpeed) {
        this.currentSpeed = newSpeed;
        this.lastSpeedChangeTime = System.currentTimeMillis();
    }

    public void draw(Camera camera, float fov) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);

        if (w < 100 || h < 100) return;

        drawCompass(gc, w - 60, 60, camera);
        drawSpeedMeter(gc, w - 25, h / 2, currentSpeed);
        drawTelemetry(gc, 15, h - 15, currentSpeed, fov);
    }

    private void drawCompass(GraphicsContext gc, double cx, double cy, Camera camera) {
        // Get camera rotation (quaternion)
        float[] q = camera.getQuaternion(); // [w, x, y, z]
        
        // Base axes
        float[][] axes = {
            {1, 0, 0}, // X
            {0, 1, 0}, // Y
            {0, 0, 1}  // Z
        };
        Color[] colors = {COLOR_X, COLOR_Y, COLOR_Z};
        String[] labels = {"X", "Y", "Z"};

        double size = 40;
        gc.setLineWidth(2.0);
        gc.setFont(Font.font("monospace", FontWeight.BOLD, 12));

        // Sort axes by Z-depth for correct painter's algorithm rendering
        // (Simplified here: just draw them with varying opacities)
        for (int i = 0; i < 3; i++) {
            float[] axis = axes[i];
            
            // Rotate axis by camera quaternion (inverse to show world orientation)
            // v' = q^-1 * v * q
            float[] rot = rotateVectorInverse(axis, q);
            
            double x2 = cx + rot[0] * size;
            double y2 = cy - rot[1] * size; // Flip Y for screen space

            // Determine if pointing towards or away
            double opacity = 0.4 + (rot[2] + 1.0) * 0.3;
            gc.setStroke(colors[i].deriveColor(0, 1, 1, opacity));
            gc.setFill(colors[i].deriveColor(0, 1, 1, opacity));

            // Draw line
            gc.strokeLine(cx, cy, x2, y2);
            
            // Draw label at the end
            gc.fillText(labels[i], x2 + 4, y2 + 4);
        }

        // Draw center dot
        gc.setFill(Color.WHITE);
        gc.fillOval(cx - 2, cy - 2, 4, 4);
    }

    private void drawSpeedMeter(GraphicsContext gc, double cx, double cy, float speed) {
        long elapsed = System.currentTimeMillis() - lastSpeedChangeTime;
        if (elapsed > SPEED_HUD_DURATION_MS) return;

        double opacity = 1.0 - (double) elapsed / SPEED_HUD_DURATION_MS;
        double h = 140;
        double yStart = cy - h / 2;

        // Background track
        gc.setStroke(Color.rgb(40, 40, 50, opacity * 0.5));
        gc.setLineWidth(5);
        gc.strokeLine(cx, yStart, cx, yStart + h);

        // Fill segments
        int segments = 10;
        double segH = h / segments;
        int activeSegments = Math.round(speed * segments);

        for (int i = 0; i < segments; i++) {
            double sy = yStart + h - (i + 1) * segH;
            if (i < activeSegments) {
                gc.setFill(COLOR_HUD.deriveColor(0, 1, 1, opacity));
            } else {
                gc.setFill(Color.rgb(60, 60, 70, opacity * 0.3));
            }
            gc.fillRect(cx - 7, sy + 1, 14, segH - 2);
        }

        // Value text
        gc.setFill(COLOR_HUD.deriveColor(0, 1, 1, opacity));
        gc.setFont(Font.font("monospace", FontWeight.BOLD, 11));
        gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
        gc.fillText(String.format("%.2fx", speed), cx - 12, yStart + h + 15);
        gc.fillText("SPD", cx - 12, yStart - 5);
    }

    private void drawTelemetry(GraphicsContext gc, double x, double y, float speed, float fov) {
        gc.setFill(COLOR_TEXT);
        gc.setFont(Font.font("monospace", FontWeight.BOLD, 12));
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        String text = String.format("NAV_SYS: ACTIVE | SPD: %.3f | FOV: %.1f\u00B0", speed, fov);
        gc.fillText(text, x, y);
    }

    // Helper for rotating vector by quaternion inverse
    private float[] rotateVectorInverse(float[] v, float[] q) {
        // Inverse of q is [w, -x, -y, -z]
        float qw = q[0], qx = -q[1], qy = -q[2], qz = -q[3];
        
        // Standard quaternion rotation: v' = q * v * q^-1
        // But we want world-to-local, so we use the conjugate
        float vx = v[0], vy = v[1], vz = v[2];
        
        float tx = 2 * (qy * vz - qz * vy);
        float ty = 2 * (qz * vx - qx * vz);
        float tz = 2 * (qx * vy - qy * vx);
        
        return new float[] {
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        };
    }
}
