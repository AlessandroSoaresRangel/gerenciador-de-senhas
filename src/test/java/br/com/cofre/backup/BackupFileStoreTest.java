package br.com.cofre.backup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BackupFileStoreTest {
    @TempDir Path directory;

    @Test
    void publishCompleteFileAndPreserveExistingDestination() throws Exception {
        var files = new BackupFileStore();
        Path destination = directory.resolve("backup.cofre");
        byte[] contents = {1, 2, 3};
        files.write(destination, contents);
        assertArrayEquals(contents, files.read(destination));
        assertThrows(Exception.class, () -> files.write(destination, new byte[] {4}));
        assertArrayEquals(contents, files.read(destination));
        try (var children = Files.list(directory)) {
            assertEquals(List.of(destination), children.toList());
        }
    }

    @Test
    void rejectOversizedFileBeforeReadingItsContents() throws Exception {
        Path source = directory.resolve("large.cofre");
        // Arquivo esparso: verifica o limite sem alocar 64 MiB em memória no teste.
        try (var file = new java.io.RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        assertThrows(IllegalArgumentException.class, () -> new BackupFileStore().read(source));
    }
}
