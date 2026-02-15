package org.fractalizer.fractals;

/**
 * Enumeration of supported fractal types.
 */
public enum FractalType {
    MANDELBULB("Mandelbulb", "mandelbulb"),
    MANDELBOX("Mandelbox", "mandelbox"),
    MENGER_SPONGE("Menger Sponge", "menger"),
    KALEIDOSCOPIC_IFS("Kaleidoscopic IFS", "kaleidoscopic"),
    POLYHEDRAL_IFS("Polyhedral IFS", "polyhedral"),
    SIERPINSKI("Sierpinski Tetrahedron", "sierpinski"),
    PSEUDO_KLEINIAN("Pseudo-Kleinian", "pseudokleinian"),
    APOLLONIAN("Apollonian Gasket", "apollonian"),
    BRISTORBROT("Bristorbrot", "bristorbrot"),
    QUATERNION_JULIA_4D("Quaternion Julia 4D", "quaternionjulia4d"),
    TEST_SCENE("Test Scene", "testscene"),
    CORNELL_BOX("Cornell Box", "cornellbox");

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