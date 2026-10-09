package br.com.cofre.application;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.domain.Credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.*;
import java.util.List;

class VaultServiceTest {
    @TempDir Path temp;

    private char[] master() {
        return "uma senha mestra forte".toCharArray();
    }

    private Credential credential() {
        return new Credential(
                "Conta privada",
                "usuario-secreto",
                "senha-secreta",
                List.of("https://conta.exemplo.com"),
                false,
                "nota confidencial");
    }

    @Test
    void creationAcceptsMinimumLengthAndClearsPasswordOnRejection() throws Exception {
        try (var vault = new VaultService(temp.resolve("minimum-length.db"))) {
            char[] shortPassword = "12345678901".toCharArray();
            assertThrows(IllegalArgumentException.class, () -> vault.create(shortPassword));
            assertArrayEquals(new char[shortPassword.length], shortPassword);
            assertFalse(vault.exists());

            char[] minimumPassword = "123456789012".toCharArray();
            vault.create(minimumPassword);
            assertArrayEquals(new char[minimumPassword.length], minimumPassword);
            assertTrue(vault.unlocked());
        }
    }

    @Test
    void persistenceLockAndWrongPassword() throws Exception {
        Path file = temp.resolve("vault.db");
        String id;
        try (var vault = new VaultService(file)) {
            vault.create(master());
            id = vault.save(null, credential());
            vault.lock();
            assertThrows(IllegalStateException.class, vault::list);
            assertThrows(IllegalStateException.class, () -> vault.save(null, credential()));
            assertThrows(Exception.class, () -> vault.unlock("senha incorreta".toCharArray()));
            assertFalse(vault.unlocked());
        }
        try (var vault = new VaultService(file)) {
            vault.unlock(master());
            assertEquals(credential(), vault.list().getFirst().credential());
            vault.save(id, new Credential("Editada", "outro", "outra senha", List.of(), false, ""));
            assertEquals(1, vault.list().size());
            assertEquals("Editada", vault.list().getFirst().credential().title());
            vault.delete(id);
            assertTrue(vault.list().isEmpty());
        }
    }

    @Test
    void noPlaintextAndRejectTampering() throws Exception {
        Path file = temp.resolve("vault.db");
        try (var vault = new VaultService(file)) {
            vault.create(master());
            vault.save(null, credential());
            String raw =
                    new String(
                            Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String secret :
                    List.of(
                            "Conta privada",
                            "usuario-secreto",
                            "senha-secreta",
                            "conta.exemplo.com",
                            "nota confidencial")) assertFalse(raw.contains(secret));
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file);
                    Statement s = db.createStatement()) {
                s.executeUpdate("UPDATE vault_item SET ciphertext=zeroblob(length(ciphertext))");
            }
            assertThrows(Exception.class, vault::list);
            assertFalse(vault.unlocked());
        }
    }

    @Test
    void validateCreationAndOrigins() throws Exception {
        try (var vault = new VaultService(temp.resolve("vault.db"))) {
            assertThrows(IllegalArgumentException.class, () -> vault.create("curta".toCharArray()));
            assertFalse(vault.exists());
            vault.create(master());
            assertThrows(IllegalStateException.class, () -> vault.create(master()));
        }
        assertEquals(
                "https://conta.exemplo.com", Credential.origin("https://CONTA.exemplo.com:443/"));
        assertThrows(IllegalArgumentException.class, () -> Credential.origin("http://exemplo.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Credential.origin("https://exemplo.com/login"));
    }
}
