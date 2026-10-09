package br.com.cofre.crypto;

import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.EncryptedItem;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;

/** Converte credenciais em registros autenticados, preservando o formato existente. */
public final class CredentialCipher implements CredentialEncryption {
    private static final int ITEM_FORMAT_VERSION = 1;
    private static final String ITEM_AUTHENTICATION_CONTEXT = "item:" + ITEM_FORMAT_VERSION + ":";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public EncryptedItem encrypt(VaultEntry entry, byte[] key) throws Exception {
        if (entry.id() == null || entry.credential() == null) {
            throw new IllegalArgumentException("Registro de backup inválido.");
        }
        byte[] plaintext = objectMapper.writeValueAsBytes(entry.credential());
        try {
            var encrypted = VaultCrypto.encrypt(key, plaintext, ITEM_AUTHENTICATION_CONTEXT + entry.id());
            return new EncryptedItem(entry.id(), ITEM_FORMAT_VERSION, encrypted.nonce(), encrypted.ciphertext());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public VaultEntry decrypt(EncryptedItem item, byte[] key) throws Exception {
        if (item.version() != ITEM_FORMAT_VERSION) {
            throw new IllegalStateException("Registro não suportado.");
        }
        byte[] plaintext =
                VaultCrypto.decrypt(key, item.nonce(), item.ciphertext(), ITEM_AUTHENTICATION_CONTEXT + item.id());
        try {
            return new VaultEntry(item.id(), objectMapper.readValue(plaintext, Credential.class));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
