package br.com.cofre.crypto;

import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.EncryptedItem;

/** Fronteira de criptografia dos registros; implementações devem autenticar ID e versão. */
public interface CredentialEncryption {
    EncryptedItem encrypt(VaultEntry entry, byte[] key) throws Exception;

    VaultEntry decrypt(EncryptedItem item, byte[] key) throws Exception;
}
