package org.fractalizer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The packaged app finds hdri/ and presets/ where jpackage put them; a checkout keeps its own. */
class DataDirsTest {

    @Test
    void shippedFolderWhenTheWorkingDirectoryHasNone(@TempDir Path work, @TempDir Path shipped) throws Exception {
        Files.createDirectory(shipped.resolve("hdri"));
        File found = DataDirs.of("hdri", shipped.toString(), work.toFile());
        assertEquals(shipped.resolve("hdri").toFile(), found);
        assertTrue(found.isDirectory());
    }

    @Test
    void workingDirectoryWinsWhenBothExist(@TempDir Path work, @TempDir Path shipped) throws Exception {
        Files.createDirectory(shipped.resolve("presets"));
        Files.createDirectory(work.resolve("presets"));
        assertEquals(work.resolve("presets").toFile(), DataDirs.of("presets", shipped.toString(), work.toFile()));
    }

    @Test
    void workingDirectoryPathWhenNothingExists(@TempDir Path work) {
        File found = DataDirs.of("presets", null, work.toFile());
        assertEquals(work.resolve("presets").toFile(), found);
        assertFalse(found.exists());
        assertEquals(found, DataDirs.of("presets", "", work.toFile()));
        assertEquals(found, DataDirs.of("presets", work.resolve("missing").toString(), work.toFile()));
    }
}
