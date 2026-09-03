module Fractaliz3r {
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.media;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires org.lwjgl.stb;
    requires java.desktop;
    requires com.google.gson;
    exports org.fractalizer;
    requires transitive org.lwjgl.natives;

    opens org.fractalizer.config to com.google.gson;
    opens org.fractalizer.fractals to com.google.gson;
    opens org.fractalizer.animation to com.google.gson;
    // FractalConfig carries the post-processing chain as a GLSLEngine.PostProcessParams
    // field that Gson serializes by reflection. Without this line File > Save throws
    // JsonIOException whenever the app runs as a module (javafx:run, the jlink image);
    // it only worked from the classpath, which is why no harness had caught it.
    opens org.fractalizer.engine to com.google.gson;
}