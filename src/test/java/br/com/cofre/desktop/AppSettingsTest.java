package br.com.cofre.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppSettingsTest {
    @TempDir Path directory;

    @Test
    void loadsDefaultsPersistsAndReloadsIndependentOfVault() throws Exception {
        Path file = directory.resolve("settings.properties");
        var store = new AppSettingsStore(file);
        assertEquals(new AppSettings(300, 30), store.load());
        store.save(new AppSettings(60, 15));
        assertEquals(new AppSettings(60, 15), new AppSettingsStore(file).load());
        store.save(new AppSettings(0, 1));
        assertEquals(new AppSettings(0, 1), store.load());
    }

    @Test
    void rejectsInvalidValuesWithoutReplacingSavedPreferences() throws Exception {
        var store = new AppSettingsStore(directory.resolve("settings.properties"));
        store.save(new AppSettings(60, 15));
        assertThrows(IllegalArgumentException.class, () -> new AppSettings(-1, 30));
        assertThrows(IllegalArgumentException.class, () -> new AppSettings(86401, 30));
        assertThrows(IllegalArgumentException.class, () -> new AppSettings(60, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppSettings(60, 3601));
        assertEquals(new AppSettings(60, 15), store.load());
        Files.writeString(directory.resolve("settings.properties"), "idleLockSeconds=invalid");
        assertThrows(java.io.IOException.class, store::load);
        Files.writeString(directory.resolve("settings.properties"), "idleLockSeconds=" + "\\" + "uZZZZ");
        assertThrows(java.io.IOException.class, store::load);
    }
}
