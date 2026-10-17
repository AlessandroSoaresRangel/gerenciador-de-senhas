package br.com.cofre.desktop;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.Properties;

/** Persiste preferencias sem segredos, separadas do banco e do formato criptografico do cofre. */
final class AppSettingsStore {
    private static final String IDLE_LOCK_SECONDS = "idleLockSeconds";
    private static final String CLIPBOARD_CLEAR_SECONDS = "clipboardClearSeconds";
    private final Path path;

    AppSettingsStore(Path path) {
        this.path = path.toAbsolutePath();
    }

    AppSettings load() throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (NoSuchFileException absent) {
            return AppSettings.defaults();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Arquivo de preferências inválido.", invalid);
        }
        try {
            return new AppSettings(
                    Integer.parseInt(properties.getProperty(IDLE_LOCK_SECONDS,
                            Integer.toString(AppSettings.DEFAULT_IDLE_LOCK_SECONDS))),
                    Integer.parseInt(properties.getProperty(CLIPBOARD_CLEAR_SECONDS,
                            Integer.toString(AppSettings.DEFAULT_CLIPBOARD_CLEAR_SECONDS))));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Preferencias de tempo invalidas.", invalid);
        }
    }

    /** Publica o arquivo completo; falhas anteriores a substituicao preservam o anterior. */
    void save(AppSettings settings) throws IOException {
        var properties = new Properties();
        properties.setProperty(IDLE_LOCK_SECONDS, Integer.toString(settings.idleLockSeconds()));
        properties.setProperty(CLIPBOARD_CLEAR_SECONDS, Integer.toString(settings.clipboardClearSeconds()));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".settings-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Cofre local - tempos em segundos");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
