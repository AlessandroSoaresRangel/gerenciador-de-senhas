package br.com.cofre.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class VaultCrypto {
    public static final int MEMORY = 65_536; // KiB: 64 MiB para o Argon2id.
    public static final int ITERATIONS = 3;
    public static final int PARALLELISM = 1;
    public static final int SALT_LENGTH_BYTES = 16;
    public static final int KEY_LENGTH_BYTES = 32;

    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int AUTHENTICATION_TAG_LENGTH_BITS = 128;
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private VaultCrypto() {
        // Classe utilitária: todas as operações são estáticas.
    }

    public record Sealed(byte[] nonce, byte[] ciphertext) {}

    public static byte[] random(int length) {
        byte[] randomBytes = new byte[length];
        SECURE_RANDOM.nextBytes(randomBytes);
        return randomBytes;
    }

    /** Deriva a chave que protege a chave do cofre, sem armazenar a senha mestra. */
    public static byte[] derive(
            char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        validateDerivationParameters(salt, memory, iterations, parallelism);

        Argon2Parameters parameters =
                new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withSalt(salt)
                        .withMemoryAsKB(memory)
                        .withIterations(iterations)
                        .withParallelism(parallelism)
                        .build();

        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);

            byte[] derivedKey = new byte[KEY_LENGTH_BYTES];
            generator.generateBytes(password, derivedKey);
            return derivedKey;
        } finally {
            parameters.clear();
        }
    }

    private static void validateDerivationParameters(
            byte[] salt, int memory, int iterations, int parallelism) {
        boolean supported =
                salt.length == SALT_LENGTH_BYTES
                        && memory == MEMORY
                        && iterations == ITERATIONS
                        && parallelism == PARALLELISM;

        if (!supported) {
            throw new IllegalArgumentException("Parâmetros do cofre não suportados.");
        }
    }

    /** Usa um novo nonce em cada gravação; o ciphertext inclui a tag de integridade. */
    public static Sealed encrypt(byte[] key, byte[] plaintext, String context)
            throws GeneralSecurityException {
        byte[] nonce = random(NONCE_LENGTH_BYTES);
        byte[] ciphertext = processCipher(Cipher.ENCRYPT_MODE, key, nonce, plaintext, context);
        return new Sealed(nonce, ciphertext);
    }

    /** Retorna os dados somente quando a tag de integridade e o contexto são válidos. */
    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, String context)
            throws GeneralSecurityException {
        return processCipher(Cipher.DECRYPT_MODE, key, nonce, ciphertext, context);
    }

    private static byte[] processCipher(
            int operationMode, byte[] key, byte[] nonce, byte[] input, String context)
            throws GeneralSecurityException {
        if (nonce.length != NONCE_LENGTH_BYTES) {
            throw new GeneralSecurityException("Nonce inválido.");
        }

        SecretKeySpec aesKey = new SecretKeySpec(key, KEY_ALGORITHM);
        GCMParameterSpec gcmParameters =
                new GCMParameterSpec(AUTHENTICATION_TAG_LENGTH_BITS, nonce);

        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(operationMode, aesKey, gcmParameters);

        // Autentica ID/versão sem criptografá-los, impedindo trocar dados entre registros.
        byte[] authenticatedContext = context.getBytes(StandardCharsets.UTF_8);
        cipher.updateAAD(authenticatedContext);

        return cipher.doFinal(input);
    }
}
