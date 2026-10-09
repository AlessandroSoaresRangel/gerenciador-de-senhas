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
