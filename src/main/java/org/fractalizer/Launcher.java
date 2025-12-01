package org.fractalizer;

/**
 * Launcher class to work around JavaFX packaging issues.
 * This class doesn't extend Application, which allows the fat JAR to work correctly.
 */
public class Launcher {
    public static void main(String[] args) {
        GLSLFractalizerApp.main(args);
    }
}