package org.fractalizer.test;

import org.fractalizer.engine.DeepSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The double elementary functions DeepSource gives a scene's estimator, evaluated on the GPU
 * (the compiler and hardware that run them, not a model of them) against StrictMath, over
 * random arguments in the ranges an estimator feeds them. Each line: the worst error in units
 * of the double's last place and in absolute terms, with the argument it happened at; and the
 * same function evaluated in float (what the float program has), for scale. Passes when every
 * error is within 1e-13, relative above 1 and absolute below (near a zero of sin the last
 * place of the result says nothing: the argument itself is only known to its own last place).
 *
 * mvn compile exec:java -Dexec.mainClass="org.fractalizer.test.DoubleMathProbe" -Dexec.args="200000"
 */
public class DoubleMathProbe {

    record Case(String name, String glsl, String glslFloat, DoubleBinaryOperator ref, double ax, double bx, double ay, double by, boolean logX) {}

    static final Case[] CASES = {
            new Case("sin", "dp_sin(x)", "sin(float(x))", (x, y) -> StrictMath.sin(x), -30, 30, 0, 0, false),
            new Case("cos", "dp_cos(x)", "cos(float(x))", (x, y) -> StrictMath.cos(x), -30, 30, 0, 0, false),
            new Case("tan", "dp_tan(x)", "tan(float(x))", (x, y) -> StrictMath.tan(x), -1.4, 1.4, 0, 0, false),
            new Case("atan2", "dp_atan2(x, y)", "atan(float(x), float(y))", StrictMath::atan2, -2, 2, -2, 2, false),
            new Case("atan2 axis", "dp_atan2(x, y)", "atan(float(x), float(y))", StrictMath::atan2, -1e-9, 1e-9, -2, 2, false),
            new Case("atan", "dp_atan(x)", "atan(float(x))", (x, y) -> StrictMath.atan(x), -50, 50, 0, 0, false),
            new Case("acos", "dp_acos(x)", "acos(float(x))", (x, y) -> StrictMath.acos(x), -1, 1, 0, 0, false),
            new Case("asin", "dp_asin(x)", "asin(float(x))", (x, y) -> StrictMath.asin(x), -1, 1, 0, 0, false),
            new Case("exp", "dp_exp(x)", "exp(float(x))", (x, y) -> StrictMath.exp(x), -50, 50, 0, 0, false),
            new Case("log", "dp_log(x)", "log(float(x))", (x, y) -> StrictMath.log(x), -40, 40, 0, 0, true),
            new Case("pow r^8", "dp_pow(x, y)", "pow(float(x), float(y))", StrictMath::pow, 0.01, 3, 8, 8, false),
            new Case("pow r^7", "dp_pow(x, y)", "pow(float(x), float(y))", StrictMath::pow, 0.01, 3, 7, 7, false),
            new Case("pow r^7.5", "dp_pow(x, y)", "pow(float(x), float(y))", StrictMath::pow, 0.01, 3, 7.5, 7.5, false),
            new Case("pow r^-2.3", "dp_pow(x, y)", "pow(float(x), float(y))", StrictMath::pow, 0.01, 3, -2.3, -2.3, false),
            new Case("sinh", "dp_sinh(x)", "sinh(float(x))", (x, y) -> StrictMath.sinh(x), -5, 5, 0, 0, false),
            new Case("tanh", "dp_tanh(x)", "tanh(float(x))", (x, y) -> StrictMath.tanh(x), -5, 5, 0, 0, false),
    };

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        if (!glfwInit()) throw new IllegalStateException("GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(64, 64, "DoubleMathProbe", NULL, NULL);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("GPU: " + glGetString(GL_RENDERER));

        String lib = DeepSource.library(Set.of("sin", "cos", "tan", "atan", "acos", "asin", "exp", "log", "pow", "sinh", "tanh"));
        StringBuilder sw = new StringBuilder();
        for (int c = 0; c < CASES.length; c++) {
            sw.append("        case ").append(c).append(": r = ").append(CASES[c].glsl()).append("; f = double(").append(CASES[c].glslFloat()).append("); break;\n");
        }
        String src = "#version 430\nlayout(local_size_x = 64) in;\n"
                + "layout(std430, binding = 0) readonly buffer InX { double inX[]; };\n"
                + "layout(std430, binding = 1) readonly buffer InY { double inY[]; };\n"
                + "layout(std430, binding = 2) writeonly buffer Out { double outV[]; };\n"
                + "uniform int fn; uniform int count;\n"
                + lib
                + "\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                + "    double x = inX[i], y = inY[i], r = 0.0LF, f = 0.0LF;\n    switch (fn) {\n" + sw + "    }\n"
                + "    outV[2 * i] = r;\n    outV[2 * i + 1] = f;\n}\n";
        int cs = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(cs, src);
        glCompileShader(cs);
        if (glGetShaderi(cs, GL_COMPILE_STATUS) == GL_FALSE) {
            System.out.println(src);
            throw new RuntimeException(glGetShaderInfoLog(cs));
        }
        int prog = glCreateProgram();
        glAttachShader(prog, cs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(prog));
        glUseProgram(prog);

        int[] ssbo = new int[3];
        glGenBuffers(ssbo);
        Random rnd = new Random(1);
        System.out.printf("%-11s | %12s %12s %26s | %12s%n", "function", "worst ulp", "worst abs", "at", "float abs");
        boolean ok = true;
        for (int c = 0; c < CASES.length; c++) {
            Case k = CASES[c];
            double[] xs = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                double u = k.ax() + (k.bx() - k.ax()) * rnd.nextDouble();
                xs[i] = k.logX() ? Math.exp(u) : u;
                ys[i] = k.ay() + (k.by() - k.ay()) * rnd.nextDouble();
            }
            upload(ssbo[0], 0, xs);
            upload(ssbo[1], 1, ys);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
            glBufferData(GL_SHADER_STORAGE_BUFFER, 16L * n, GL_DYNAMIC_READ);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo[2]);
            glUniform1i(glGetUniformLocation(prog, "fn"), c);
            glUniform1i(glGetUniformLocation(prog, "count"), n);
            glDispatchCompute((n + 63) / 64, 1, 1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            ByteBuffer out = BufferUtils.createByteBuffer(16 * n).order(ByteOrder.nativeOrder());
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, out);
            DoubleBuffer o = out.asDoubleBuffer();
            double worstUlp = 0, worstAbs = 0, worstFloat = 0, worstScaled = 0;
            String at = "";
            int nan = 0;
            for (int i = 0; i < n; i++) {
                double ref = k.ref().applyAsDouble(xs[i], ys[i]);
                double r = o.get(2 * i), f = o.get(2 * i + 1);
                if (Double.isNaN(r) && !Double.isNaN(ref)) { nan++; continue; }
                double abs = Math.abs(r - ref);
                double ulp = abs / Math.ulp(Math.max(Math.abs(ref), Double.MIN_NORMAL));
                worstScaled = Math.max(worstScaled, abs / Math.max(Math.abs(ref), 1.0));
                if (ulp > worstUlp) {
                    worstUlp = ulp;
                    worstAbs = abs;
                    at = k.ay() == k.by() && k.ay() == 0 ? String.format(Locale.ROOT, "%.10g", xs[i])
                            : String.format(Locale.ROOT, "%.6g, %.6g", xs[i], ys[i]);
                }
                if (!Double.isNaN(f)) worstFloat = Math.max(worstFloat, Math.abs(f - ref));
            }
            System.out.printf(Locale.ROOT, "%-11s | %12.1f %12.3g %26s | %12.3g%s%n", k.name(), worstUlp, worstAbs, at, worstFloat,
                    nan > 0 ? "   NaN x" + nan : "");
            if (worstScaled > 1e-13 || nan > 0) ok = false;
        }
        System.out.println(ok ? "RESULT: PASS (every function within 1e-13, relative above 1, absolute below)" : "RESULT: FAIL");
        if (args.length > 1 && args[1].equals("bench")) bench(lib, ssbo);
        glfwDestroyWindow(window);
        glfwTerminate();
        System.exit(ok ? 0 : 1);
    }

    /** What each function costs on this GPU, against one double fused multiply-add: every
     *  thread evaluates it in a chain (each result feeds the next argument, so nothing is
     *  hoisted or shared), timed to the GPU's finish, best of five. Dispatches are kept far
     *  below the driver's two-second watchdog. */
    static void bench(String lib, int[] ssbo) {
        String[][] cases = {
                {"double fma", "fma(x, 1.0000001LF, y)"},
                {"double div", "y / x"},
                {"double sqrt", "sqrt(x)"},
                {"float sin", "double(sin(float(x)))"},
                {"float atan2", "double(atan(float(y), float(x)))"},
                {"dp_sin", "dp_sin(x)"},
                {"dp_sin+dp_cos", "dp_sin(x) + dp_cos(x)"},
                {"dp_atan2", "dp_atan2(y, x)"},
                {"dp_acos", "dp_acos(x * 0.3LF - 0.45LF)"},
                {"dp_pow ^8", "dp_pow(x, 8.0LF)"},
                {"dp_pow ^7.5", "dp_pow(x, 7.5LF)"},
                {"dp_exp", "dp_exp(x)"},
                {"dp_log", "dp_log(x)"},
        };
        StringBuilder sw = new StringBuilder();
        for (int c = 0; c < cases.length; c++) sw.append("            case ").append(c).append(": v = ").append(cases[c][1]).append("; break;\n");
        String src = "#version 430\nlayout(local_size_x = 64) in;\n"
                + "layout(std430, binding = 0) readonly buffer InX { double inX[]; };\n"
                + "layout(std430, binding = 1) readonly buffer InY { double inY[]; };\n"
                + "layout(std430, binding = 2) writeonly buffer Out { double outV[]; };\n"
                + "uniform int fn; uniform int count; uniform int reps;\n" + lib
                + "\nvoid main() {\n    int i = int(gl_GlobalInvocationID.x);\n    if (i >= count) return;\n"
                + "    double x = inX[i], y = inY[i], acc = 0.0LF;\n"
                + "    for (int k = 0; k < reps; k++) {\n        double v = 0.0LF;\n        switch (fn) {\n" + sw + "        }\n"
                + "        acc = acc + v;\n        x = x + v * 1.0e-30LF;\n    }\n    outV[i] = acc;\n}\n";
        int cs = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(cs, src);
        glCompileShader(cs);
        if (glGetShaderi(cs, GL_COMPILE_STATUS) == GL_FALSE) throw new RuntimeException(glGetShaderInfoLog(cs));
        int prog = glCreateProgram();
        glAttachShader(prog, cs);
        glLinkProgram(prog);
        glUseProgram(prog);
        int n = 1 << 17;
        double[] xs = new double[n], ys = new double[n];
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) { xs[i] = 0.1 + 2.9 * rnd.nextDouble(); ys[i] = -2 + 4 * rnd.nextDouble(); }
        upload(ssbo[0], 0, xs);
        upload(ssbo[1], 1, ys);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo[2]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 8L * n, GL_DYNAMIC_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo[2]);
        glUniform1i(glGetUniformLocation(prog, "count"), n);
        System.out.printf("%n%-14s | %10s %10s%n", "cost", "ns/eval", "x fma");
        double fmaNs = 0;
        for (int c = 0; c < cases.length; c++) {
            glUniform1i(glGetUniformLocation(prog, "fn"), c);
            int reps = 4;
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 7; round++) {
                glUniform1i(glGetUniformLocation(prog, "reps"), reps);
                glFinish();
                long t0 = System.nanoTime();
                glDispatchCompute(n / 64, 1, 1);
                glFinish();
                long dt = System.nanoTime() - t0;
                if (round < 2) {                       // warm up, and size the chain to ~50 ms
                    if (dt < 25_000_000L && reps < 4096) reps = (int) Math.min(4096, reps * Math.max(2, 50_000_000L / Math.max(dt, 1)));
                    continue;
                }
                best = Math.min(best, (double) dt / ((double) n * reps));
            }
            if (c == 0) fmaNs = best;
            System.out.printf(Locale.ROOT, "%-14s | %10.4f %10.1f%n", cases[c][0], best, best / fmaNs);
        }
    }

    static void upload(int buffer, int binding, double[] v) {
        DoubleBuffer b = BufferUtils.createDoubleBuffer(v.length);
        b.put(v).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, b, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, buffer);
    }
}
