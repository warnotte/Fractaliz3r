package org.fractalizer.fractals;

/**
 * Enumeration of supported fractal types.
 */
public enum FractalType {
    MANDELBULB("Mandelbulb", "mandelbulb"),
    MANDELBOX("Mandelbox", "mandelbox"),
    MENGER_SPONGE("Menger Sponge", "menger"),
    KALEIDOSCOPIC_IFS("Kaleidoscopic IFS", "kaleidoscopic"),
    JULIA_3D("Julia 3D", "julia3d"),
    PSEUDO_KLEINIAN("Pseudo Kleinian", "pseudokleinian");

    private final String displayName;
    private final String kernelName;

    FractalType(String displayName, String kernelName) {
        this.displayName = displayName;
        this.kernelName = kernelName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getKernelName() {
        return kernelName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}