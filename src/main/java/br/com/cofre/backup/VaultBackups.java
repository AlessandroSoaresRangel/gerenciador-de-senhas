package br.com.cofre.backup;

import br.com.cofre.domain.VaultEntry;
import br.com.cofre.storage.VaultHeader;
import br.com.cofre.storage.VaultSnapshot;

import java.nio.file.Path;
import java.util.List;

/** Exporta dados autenticados e valida restaurações sem modificar a sessão ou a persistência. */
public interface VaultBackups {
    void export(Path destination, VaultHeader header, List<VaultEntry> entries, byte[] key)
            throws Exception;

    /** Consome e apaga a senha recebida, inclusive em caso de falha. */
    VaultSnapshot read(Path source, char[] password) throws Exception;
}
