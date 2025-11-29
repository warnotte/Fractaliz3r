package org.fractalizer.ui.panels;

/**
 * Callback interface for panels to signal that a render is needed.
 */
@FunctionalInterface
public interface RenderCallback {
    void requestRender();
}
