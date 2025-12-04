module Fractaliz3r {
    requires javafx.graphics;
    requires javafx.controls;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires org.lwjgl.stb;
    requires java.desktop;
    requires com.google.gson;
    exports org.fractalizer;
    requires transitive org.lwjgl.natives;

}