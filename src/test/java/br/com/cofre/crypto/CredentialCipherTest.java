package br.com.cofre.crypto;

import static org.junit.jupiter.api.Assertions.*;

import br.com.cofre.domain.Credential;
import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.EncryptedItem;

import org.junit.jupiter.api.Test;

import java.util.List;

class CredentialCipherTest {
    @Test
    void roundTripPreservesAllFieldsAndAuthenticatesRecordId() throws Exception {
        var cipher = new CredentialCipher();
        var entry =
                new VaultEntry(
                        "registro-teste",
                        new Credential(
                                "Conta",
                                "usuário",
                                "senha de teste",
                                List.of("https://exemplo.com"),
                                false,
                                "Notas"));
        byte[] key = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
        var encrypted = cipher.encrypt(entry, key);
        assertEquals(entry, cipher.decrypt(encrypted, key));
        var swapped =
                new EncryptedItem(
                        "outro-id", encrypted.version(), encrypted.nonce(), encrypted.ciphertext());
        assertThrows(Exception.class, () -> cipher.decrypt(swapped, key));
    }

    @Test
    void rejectUnsupportedRecordVersion() throws Exception {
        var cipher = new CredentialCipher();
        assertThrows(
                IllegalStateException.class,
                () ->
                        cipher.decrypt(
                                new EncryptedItem("id", 999, new byte[12], new byte[16]),
                                new byte[32]));
    }
}
