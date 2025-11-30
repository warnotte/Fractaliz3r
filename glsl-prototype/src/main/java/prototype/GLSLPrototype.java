package prototype;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * GLSL Progressive Fractal Renderer Prototype
 *
 * Demonstrates:
 * - Fragmentarium-style shader architecture (DE abstraction)
 * - Progressive rendering with accumulation buffer
 * - No watchdog issues (each frame is fast)
 * - FPS camera controls
 */
public class GLSLPrototype {

    // Window
    private long window;
    private int width = 1280;
    private int height = 720;

    // Shaders
    private int renderProgram;      // Main fractal render
    private int displayProgram;     // Display accumulated result

    // Framebuffers for progressive rendering
    private int accumFBO;
    private int accumTexture;       // Float32 RGBA accumulation
    private int sampleCount = 0;

    // Fullscreen quad
    private int quadVAO;

    // Camera (quaternion-based like Fractaliz3r)
    private float[] camPos = {0.0f, 0.0f, -3.0f};
    private float[] camQuat = {0.0f, 0.0f, 0.0f, 1.0f}; // Identity quaternion
    private float fov = 60.0f;
    private float moveSpeed = 0.05f;

    // Mouse state
    private double lastMouseX, lastMouseY;
    private boolean mouseCaptured = false;

    // Fractal parameters
    private float power = 8.0f;
    private int maxIterations = 10;

    // Render state
    private boolean needsReset = true;
    private int maxSamples = 1000;

    public void run() {
        init();
        loop();
        cleanup();
    }

    private void init() {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        // Create window
        window = glfwCreateWindow(width, height, "GLSL Fractal Prototype - Progressive Renderer", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Setup callbacks
        setupCallbacks();

        // Center window
        try (MemoryStack stack = stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            glfwGetWindowSize(window, pWidth, pHeight);
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowPos(window,
                (vidmode.width() - pWidth.get(0)) / 2,
                (vidmode.height() - pHeight.get(0)) / 2
            );
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // VSync
        glfwShowWindow(window);

        // Initialize OpenGL
        GL.createCapabilities();
        System.out.println("OpenGL Version: " + glGetString(GL_VERSION));
        System.out.println("GLSL Version: " + glGetString(GL_SHADING_LANGUAGE_VERSION));

        // Create resources
        createShaders();
        createFramebuffer();
        createFullscreenQuad();

        System.out.println("\n=== Controls ===");
        System.out.println("WASD/Arrow keys: Move");
        System.out.println("Mouse: Look around (click to capture)");
        System.out.println("Q/E: Roll");
        System.out.println("Space/Shift: Up/Down");
        System.out.println("R: Reset camera");
        System.out.println("+/-: Adjust power");
        System.out.println("ESC: Release mouse / Exit");
        System.out.println("================\n");
    }

    private void setupCallbacks() {
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW_PRESS) {
                switch (key) {
                    case GLFW_KEY_ESCAPE:
                        if (mouseCaptured) {
                            mouseCaptured = false;
                            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                        } else {
                            glfwSetWindowShouldClose(window, true);
                        }
                        break;
                    case GLFW_KEY_R:
                        resetCamera();
                        break;
                    case GLFW_KEY_EQUAL:
                    case GLFW_KEY_KP_ADD:
                        power += 0.5f;
                        needsReset = true;
                        System.out.println("Power: " + power);
                        break;
                    case GLFW_KEY_MINUS:
                    case GLFW_KEY_KP_SUBTRACT:
                        power = Math.max(2.0f, power - 0.5f);
                        needsReset = true;
                        System.out.println("Power: " + power);
                        break;
                }
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                mouseCaptured = true;
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                double[] x = new double[1], y = new double[1];
                glfwGetCursorPos(window, x, y);
                lastMouseX = x[0];
                lastMouseY = y[0];
            }
        });

        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (mouseCaptured) {
                double dx = xpos - lastMouseX;
                double dy = ypos - lastMouseY;
                lastMouseX = xpos;
                lastMouseY = ypos;

                // Rotate camera
                float sensitivity = 0.002f;
                rotateCamera((float) -dx * sensitivity, (float) -dy * sensitivity, 0);
                needsReset = true;
            }
        });

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            width = w;
            height = h;
            glViewport(0, 0, w, h);
            recreateFramebuffer();
            needsReset = true;
        });
    }

    private void createShaders() {
        // Load and compile shaders
        String vertexSource = loadShader("/shaders/fullscreen.vert");
        String commonSource = loadShader("/shaders/common.glsl");
        String raytracerSource = loadShader("/shaders/raytracer.glsl");
        String mandelbulbSource = loadShader("/shaders/mandelbulb.glsl");

        // Render program: common + mandelbulb DE + raytracer
        String renderFragSource = "#version 430 core\n" +
            commonSource + "\n" +
            mandelbulbSource + "\n" +
            raytracerSource;

        renderProgram = createProgram(vertexSource, renderFragSource);

        // Display program: simple texture display with tone mapping
        String displayFragSource = loadShader("/shaders/display.glsl");
        displayProgram = createProgram(vertexSource, displayFragSource);
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);
        checkShaderError(vertexShader, "Vertex");

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);
        checkShaderError(fragmentShader, "Fragment");

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        checkProgramError(program);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        return program;
    }

    private void checkShaderError(int shader, String type) {
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            throw new RuntimeException(type + " shader compilation failed:\n" + log);
        }
    }

    private void checkProgramError(int program) {
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new RuntimeException("Program linking failed:\n" + log);
        }
    }

    private void createFramebuffer() {
        // Create accumulation texture (float32 RGBA for precision)
        accumTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, accumTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Create FBO
        accumFBO = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, accumTexture, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete!");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void recreateFramebuffer() {
        glDeleteTextures(accumTexture);
        glDeleteFramebuffers(accumFBO);
        createFramebuffer();
    }

    private void createFullscreenQuad() {
        float[] vertices = {
            -1.0f, -1.0f,  0.0f, 0.0f,
             1.0f, -1.0f,  1.0f, 0.0f,
             1.0f,  1.0f,  1.0f, 1.0f,
            -1.0f,  1.0f,  0.0f, 1.0f
        };
        int[] indices = {0, 1, 2, 2, 3, 0};

        quadVAO = glGenVertexArrays();
        int vbo = glGenBuffers();
        int ebo = glGenBuffers();

        glBindVertexArray(quadVAO);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
    }

    private void loop() {
        long lastTime = System.nanoTime();
        int frames = 0;

        while (!glfwWindowShouldClose(window)) {
            // Handle input
            handleInput();

            // Reset accumulation if needed
            if (needsReset) {
                clearAccumulation();
                needsReset = false;
            }

            // Render one sample (progressive)
            if (sampleCount < maxSamples) {
                renderSample();
                sampleCount++;
            }

            // Display result
            displayResult();

            glfwSwapBuffers(window);
            glfwPollEvents();

            // FPS counter
            frames++;
            long currentTime = System.nanoTime();
            if (currentTime - lastTime >= 1_000_000_000L) {
                glfwSetWindowTitle(window, String.format(
                    "GLSL Fractal Prototype - %d FPS - %d/%d samples - Power: %.1f",
                    frames, sampleCount, maxSamples, power
                ));
                frames = 0;
                lastTime = currentTime;
            }
        }
    }

    private void handleInput() {
        float[] forward = getForwardVector();
        float[] right = getRightVector();
        float[] up = {0, 1, 0};

        boolean moved = false;

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
            camPos[0] += forward[0] * moveSpeed;
            camPos[1] += forward[1] * moveSpeed;
            camPos[2] += forward[2] * moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
            camPos[0] -= forward[0] * moveSpeed;
            camPos[1] -= forward[1] * moveSpeed;
            camPos[2] -= forward[2] * moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS) {
            camPos[0] -= right[0] * moveSpeed;
            camPos[1] -= right[1] * moveSpeed;
            camPos[2] -= right[2] * moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS || glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS) {
            camPos[0] += right[0] * moveSpeed;
            camPos[1] += right[1] * moveSpeed;
            camPos[2] += right[2] * moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            camPos[1] += moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) {
            camPos[1] -= moveSpeed;
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_Q) == GLFW_PRESS) {
            rotateCamera(0, 0, 0.02f);
            moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS) {
            rotateCamera(0, 0, -0.02f);
            moved = true;
        }

        if (moved) {
            needsReset = true;
        }
    }

    private void clearAccumulation() {
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        sampleCount = 0;
    }

    private void renderSample() {
        glBindFramebuffer(GL_FRAMEBUFFER, accumFBO);
        glUseProgram(renderProgram);

        // Enable additive blending for accumulation
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        // Set uniforms
        glUniform2f(glGetUniformLocation(renderProgram, "resolution"), width, height);
        glUniform3f(glGetUniformLocation(renderProgram, "camPos"), camPos[0], camPos[1], camPos[2]);
        glUniform4f(glGetUniformLocation(renderProgram, "camQuat"), camQuat[0], camQuat[1], camQuat[2], camQuat[3]);
        glUniform1f(glGetUniformLocation(renderProgram, "fov"), fov);
        glUniform1f(glGetUniformLocation(renderProgram, "power"), power);
        glUniform1i(glGetUniformLocation(renderProgram, "maxIterations"), maxIterations);
        glUniform1i(glGetUniformLocation(renderProgram, "sampleIndex"), sampleCount);
        glUniform1f(glGetUniformLocation(renderProgram, "time"), (float) glfwGetTime());

        // Light
        glUniform3f(glGetUniformLocation(renderProgram, "lightDir"), 0.5f, 0.8f, 0.6f);
        glUniform3f(glGetUniformLocation(renderProgram, "lightColor"), 1.0f, 0.95f, 0.9f);
        glUniform3f(glGetUniformLocation(renderProgram, "ambientColor"), 0.1f, 0.12f, 0.15f);

        // Render fullscreen quad
        glBindVertexArray(quadVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void displayResult() {
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(displayProgram);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, accumTexture);
        glUniform1i(glGetUniformLocation(displayProgram, "accumTexture"), 0);
        glUniform1i(glGetUniformLocation(displayProgram, "sampleCount"), Math.max(1, sampleCount));

        glBindVertexArray(quadVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
    }

    // === Quaternion Camera Helpers ===

    private void resetCamera() {
        camPos[0] = 0.0f;
        camPos[1] = 0.0f;
        camPos[2] = -3.0f;
        camQuat[0] = 0.0f;
        camQuat[1] = 0.0f;
        camQuat[2] = 0.0f;
        camQuat[3] = 1.0f;
        needsReset = true;
        System.out.println("Camera reset");
    }

    private void rotateCamera(float yaw, float pitch, float roll) {
        // Create rotation quaternions
        float[] qYaw = axisAngleToQuat(0, 1, 0, yaw);
        float[] qPitch = axisAngleToQuat(1, 0, 0, pitch);
        float[] qRoll = axisAngleToQuat(0, 0, 1, roll);

        // Apply rotations: current * yaw * pitch * roll
        camQuat = multiplyQuat(camQuat, qYaw);
        camQuat = multiplyQuat(camQuat, qPitch);
        camQuat = multiplyQuat(camQuat, qRoll);
        normalizeQuat(camQuat);
    }

    private float[] getForwardVector() {
        // Rotate (0, 0, 1) by camera quaternion
        return rotateByQuat(new float[]{0, 0, 1}, camQuat);
    }

    private float[] getRightVector() {
        // Rotate (1, 0, 0) by camera quaternion
        return rotateByQuat(new float[]{1, 0, 0}, camQuat);
    }

    private float[] axisAngleToQuat(float ax, float ay, float az, float angle) {
        float halfAngle = angle * 0.5f;
        float s = (float) Math.sin(halfAngle);
        return new float[]{ax * s, ay * s, az * s, (float) Math.cos(halfAngle)};
    }

    private float[] multiplyQuat(float[] a, float[] b) {
        return new float[]{
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    private void normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        q[0] /= len;
        q[1] /= len;
        q[2] /= len;
        q[3] /= len;
    }

    private float[] rotateByQuat(float[] v, float[] q) {
        float[] qConj = {-q[0], -q[1], -q[2], q[3]};
        float[] vQuat = {v[0], v[1], v[2], 0};
        float[] result = multiplyQuat(multiplyQuat(q, vQuat), qConj);
        return new float[]{result[0], result[1], result[2]};
    }

    // === Resource Loading ===

    private String loadShader(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Shader not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private void cleanup() {
        glDeleteProgram(renderProgram);
        glDeleteProgram(displayProgram);
        glDeleteFramebuffers(accumFBO);
        glDeleteTextures(accumTexture);
        glDeleteVertexArrays(quadVAO);

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    public static void main(String[] args) {
        new GLSLPrototype().run();
    }
}
