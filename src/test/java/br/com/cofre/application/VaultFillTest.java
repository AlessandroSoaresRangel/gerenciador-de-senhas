package br.com.cofre.application;

import br.com.cofre.domain.Credential;
import br.com.cofre.backup.VaultBackupService;
import br.com.cofre.crypto.CredentialCipher;
import br.com.cofre.storage.SqliteVaultRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class VaultFillTest {
    @TempDir Path directory;

    @Test
    void supportsCurrentLimitAndLegacyLongMasterPasswords() throws Exception {
        String currentPassword = "x".repeat(4096);
        try (var vault = new VaultService(directory.resolve("current-limit.db"))) {
            vault.create(currentPassword.toCharArray());
            vault.lock();
            vault.unlock(currentPassword.toCharArray());
            assertTrue(vault.unlocked());
        }
        // Reconstroi um cabecalho v1 criado antes da politica de tamanho, sem alterar seu formato.
        String legacyPassword = "x".repeat(4097);
        Path legacy = directory.resolve("legacy.db");
        byte[] salt = br.com.cofre.crypto.VaultCrypto.random(16);
        byte[] wrapping = br.com.cofre.crypto.VaultCrypto.derive(legacyPassword.toCharArray(), salt,
                br.com.cofre.crypto.VaultCrypto.MEMORY_KIBIBYTES, 3, 1);
        byte[] key = br.com.cofre.crypto.VaultCrypto.random(32);
        try (var repository = new SqliteVaultRepository(legacy)) {
            var encrypted = br.com.cofre.crypto.VaultCrypto.encrypt(wrapping, key, "vault-key:1");
            repository.insertHeader(new br.com.cofre.storage.VaultHeader(1, salt,
                    br.com.cofre.crypto.VaultCrypto.MEMORY_KIBIBYTES, 3, 1, encrypted.nonce(), encrypted.ciphertext()));
        } finally {
            java.util.Arrays.fill(wrapping, (byte) 0);
            java.util.Arrays.fill(key, (byte) 0);
        }
        try (var vault = new VaultService(legacy)) {
            vault.unlock(legacyPassword.toCharArray());
            assertTrue(vault.unlocked());
        }
    }

    @Test
    void selectedHealthyAccountDoesNotDependOnUnrelatedCorruptRecord() throws Exception {
        Path database = directory.resolve("isolated.db");
        try (var vault = new VaultService(database)) {
            vault.create("synthetic master password".toCharArray());
            String healthy = vault.save(null, new Credential("Healthy", "user", "synthetic secret",
                    List.of("https://example.com"), true, ""));
            String corrupt = vault.save(null, new Credential("Corrupt", "other", "synthetic other",
                    List.of("https://other.example"), false, ""));
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
                    var statement = connection.prepareStatement("UPDATE vault_item SET ciphertext = zeroblob(32) WHERE id = ?")) {
                statement.setString(1, corrupt);
                statement.executeUpdate();
            }
            assertThrows(Exception.class, vault::list);
            assertEquals("synthetic secret", vault.requestFill("https://example.com", healthy, "document").password());
            assertEquals("synthetic secret", vault.requestAutomaticFill("https://example.com", healthy, "automatic").password());
            assertThrows(Exception.class, () -> vault.requestFill("https://other.example", corrupt, "bad"));
        }
    }

    @Test
    void newMasterPasswordLimitRejectsAndWipesWithoutCreatingVault() throws Exception {
        try (var vault = new VaultService(directory.resolve("limit.db"))) {
            char[] excessive = "x".repeat(4097).toCharArray();
            assertThrows(IllegalArgumentException.class, () -> vault.create(excessive));
            assertArrayEquals(new char[4097], excessive);
            assertFalse(vault.exists());
        }
    }

    @Test
    void automaticFillRequiresOptInAndExactOrigin() throws Exception {
        try (var vault = new VaultService(directory.resolve("automatic.db"))) {
            vault.create("senha mestra de teste".toCharArray());
            String id = vault.save(null, new Credential("Conta", "teste", "senha fictícia",
                    List.of("https://exemplo.com"), false, ""));
            assertThrows(IllegalArgumentException.class,
                    () -> vault.requestAutomaticFill("https://exemplo.com", id, "document"));
            vault.save(id, new Credential("Conta", "teste", "senha fictícia",
                    List.of("https://exemplo.com"), true, ""));
            assertThrows(IllegalArgumentException.class,
                    () -> vault.requestAutomaticFill("https://sub.exemplo.com", id, "document"));
            var fill = vault.requestAutomaticFill("https://exemplo.com", id, "document");
            vault.confirmFill(fill.authorization(), "https://exemplo.com", id, "document");
            vault.lock();
            assertThrows(IllegalStateException.class,
                    () -> vault.requestAutomaticFill("https://exemplo.com", id, "document"));
        }
    }

    @Test
    void injectedClockControlsExpirationWithoutWaiting() throws Exception {
        var clock = new MutableClock();
        try (var vault = new VaultService(
                new SqliteVaultRepository(directory.resolve("expiration.db")),
                new VaultBackupService(), new CredentialCipher(), new FillAuthorizations(clock))) {
            vault.create("senha mestra de teste".toCharArray());
            String accountId = vault.save(null, new Credential(
                    "Conta", "usuário", "senha fictícia", List.of("https://exemplo.com"), false, ""));
            var authorization = vault.requestFill("https://exemplo.com", accountId, "document");
            clock.advanceSeconds(11);
            assertThrows(IllegalStateException.class,
                    () -> vault.confirmFill(authorization.authorization(), "https://exemplo.com", accountId, "document"));
            assertTrue(vault.unlocked());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-01-01T00:00:00Z");

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Test
    void deletionInvalidatesAuthorizationAndInvalidDocumentsAreRejected() throws Exception {
        try (var vault = new VaultService(directory.resolve("delete.db"))) {
            vault.create("senha mestra de teste".toCharArray());
            var credential = new Credential(
                    "Conta", "usuário", "senha fictícia", List.of("https://exemplo.com"), false, "");
            String accountId = vault.save(null, credential);
            for (String document : new String[] {null, "", " ", "x".repeat(201)}) {
                assertThrows(IllegalArgumentException.class,
                        () -> vault.requestFill("https://exemplo.com", accountId, document));
            }
            var fill = vault.requestFill("https://exemplo.com", accountId, "document");
            vault.delete(accountId);
            assertThrows(IllegalStateException.class,
                    () -> vault.confirmFill(fill.authorization(), "https://exemplo.com", accountId, "document"));
            assertTrue(vault.list().isEmpty());
        }
    }

    @Test

    void releaseSelectedAccountAndInvalidateOnLockAndEdit() throws Exception {
        try (var vault = new VaultService(directory.resolve("vault.db"))) {
            vault.create("senha mestra de teste".toCharArray());
            var credential = new Credential(
                    "Conta", "usuário", "senha fictícia", List.of("https://exemplo.com"), false, "notas");
            String id = vault.save(null, credential);
            assertThrows(IllegalArgumentException.class, () -> vault.requestFill("https://exemplo.com.falso.com", id, "document"));
            assertThrows(IllegalArgumentException.class, () -> vault.requestFill("https://exemplo.com", "outro-id", "document"));
            var fill = vault.requestFill("https://exemplo.com", id, "document");
            assertEquals("senha fictícia", fill.password());
            vault.confirmFill(fill.authorization(), "https://exemplo.com", id, "document");
            assertThrows(IllegalStateException.class, () -> vault.confirmFill(fill.authorization(), "https://exemplo.com", id, "document"));
            var blocked = vault.requestFill("https://exemplo.com", id, "document");
            vault.lock();
            assertThrows(IllegalStateException.class, () -> vault.confirmFill(blocked.authorization(), "https://exemplo.com", id, "document"));
            vault.unlock("senha mestra de teste".toCharArray());
            assertThrows(IllegalStateException.class, () -> vault.confirmFill(blocked.authorization(), "https://exemplo.com", id, "document"));
            var edited = vault.requestFill("https://exemplo.com", id, "document");
            vault.save(id, credential);
            assertThrows(IllegalStateException.class, () -> vault.confirmFill(edited.authorization(), "https://exemplo.com", id, "document"));
        }
    }
}
