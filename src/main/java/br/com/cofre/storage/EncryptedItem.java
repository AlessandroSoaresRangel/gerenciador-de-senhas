package br.com.cofre.storage;

/** Conteúdo criptografado de um registro, independente do mecanismo de persistência. */
public record EncryptedItem(String id, int version, byte[] nonce, byte[] ciphertext) {}
