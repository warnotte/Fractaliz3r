package org.fractalizer.render;

import org.fractalizer.engine.OpenCLEngine;
import org.fractalizer.fractals.FractalParams;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.lwjgl.opencl.CL10.*;

/**
 * Renders fractals in tiles to avoid GPU watchdog timeouts.
 * Supports both preview (fast, lower quality) and full resolution rendering.
 */
public class TileRenderer {

    public static final int DEFAULT_TILE_SIZE = 256;
    public static final int PREVIEW_TILE_SIZE = 128;

    private final OpenCLEngine engine;
    private final ExecutorService executor;
    private final int tileSize;

    private volatile boolean cancelled = false;

    public TileRenderer(OpenCLEngine engine, int tileSize) {
        this.engine = engine;
        this.tileSize = tileSize;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public TileRenderer(OpenCLEngine engine) {
        this(engine, DEFAULT_TILE_SIZE);
    }

    /**
     * Render a full image asynchronously, tile by tile.
     *
     * @param kernelName The name of the loaded kernel to use
     * @param params Fractal parameters
     * @param width Full image width
     * @param height Full image height
     * @param onTileComplete Callback for each completed tile (receives tile data and position)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Future that completes when rendering is done
     */
    public CompletableFuture<float[]> renderAsync(
            String kernelName,
            FractalParams params,
            int width,
            int height,
            Consumer<TileResult> onTileComplete,
            Consumer<Double> onProgress) {

        cancelled = false;

        return CompletableFuture.supplyAsync(() -> {
            float[] fullImage = new float[width * height * 4]; // RGBA

            int tilesX = (width + tileSize - 1) / tileSize;
            int tilesY = (height + tileSize - 1) / tileSize;
            int totalTiles = tilesX * tilesY;
            int completedTiles = 0;

            // Allocate GPU buffer for one tile
            int tilePixels = tileSize * tileSize * 4;
            long outputBuffer = engine.createBuffer(CL_MEM_WRITE_ONLY, (long) tilePixels * Float.BYTES);

            try {
                for (int ty = 0; ty < tilesY && !cancelled; ty++) {
                    for (int tx = 0; tx < tilesX && !cancelled; tx++) {
                        int tileX = tx * tileSize;
                        int tileY = ty * tileSize;
                        int tileW = Math.min(tileSize, width - tileX);
                        int tileH = Math.min(tileSize, height - tileY);

                        // Set kernel arguments for this tile
                        engine.setKernelArg(kernelName, 0, outputBuffer);
                        engine.setKernelArgInt(kernelName, 1, width);
                        engine.setKernelArgInt(kernelName, 2, height);
                        engine.setKernelArgInt(kernelName, 3, tileX);
                        engine.setKernelArgInt(kernelName, 4, tileY);
                        engine.setKernelArgInt(kernelName, 5, tileSize);

                        // Set fractal-specific parameters
                        params.setKernelParams(engine, kernelName, 6);

                        // Execute kernel for this tile
                        engine.executeKernel2D(kernelName, tileSize, tileSize);
                        engine.finish();

                        // Read back tile data
                        java.nio.ByteBuffer byteBuffer = BufferUtils.createByteBuffer(tilePixels * Float.BYTES);
                        engine.readBuffer(outputBuffer, byteBuffer);
                        byteBuffer.rewind();
                        FloatBuffer floatView = byteBuffer.asFloatBuffer();

                        // Copy tile to full image
                        float[] tileArray = new float[tilePixels];
                        floatView.get(tileArray);

                        copyTileToImage(tileArray, fullImage, width, height,
                            tileX, tileY, tileW, tileH, tileSize);

                        completedTiles++;

                        if (onTileComplete != null) {
                            onTileComplete.accept(new TileResult(tileArray, tileX, tileY, tileW, tileH));
                        }

                        if (onProgress != null) {
                            onProgress.accept((double) completedTiles / totalTiles);
                        }
                    }
                }
            } finally {
                engine.releaseBuffer(outputBuffer);
            }

            return fullImage;
        }, executor);
    }

    /**
     * Render a preview at reduced resolution.
     */
    public CompletableFuture<float[]> renderPreview(
            String kernelName,
            FractalParams params,
            int targetWidth,
            int targetHeight,
            int previewScale,
            Consumer<Double> onProgress) {

        int previewWidth = targetWidth / previewScale;
        int previewHeight = targetHeight / previewScale;

        // For preview, we modify params to reduce iterations
        FractalParams previewParams = params.withReducedQuality(previewScale);

        return renderAsync(kernelName, previewParams, previewWidth, previewHeight, null, onProgress);
    }

    /**
     * Cancel ongoing rendering.
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Check if rendering was cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void copyTileToImage(float[] tile, float[] image,
            int imageWidth, int imageHeight,
            int tileX, int tileY, int tileW, int tileH, int tileStride) {

        for (int y = 0; y < tileH; y++) {
            int imageY = tileY + y;
            if (imageY >= imageHeight) break;

            for (int x = 0; x < tileW; x++) {
                int imageX = tileX + x;
                if (imageX >= imageWidth) break;

                int tileIdx = (y * tileStride + x) * 4;
                int imageIdx = (imageY * imageWidth + imageX) * 4;

                image[imageIdx] = tile[tileIdx];         // R
                image[imageIdx + 1] = tile[tileIdx + 1]; // G
                image[imageIdx + 2] = tile[tileIdx + 2]; // B
                image[imageIdx + 3] = tile[tileIdx + 3]; // A
            }
        }
    }

    /**
     * Result of rendering a single tile.
     */
    public record TileResult(float[] data, int x, int y, int width, int height) {}
}