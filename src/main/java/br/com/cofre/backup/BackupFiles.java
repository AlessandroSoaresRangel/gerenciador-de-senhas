package br.com.cofre.backup;

import java.nio.file.Path;

/** Acesso a backups completos, com validação de destino e limites de tamanho. */
public interface BackupFiles {
    byte[] read(Path source) throws Exception;

    void validateDestination(Path destination);

    void write(Path destination, byte[] contents) throws Exception;
}
