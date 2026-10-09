package br.com.cofre.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class SqliteVaultRepositoryTest {
    @TempDir Path directory;

    private VaultHeader header() {
        return new VaultHeader(1, new byte[16], 65536, 3, 1, new byte[12], new byte[48]);
    }

    @Test
    void persistHeaderAndUpdateEncryptedItemWithoutDuplicates() throws Exception {
        Path database = directory.resolve("vault.db");
        try (var repository = new SqliteVaultRepository(database)) {
            assertFalse(repository.exists());
            repository.insertHeader(header());
            repository.saveItem(new EncryptedItem("id", 1, new byte[12], new byte[] {1}));
            repository.saveItem(new EncryptedItem("id", 1, new byte[12], new byte[] {2}));
        }
        try (var repository = new SqliteVaultRepository(database)) {
            assertTrue(repository.exists());
            assertEquals(65536, repository.readHeader().memory());
            assertEquals(1, repository.readItems().size());
            assertArrayEquals(new byte[] {2}, repository.readItems().getFirst().ciphertext());
            repository.deleteItem("id");
            assertTrue(repository.readItems().isEmpty());
        }
    }

    @Test
    void duplicateIdsRollBackReplacementAndRepositoryRemainsUsable() throws Exception {
        try (var repository = new SqliteVaultRepository(directory.resolve("vault.db"))) {
            repository.insertHeader(header());
            repository.saveItem(new EncryptedItem("original", 1, new byte[12], new byte[] {1}));
            var duplicate = new EncryptedItem("duplicate", 1, new byte[12], new byte[] {2});
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            repository.replace(
                                    new VaultSnapshot(header(), List.of(duplicate, duplicate))));
            assertEquals("original", repository.readItems().getFirst().id());
            repository.replace(new VaultSnapshot(header(), List.of(duplicate)));
            assertEquals("duplicate", repository.readItems().getFirst().id());
        }
    }
}
