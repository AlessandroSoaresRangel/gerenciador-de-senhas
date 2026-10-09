package br.com.cofre.ipc;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Restringe a descoberta ao dono antes de gravar o token, falhando se não houver ACL/POSIX. */
public final class PrivateEndpoint {
    private PrivateEndpoint() {}

    public static void restrict(Path path) throws Exception {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            var entry =
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(Files.getOwner(path))
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) {
                entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            }
            acl.setAcl(List.of(entry.build()));
        } else if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString(
                            Files.isDirectory(path) ? "rwx------" : "rw-------"));
        } else {
            throw new java.io.IOException("Sistema de arquivos sem controle de acesso suportado.");
        }
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".cofre-local", "ipc", "endpoint.json");
    }
}
