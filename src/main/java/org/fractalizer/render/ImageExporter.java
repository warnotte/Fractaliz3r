package org.fractalizer.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Exports rendered fractal data to image files.
 */
public class ImageExporter {

    /**
     * Export float RGBA data to PNG file.
     *
     * @param data RGBA float array (values 0-1)
     * @param width Image width
     * @param height Image height
     * @param file Output file
     */
    public static void exportToPNG(float[] data, int width, int height, File file) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;

                int r = clamp((int) (data[idx] * 255), 0, 255);
                int g = clamp((int) (data[idx + 1] * 255), 0, 255);
                int b = clamp((int) (data[idx + 2] * 255), 0, 255);
                int a = clamp((int) (data[idx + 3] * 255), 0, 255);

                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }

        ImageIO.write(image, "PNG", file);
    }

    /**
     * Convert float RGBA data to BufferedImage for display.
     */
    public static BufferedImage toBufferedImage(float[] data, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;

                int r = clamp((int) (data[idx] * 255), 0, 255);
                int g = clamp((int) (data[idx + 1] * 255), 0, 255);
                int b = clamp((int) (data[idx + 2] * 255), 0, 255);
                int a = clamp((int) (data[idx + 3] * 255), 0, 255);

                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }

        return image;
    }

    /**
     * Scale up a preview image using nearest-neighbor for quick display.
     */
    public static BufferedImage scaleUp(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);

        float scaleX = (float) source.getWidth() / targetWidth;
        float scaleY = (float) source.getHeight() / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int srcX = (int) (x * scaleX);
                int srcY = (int) (y * scaleY);
                srcX = Math.min(srcX, source.getWidth() - 1);
                srcY = Math.min(srcY, source.getHeight() - 1);
                scaled.setRGB(x, y, source.getRGB(srcX, srcY));
            }
        }

        return scaled;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}