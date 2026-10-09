package br.com.cofre.storage;

import java.util.List;

/** Conjunto completo de dados criptografados usado na substituição transacional do cofre. */
public record VaultSnapshot(VaultHeader header, List<EncryptedItem> items) {}
