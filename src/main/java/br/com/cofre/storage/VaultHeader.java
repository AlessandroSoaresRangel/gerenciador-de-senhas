package br.com.cofre.storage;

/** Metadados persistidos para recuperar a chave; não contém a senha mestra. */
public record VaultHeader(
        int version,
        byte[] salt,
        int memory,
        int iterations,
        int parallelism,
        byte[] nonce,
        byte[] wrappedKey) {}
