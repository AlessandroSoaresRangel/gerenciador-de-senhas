package br.com.cofre.application;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.domain.Credential;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class VaultBackupTest {
    @Test
    void explainWhenBackupDestinationIsADirectory() throws Exception {
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            vault.create(password());
            var failure =
                    assertThrows(IllegalArgumentException.class, () -> vault.backup(directory));
            assertEquals(
                    "Informe o caminho completo de um novo arquivo, por exemplo"
                            + " C:/codex-lab/backup.cofre. O destino informado é uma pasta.",
                    failure.getMessage());
        }
    }

    @TempDir Path directory;

    private char[] password() {
        return "senha mestra de teste".toCharArray();
    }

    private Credential credential(String title) {
        return new Credential(
                title,
                "usuario privado",
                "senha privada",
                List.of("https://exemplo.com"),
                false,
                "notas privadas");
    }

    private Path createBackup() throws Exception {
        Path backup = directory.resolve("copia.cofre");
        try (var vault = new VaultService(directory.resolve("original.db"))) {
            vault.create(password());
            vault.save(null, credential("Conta secreta"));
            vault.backup(backup);
        }
        return backup;
    }

    @Test
    void restoreBackupAndReopenWithOriginalPassword() throws Exception {
        Path backup = createBackup();
        String contents = Files.readString(backup);
        for (String secret :
                List.of(
                        "Conta secreta",
                        "usuario privado",
                        "senha privada",
                        "exemplo.com",
                        "notas privadas")) {
            assertFalse(contents.contains(secret));
        }
        Path restored = directory.resolve("restored.db");
        try (var vault = new VaultService(restored)) {
            vault.restore(backup, password());
            assertFalse(vault.unlocked());
            vault.unlock(password());
            assertEquals(credential("Conta secreta"), vault.list().getFirst().credential());
        }
        try (var vault = new VaultService(restored)) {
            vault.unlock(password());
            assertEquals(1, vault.list().size());
        }
    }

    @Test
    void wrongPasswordAndTamperingPreserveCurrentVault() throws Exception {
        Path backup = createBackup();
        try (var vault = new VaultService(directory.resolve("current.db"))) {
            vault.create("outra senha mestra forte".toCharArray());
            vault.save(null, credential("Conta atual"));
            assertThrows(Exception.class, () -> vault.restore(backup, "incorreta".toCharArray()));
            var mapper = new ObjectMapper();
            var document =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.readTree(backup.toFile());
            byte[] ciphertext = document.get("ciphertext").binaryValue();
            ciphertext[0] ^= 1;
            document.put("ciphertext", ciphertext);
            mapper.writeValue(backup.toFile(), document);
            assertThrows(Exception.class, () -> vault.restore(backup, password()));
            vault.unlock("outra senha mestra forte".toCharArray());
            assertEquals(credential("Conta atual"), vault.list().getFirst().credential());
        }
    }

    @Test
    void rejectUnknownFormatAndTruncatedFile() throws Exception {
        Path backup = createBackup();
        var mapper = new ObjectMapper();
        var document =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(backup.toFile());
        document.put("version", 999);
        mapper.writeValue(backup.toFile(), document);
        try (var vault = new VaultService(directory.resolve("empty.db"))) {
            assertThrows(Exception.class, () -> vault.restore(backup, password()));
            assertFalse(vault.exists());
            Files.writeString(backup, "{truncado");
            assertThrows(Exception.class, () -> vault.restore(backup, password()));
            assertFalse(vault.exists());
        }
    }

    @Test
    void requireUnlockedVaultAndNeverOverwriteBackup() throws Exception {
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            Path backup = directory.resolve("existing.cofre");
            assertThrows(IllegalStateException.class, () -> vault.backup(backup));
            vault.create(password());
            vault.backup(backup);
            byte[] original = Files.readAllBytes(backup);
            assertThrows(Exception.class, () -> vault.backup(backup));
            assertArrayEquals(original, Files.readAllBytes(backup));
        }
    }

    @Test
    void replaceExistingVaultWithBackupPasswordAndEntries() throws Exception {
        Path backup = createBackup();
        try (var vault = new VaultService(directory.resolve("current.db"))) {
            vault.create("senha antiga do cofre".toCharArray());
            vault.save(null, credential("Conta antiga"));
            vault.restore(backup, password());
            assertFalse(vault.unlocked());
            assertThrows(
                    Exception.class, () -> vault.unlock("senha antiga do cofre".toCharArray()));
            vault.unlock(password());
            assertEquals(1, vault.list().size());
            assertEquals(credential("Conta secreta"), vault.list().getFirst().credential());
        }
    }

    @Test
    void rollbackRestorationWhenDatabaseWriteFails() throws Exception {
        Path backup = createBackup();
        Path current = directory.resolve("current.db");
        try (var vault = new VaultService(current)) {
            vault.create("senha antiga do cofre".toCharArray());
            vault.save(null, credential("Conta antiga"));
            // Simula uma falha depois dos DELETEs, para verificar o rollback do cofre inteiro.
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + current);
                    var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TRIGGER fail_restore BEFORE INSERT ON vault_item BEGIN SELECT"
                                + " RAISE(ABORT, 'falha de teste'); END");
            }
            assertThrows(Exception.class, () -> vault.restore(backup, password()));
            vault.unlock("senha antiga do cofre".toCharArray());
            assertEquals(credential("Conta antiga"), vault.list().getFirst().credential());
        }
    }
}
