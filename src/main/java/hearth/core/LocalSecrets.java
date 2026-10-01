package hearth.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;
import java.util.Set;

/** Credentials are local files readable only by the current OS account; replacements are atomic. */
public final class LocalSecrets {
    private LocalSecrets() {}

    public static void write(Path file, String text) throws IOException {
        write(file, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".hearth-secret-", ".tmp");
        try {
            restrict(temporary);
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void restrict(Path file) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) throw new IOException("This file system cannot protect Hearth credentials");
        var owner = Files.getOwner(file);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(Set.of(AclEntryPermission.values())).build()));
    }
}
