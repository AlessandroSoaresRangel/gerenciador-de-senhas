package br.com.cofre.crypto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

class VaultCryptoTest {
    private byte[] derive(String password, byte[] salt) {
        return VaultCrypto.derive(
                password.toCharArray(),
                salt,
                VaultCrypto.MEMORY,
                VaultCrypto.ITERATIONS,
                VaultCrypto.PARALLELISM);
    }

    @Test
    void keyDerivationIsRepeatableAndDependsOnPasswordAndSalt() {
        byte[] salt = VaultCrypto.random(VaultCrypto.SALT_LENGTH_BYTES);
        byte[] key = derive("senha mestra de teste", salt);
        assertEquals(VaultCrypto.KEY_LENGTH_BYTES, key.length);
        assertArrayEquals(key, derive("senha mestra de teste", salt));
        assertFalse(Arrays.equals(key, derive("outra senha de teste", salt)));
        byte[] differentSalt = salt.clone();
        differentSalt[0] ^= 1;
        assertFalse(Arrays.equals(key, derive("senha mestra de teste", differentSalt)));
    }

    @Test
    void rejectUnsupportedDerivationParameters() {
        byte[] salt = new byte[VaultCrypto.SALT_LENGTH_BYTES];
        assertThrows(IllegalArgumentException.class, () -> derive("teste", new byte[8]));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VaultCrypto.derive(
                                "teste".toCharArray(),
                                salt,
                                1,
                                VaultCrypto.ITERATIONS,
                                VaultCrypto.PARALLELISM));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VaultCrypto.derive(
                                "teste".toCharArray(),
                                salt,
                                VaultCrypto.MEMORY,
                                1,
                                VaultCrypto.PARALLELISM));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VaultCrypto.derive(
                                "teste".toCharArray(),
                                salt,
                                VaultCrypto.MEMORY,
                                VaultCrypto.ITERATIONS,
                                2));
    }

    @Test
    void roundTripUnicodeAndEmptyContent() throws Exception {
        byte[] key = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
        for (String content : new String[] {"senha com acentuação 🔐", ""}) {
            byte[] plaintext = content.getBytes(StandardCharsets.UTF_8);
            var encrypted = VaultCrypto.encrypt(key, plaintext, "item:1:teste");
            assertArrayEquals(
                    plaintext,
                    VaultCrypto.decrypt(
                            key, encrypted.nonce(), encrypted.ciphertext(), "item:1:teste"));
        }
    }

    @Test
    void repeatedEncryptionUsesDifferentNonces() throws Exception {
        byte[] key = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
        byte[] content = "conteúdo de teste".getBytes(StandardCharsets.UTF_8);
        var first = VaultCrypto.encrypt(key, content, "teste");
        var second = VaultCrypto.encrypt(key, content, "teste");
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(Arrays.equals(first.ciphertext(), second.ciphertext()));
    }

    @Test
    void rejectWrongKeyContextAndTamperedContent() throws Exception {
        byte[] key = VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES);
        var encrypted = VaultCrypto.encrypt(key, new byte[] {1, 2, 3}, "item:1:original");
        assertThrows(
                GeneralSecurityException.class,
                () ->
                        VaultCrypto.decrypt(
                                VaultCrypto.random(VaultCrypto.KEY_LENGTH_BYTES),
                                encrypted.nonce(),
                                encrypted.ciphertext(),
                                "item:1:original"));
        assertThrows(
                GeneralSecurityException.class,
                () ->
                        VaultCrypto.decrypt(
                                key, encrypted.nonce(), encrypted.ciphertext(), "item:1:outro"));
        byte[] tampered = encrypted.ciphertext().clone();
        tampered[0] ^= 1;
        assertThrows(
                GeneralSecurityException.class,
                () -> VaultCrypto.decrypt(key, encrypted.nonce(), tampered, "item:1:original"));
        byte[] changedNonce = encrypted.nonce().clone();
        changedNonce[0] ^= 1;
        assertThrows(
                GeneralSecurityException.class,
                () ->
                        VaultCrypto.decrypt(
                                key, changedNonce, encrypted.ciphertext(), "item:1:original"));
        assertThrows(
                GeneralSecurityException.class,
                () ->
                        VaultCrypto.decrypt(
                                key, new byte[8], encrypted.ciphertext(), "item:1:original"));
    }
}
