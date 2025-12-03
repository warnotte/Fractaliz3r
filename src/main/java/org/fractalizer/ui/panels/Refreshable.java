package org.fractalizer.ui.panels;

/**
 * Interface for UI panels that can refresh their controls from parameters.
 * Used for save/load configuration feature.
 */
public interface Refreshable {

    /**
     * Refresh all UI controls from the current parameters.
     * Called after loading a configuration file.
     *
     * @param suppressRender If true, don't trigger render callbacks during refresh
     */
    void refreshFromParams(boolean suppressRender);

    /**
     * Convenience method - refresh with render suppression enabled.
     */
    default void refreshFromParams() {
        refreshFromParams(true);
    }
}