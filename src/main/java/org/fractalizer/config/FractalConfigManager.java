package org.fractalizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages save/load of fractal configurations.
 * Uses Gson for clean JSON serialization.
 */
public class FractalConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    /**
     * Save configuration to a file.
     *
     * @param config The configuration to save
     * @param file   The target file
     * @throws IOException If writing fails
     */
    public static void save(FractalConfig config, File file) throws IOException {
        String json = GSON.toJson(config);
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    /**
     * Load configuration from a file.
     *
     * @param file The source file
     * @return The loaded configuration
     * @throws IOException If reading fails
     */
    public static FractalConfig load(File file) throws IOException {
        String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return GSON.fromJson(json, FractalConfig.class);
    }

    /**
     * Convert configuration to JSON string.
     */
    public static String toJson(FractalConfig config) {
        return GSON.toJson(config);
    }

    /**
     * Parse configuration from JSON string.
     */
    public static FractalConfig fromJson(String json) {
        return GSON.fromJson(json, FractalConfig.class);
    }

    /**
     * Get the recommended file extension.
     */
    public static String getFileExtension() {
        return ".frac";
    }

    /**
     * Get file filter description for dialogs.
     */
    public static String getFileFilterDescription() {
        return "Fractal Configuration (*.frac)";
    }
}
