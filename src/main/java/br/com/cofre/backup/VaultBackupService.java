package br.com.cofre.backup;

import br.com.cofre.crypto.CredentialCipher;
import br.com.cofre.crypto.VaultCrypto;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.EncryptedItem;
import br.com.cofre.storage.VaultHeader;
import br.com.cofre.storage.VaultSnapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Responsável pelo formato do backup e sua integridade; não conhece SQLite nem a sessão da UI. */
public final class VaultBackupService {
    private static final int BACKUP_VERSION = 1;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BackupFileStore files = new BackupFileStore();
    private final CredentialCipher credentialCipher = new CredentialCipher();

    private record BackupDocument(
            String format, int version, VaultHeader header, byte[] nonce, byte[] ciphertext) {}

    /** Autentica a lista inteira para detectar inclusive a remoção de credenciais no arquivo. */
    public void export(Path destination, VaultHeader header, List<VaultEntry> entries, byte[] key)
            throws Exception {
        files.validateDestination(destination);
        byte[] plaintext = objectMapper.writeValueAsBytes(entries);
        try {
            var encrypted = VaultCrypto.encrypt(key, plaintext, "backup:1");
            var document =
                    new BackupDocument(
                            "cofre-local",
                            BACKUP_VERSION,
                            header,
                            encrypted.nonce(),
                            encrypted.ciphertext());
            files.write(destination, objectMapper.writeValueAsBytes(document));
        } finally {
            wipe(plaintext);
        }
    }

    /**
     * Valida o backup antes de devolver uma fotografia pronta para persistência. A chave recuperada
     * é usada apenas nesta operação e nunca é entregue à sessão. O array da senha é apagado no fim.
     */
    public VaultSnapshot read(Path source, char[] password) throws Exception {
        byte[] wrappingKey = null;
        byte[] restoredKey = null;
        byte[] plaintext = null;
        try {
            BackupDocument document =
                    objectMapper.readValue(files.read(source), BackupDocument.class);
            validateFormat(document);
            VaultHeader header = document.header();
            wrappingKey =
                    VaultCrypto.derive(
                            password,
                            header.salt(),
                            header.memory(),
                            header.iterations(),
                            header.parallelism());
            restoredKey =
                    VaultCrypto.decrypt(
                            wrappingKey, header.nonce(), header.wrappedKey(), "vault-key:1");
            if (restoredKey.length != VaultCrypto.KEY_LENGTH_BYTES)
                throw new IllegalArgumentException("Chave do backup inválida.");
            plaintext =
                    VaultCrypto.decrypt(
                            restoredKey, document.nonce(), document.ciphertext(), "backup:1");
            List<VaultEntry> entries =
                    objectMapper.readValue(plaintext, new TypeReference<List<VaultEntry>>() {});
            List<EncryptedItem> encryptedItems = new ArrayList<>();
            for (VaultEntry entry : entries)
                encryptedItems.add(credentialCipher.encrypt(entry, restoredKey));
            return new VaultSnapshot(header, List.copyOf(encryptedItems));
        } finally {
            Arrays.fill(password, '\0');
            wipe(wrappingKey);
            wipe(restoredKey);
            wipe(plaintext);
        }
    }

    private void validateFormat(BackupDocument document) {
        if (!"cofre-local".equals(document.format())
                || document.version() != BACKUP_VERSION
                || document.header() == null
                || document.header().version() != 1) {
            throw new IllegalArgumentException("Formato de backup não suportado.");
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
