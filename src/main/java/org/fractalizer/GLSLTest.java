package org.fractalizer;

import org.fractalizer.engine.GLSLEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Test standalone pour valider le nouveau moteur GLSL.
 * Rend un Mandelbulb et sauvegarde en PNG.
 */
public class GLSLTest {

    public static void main(String[] args) {
        System.out.println("=== GLSL Engine Test ===\n");

        int width = 800;
        int height = 600;
        int samples = 32;  // Nombre de samples progressifs

        try (GLSLEngine engine = new GLSLEngine(width, height)) {
            System.out.println("Engine created successfully!");
            System.out.println("Renderer: " + engine.getRenderer());
            System.out.println("OpenGL: " + engine.getGLVersion());
            System.out.println("GLSL: " + engine.getGLSLVersion());
            System.out.println();

            // Charger le shader Mandelbulb
            System.out.println("Loading Mandelbulb shader...");
            engine.loadFractalShader("mandelbulb", "/shaders/fractals/mandelbulb.glsl");
            engine.setActiveProgram("mandelbulb");
            System.out.println("Shader loaded!\n");

            // Préparer les uniforms
            Map<String, Object> uniforms = new HashMap<>();

            // Camera (GLSL expects vec4(x, y, z, w) for quaternion)
            uniforms.put("camPos", new float[]{0.0f, 0.0f, -2.5f});
            // Identity quaternion: (x=0, y=0, z=0, w=1)
            uniforms.put("camQuat", new float[]{0.0f, 0.0f, 0.0f, 1.0f});
            uniforms.put("fov", 60.0f);

            // Fractal params
            uniforms.put("power", 8.0f);
            uniforms.put("maxIterations", 10);
            uniforms.put("bailout", 2.0f);

            // Quality
            uniforms.put("qualityMultiplier", 1.0f);
            uniforms.put("maxRaySteps", 128);
            uniforms.put("baseEpsilon", 0.001f);

            // Lighting
            uniforms.put("lightDir", new float[]{0.5f, 0.8f, -0.6f});
            uniforms.put("lightColor", new float[]{1.0f, 0.95f, 0.9f});
            uniforms.put("lightIntensity", 1.2f);
            uniforms.put("ambientColor", new float[]{0.1f, 0.12f, 0.15f});
            uniforms.put("ambientIntensity", 0.3f);

            // Material
            uniforms.put("baseHue", new float[]{0.0f, 0.33f, 0.67f});

            // Effects
            uniforms.put("shadowSoftness", 16.0f);
            uniforms.put("shadowSteps", 32);
            uniforms.put("aoSteps", 5);
            uniforms.put("aoIntensity", 5.0f);
            uniforms.put("glowIntensity", 0.5f);
            uniforms.put("specularIntensity", 0.5f);
            uniforms.put("specularPower", 32.0f);

            // DoF (disabled)
            uniforms.put("dofEnabled", 0);
            uniforms.put("focalDistance", 2.5f);
            uniforms.put("aperture", 0.0f);
            uniforms.put("dofSamples", 1);

            // Render mode (0 = final)
            uniforms.put("renderMode", 0);

            // Rendu progressif
            System.out.println("Rendering " + samples + " samples...");
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < samples; i++) {
                engine.renderSample(uniforms);
                if ((i + 1) % 8 == 0) {
                    System.out.println("  Sample " + (i + 1) + "/" + samples);
                }
            }

            long renderTime = System.currentTimeMillis() - startTime;
            System.out.println("Rendering complete in " + renderTime + "ms");
            System.out.println("  " + (renderTime / samples) + "ms per sample");
            System.out.println();

            // Lire l'image
            System.out.println("Reading image...");
            float[] pixels = engine.readImage();

            // Convertir en BufferedImage
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * 4;
                    int r = (int) (pixels[idx] * 255);
                    int g = (int) (pixels[idx + 1] * 255);
                    int b = (int) (pixels[idx + 2] * 255);
                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }

            // Sauvegarder
            File outputFile = new File("glsl_test_output.png");
            ImageIO.write(image, "PNG", outputFile);
            System.out.println("Image saved to: " + outputFile.getAbsolutePath());

            System.out.println("\n=== Test completed successfully! ===");

        } catch (Exception e) {
            System.err.println("\n=== Test FAILED ===");
            e.printStackTrace();
        }
    }
}
