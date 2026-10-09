package br.com.cofre.storage;

/** Metadados persistidos para recuperar a chave; não contém a senha mestra. */
public record VaultHeader(
        int version,
        byte[] salt,
        int memory,
        int iterations,
        int parallelism,
        byte[] nonce,
        byte[] wrappedKey) {
    public static final int FORMAT_VERSION = 1;
    public static final String KEY_AUTHENTICATION_CONTEXT = "vault-key:" + FORMAT_VERSION;
}
