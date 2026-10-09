package br.com.cofre.backup;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.crypto.CredentialCipher;
import br.com.cofre.crypto.VaultCrypto;
import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;

class VaultBackupServiceTest {
    @TempDir Path directory;

    @Test
    void injectedFileFailureClearsPasswordAndPreservesFailure() {
        var readFailure = new IOException("Falha fictícia de leitura.");
        BackupFiles unavailableFiles = new BackupFiles() {
            @Override
            public byte[] read(Path source) throws IOException {
                throw readFailure;
            }

            @Override
            public void validateDestination(Path destination) {}

            @Override
            public void write(Path destination, byte[] contents) {
                fail("Uma leitura não deve gravar arquivos.");
            }
        };
        var backups = new VaultBackupService(unavailableFiles, new CredentialCipher());
        char[] password = "senha fictícia de backup".toCharArray();

        assertSame(readFailure,
                assertThrows(IOException.class, () -> backups.read(directory.resolve("absent.cofre"), password)));
        assertArrayEquals(new char[password.length], password);
    }

    @Test
    void readVersionOneFormatWithoutDependingOnCurrentExporter() throws Exception {
        byte[] salt = VaultCrypto.random(VaultCrypto.SALT_LENGTH_BYTES);
        byte[] key = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
        char[] password = "senha de teste do backup".toCharArray();
        byte[] wrappingKey =
                VaultCrypto.derive(
                        password,
                        salt,
                        VaultCrypto.MEMORY_KIBIBYTES,
                        VaultCrypto.ITERATIONS,
                        VaultCrypto.PARALLELISM);
        var wrapped = VaultCrypto.encrypt(wrappingKey, key, "vault-key:1");
        var entry =
                new VaultEntry(
                        "id-preservado",
                        new Credential(
                                "Conta", "usuário", "senha fictícia", List.of(), false, "Notas"));
        var mapper = new ObjectMapper();
        var payload =
                VaultCrypto.encrypt(key, mapper.writeValueAsBytes(List.of(entry)), "backup:1");
        // Constrói explicitamente os campos da versão original para detectar mudanças no formato.
        Map<String, Object> header =
                Map.of(
                        "version",
                        1,
                        "salt",
                        salt,
                        "memory",
                        65536,
                        "iterations",
                        3,
                        "parallelism",
                        1,
                        "nonce",
                        wrapped.nonce(),
                        "wrappedKey",
                        wrapped.ciphertext());
        Path source = directory.resolve("legacy.cofre");
        mapper.writeValue(
                source.toFile(),
                Map.of(
                        "format",
                        "cofre-local",
                        "version",
                        1,
                        "header",
                        header,
                        "nonce",
                        payload.nonce(),
                        "ciphertext",
                        payload.ciphertext()));
        var snapshot = new VaultBackupService().read(source, password);
        assertArrayEquals(new char[password.length], password);
        assertEquals(entry, new CredentialCipher().decrypt(snapshot.items().getFirst(), key));
    }
}
