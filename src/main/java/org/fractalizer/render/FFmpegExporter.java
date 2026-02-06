package org.fractalizer.render;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Utility class for creating video files from PNG sequences using FFmpeg.
 * Assumes ffmpeg is installed and available in the system PATH.
 */
public class FFmpegExporter {

    private static final boolean DEBUG = true;

    private static void log(String message) {
        if (DEBUG) {
            System.out.println("[FFmpeg] " + message);
        }
    }

    private static void logError(String message) {
        System.err.println("[FFmpeg ERROR] " + message);
    }

    /**
     * Result of an FFmpeg operation.
     */
    public static class ExportResult {
        public final boolean success;
        public final String message;
        public final File outputFile;

        public ExportResult(boolean success, String message, File outputFile) {
            this.success = success;
            this.message = message;
            this.outputFile = outputFile;
        }
    }

    /**
     * Check if FFmpeg is available on the system.
     *
     * @return true if ffmpeg command is accessible
     */
    public static boolean isFFmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get FFmpeg version string.
     *
     * @return Version string or error message
     */
    public static String getFFmpegVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String firstLine = reader.readLine();
            process.waitFor();

            if (firstLine != null && firstLine.contains("ffmpeg version")) {
                // Extract version from "ffmpeg version X.X.X ..."
                String[] parts = firstLine.split(" ");
                if (parts.length >= 3) {
                    return parts[2];
                }
            }
            return firstLine != null ? firstLine : "Unknown";
        } catch (Exception e) {
            return "Not available: " + e.getMessage();
        }
    }

    /**
     * Check if ExifTool is available on the system.
     */
    public static boolean isExifToolAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("exiftool", "-ver");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Injects 360 metadata into a video file using ExifTool.
     */
    public static boolean injectExifToolMetadata(File videoFile) {
        if (!isExifToolAvailable()) return false;
        
        log("Injecting 360 metadata via ExifTool...");
        try {
            List<String> command = new ArrayList<>();
            command.add("exiftool");
            command.add("-overwrite_original");
            command.add("-XMP-GSpherical:Spherical=true");
            command.add("-XMP-GSpherical:Stitched=true");
            command.add("-XMP-GSpherical:ProjectionType=equirectangular");
            command.add(videoFile.getAbsolutePath());
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output to ensure process completes
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* consume */ }
            }
            
            return process.waitFor() == 0;
        } catch (Exception e) {
            logError("ExifTool injection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create an MP4 video from a PNG sequence using H.265 (HEVC) codec.
     *
     * @param inputDir Directory containing PNG frames (frame_00000.png, frame_00001.png, etc.)
     * @param outputFile Output MP4 file
     * @param frameRate Frame rate of the output video
     * @param crf Constant Rate Factor (0-51, lower = better quality, 18-28 recommended)
     * @param progressCallback Optional callback for progress updates (0.0 to 1.0)
     * @return ExportResult with success status and message
     */
    public static ExportResult createMP4(File inputDir, File outputFile, int frameRate, int crf,
                                          boolean is360, Consumer<Double> progressCallback) {
        log("Starting MP4 export...");
        log("Input directory: " + inputDir.getAbsolutePath());
        log("Output file: " + outputFile.getAbsolutePath());
        log("Frame rate: " + frameRate + ", CRF: " + crf + (is360 ? " (360 VR Enabled)" : ""));

        if (!isFFmpegAvailable()) {
            logError("FFmpeg is not installed or not in PATH");
            return new ExportResult(false, "FFmpeg is not installed or not in PATH", null);
        }

        // Verify input directory exists and has frames
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            logError("Input directory does not exist: " + inputDir.getAbsolutePath());
            return new ExportResult(false, "Input directory does not exist: " + inputDir.getAbsolutePath(), null);
        }

        File[] frames = inputDir.listFiles((dir, name) -> name.matches("frame_\\d+\\.png"));
        if (frames == null || frames.length == 0) {
            logError("No frame files found in: " + inputDir.getAbsolutePath());
            return new ExportResult(false, "No frame files found in: " + inputDir.getAbsolutePath(), null);
        }

        log("Found " + frames.length + " frames");

        // Check first frame dimensions
        try {
            java.awt.image.BufferedImage firstFrame = javax.imageio.ImageIO.read(frames[0]);
            if (firstFrame != null) {
                log("Frame dimensions: " + firstFrame.getWidth() + "x" + firstFrame.getHeight());
            }
        } catch (IOException e) {
            log("Could not read first frame for dimension check: " + e.getMessage());
        }

        // Build FFmpeg command
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y"); // Overwrite output file
        command.add("-framerate");
        command.add(String.valueOf(frameRate));
        
        // Input 1: The PNG sequence
        command.add("-i");
        command.add(new File(inputDir, "frame_%05d.png").getAbsolutePath());
        
        // Input 2: A dummy silent audio track
        command.add("-f");
        command.add("lavfi");
        command.add("-i");
        command.add("anullsrc=channel_layout=stereo:sample_rate=44100");
        
        command.add("-c:v");
        command.add("libx265"); // Back to HEVC for best quality/size ratio
        command.add("-crf");
        command.add(String.valueOf(crf));
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-tag:v");
        command.add("hvc1"); // Apple compatibility
        
        // Audio codec
        command.add("-c:a");
        command.add("aac");
        command.add("-shortest");
        
        // Basic container flags (faststart is always good)
        command.add("-movflags");
        command.add("+faststart");
        
        command.add(outputFile.getAbsolutePath());

        log("FFmpeg command: " + String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output to prevent blocking
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int frameCount = frames.length;
            StringBuilder outputLog = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
                log("FFmpeg: " + line);

                // Parse progress from FFmpeg output
                // FFmpeg outputs lines like "frame=  123 fps=..."
                if (line.contains("frame=") && progressCallback != null) {
                    try {
                        int frameIdx = line.indexOf("frame=");
                        if (frameIdx >= 0) {
                            String frameStr = line.substring(frameIdx + 6).trim().split("\\s+")[0];
                            int currentFrame = Integer.parseInt(frameStr);
                            double progress = (double) currentFrame / frameCount;
                            progressCallback.accept(Math.min(progress, 1.0));
                        }
                    } catch (NumberFormatException ignored) {
                        // Continue if parsing fails
                    }
                }
            }

            int exitCode = process.waitFor();
            log("FFmpeg exit code: " + exitCode);

            if (exitCode == 0 && outputFile.exists()) {
                long fileSize = outputFile.length();
                log("Output file size: " + fileSize + " bytes");

                if (fileSize == 0) {
                    logError("Output file is empty (0 bytes)!");
                    logError("FFmpeg output:\n" + outputLog);
                    return new ExportResult(false, "FFmpeg created empty file. Check console for details.", null);
                }

                if (progressCallback != null) {
                    progressCallback.accept(1.0);
                }
                
                // Automatic ExifTool Injection for 360 mode
                String statusMsg = String.format("Video created successfully: %s", outputFile.getName());
                if (is360) {
                    if (injectExifToolMetadata(outputFile)) {
                        statusMsg += " (360 Metadata Injected)";
                    } else {
                        statusMsg += " (Manual 360 injection required)";
                    }
                }

                long fileSizeMB = fileSize / (1024 * 1024);
                log(statusMsg + " (" + fileSizeMB + " MB)");
                return new ExportResult(true,
                        statusMsg + String.format(" (%d MB)", fileSizeMB),
                        outputFile);
            } else {
                logError("FFmpeg failed. Exit code: " + exitCode);
                logError("FFmpeg output:\n" + outputLog);
                return new ExportResult(false, "FFmpeg exited with code: " + exitCode, null);
            }

        } catch (IOException e) {
            logError("Failed to run FFmpeg: " + e.getMessage());
            return new ExportResult(false, "Failed to run FFmpeg: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("FFmpeg process was interrupted");
            return new ExportResult(false, "FFmpeg process was interrupted", null);
        }
    }

    /**
     * Create an MP4 video with default settings (CRF 23, good balance of quality/size).
     *
     * @param inputDir Directory containing PNG frames
     * @param outputFile Output MP4 file
     * @param frameRate Frame rate
     * @return ExportResult
     */
    public static ExportResult createMP4(File inputDir, File outputFile, int frameRate) {
        return createMP4(inputDir, outputFile, frameRate, 23, false, null);
    }

    /**
     * Create MP4 with output file automatically named based on input directory.
     *
     * @param inputDir Directory containing PNG frames
     * @param frameRate Frame rate
     * @param crf Quality setting
     * @param is360 Whether to inject spherical metadata
     * @param progressCallback Progress callback
     * @return ExportResult
     */
    public static ExportResult createMP4InPlace(File inputDir, int frameRate, int crf,
                                                 boolean is360, Consumer<Double> progressCallback) {
        String outputName = inputDir.getName() + ".mp4";
        File outputFile = new File(inputDir.getParentFile(), outputName);
        return createMP4(inputDir, outputFile, frameRate, crf, is360, progressCallback);
    }
}
