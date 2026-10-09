package br.com.cofre.backup;

import java.nio.file.Files;
import java.nio.file.Path;

/** Cuida dos limites de arquivo e da publicação de backups completos, sem sobrescrever dados. */
public final class BackupFileStore implements BackupFiles {
    private static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;

    @Override
    public byte[] read(Path source) throws Exception {
        validateSize(Files.size(source));
        byte[] contents;
        try (var input = Files.newInputStream(source)) {
            // Limita a leitura mesmo se o arquivo crescer após a consulta ao tamanho.
            contents = input.readNBytes(MAX_BACKUP_BYTES + 1);
        }
        validateSize(contents.length);
        return contents;
    }

    @Override
    public void validateDestination(Path destination) {
        if (Files.isDirectory(destination)) {
            throw new IllegalArgumentException(
                    "Informe o caminho completo de um novo arquivo, por exemplo"
                        + " C:/codex-lab/backup.cofre. O destino informado é uma pasta.");
        }
    }

    @Override
    public void write(Path destination, byte[] contents) throws Exception {
        validateDestination(destination);
        validateSize(contents.length);
        Path absolute = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".cofre-backup-", ".tmp");
        try {
            Files.write(temporary, contents);
            Files.move(temporary, absolute);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateSize(long size) {
        if (size > MAX_BACKUP_BYTES) {
            throw new IllegalArgumentException("Backup excede o limite de 64 MiB.");
        }
    }
}
