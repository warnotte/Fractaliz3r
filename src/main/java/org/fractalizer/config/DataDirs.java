package org.fractalizer.config;

import java.io.File;

/**
 * Where the data folders live: {@code presets/} (the shipped scenes) and {@code hdri/} (the
 * environment maps). In a checkout they sit in the working directory. In a packaged app the
 * launcher passes {@code -Dfractalizer.data=$APPDIR} and jpackage has copied the folders there
 * ({@code app/} on Windows, {@code lib/app/} on Linux); the working directory is then wherever
 * the app was started from and usually holds nothing. A folder in the working directory wins
 * when it exists, so a user can still drop their own next to where they launch.
 */
public final class DataDirs {

    /** System property naming the directory the packaged data folders were copied to. */
    public static final String PROPERTY = "fractalizer.data";

    private DataDirs() {}

    /** The folder of that name: in the working directory when it is there, else in the
     *  packaged data directory when it is there, else the working-directory path (absent). */
    public static File of(String name) {
        return of(name, System.getProperty(PROPERTY), new File(System.getProperty("user.dir", ".")));
    }

    static File of(String name, String dataProperty, File workingDir) {
        File local = new File(workingDir, name);
        if (local.isDirectory()) return local;
        if (dataProperty != null && !dataProperty.isBlank()) {
            File shipped = new File(dataProperty, name);
            if (shipped.isDirectory()) return shipped;
        }
        return local;
    }
}
