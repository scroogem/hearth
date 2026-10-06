package hearth.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class InviteTest {
    @TempDir Path temp;

    @Test void aGuestReceivesOnlyAWorldKeyAndLocalSecretsAreProtected() throws Exception {
        var access = GitSsh.generate();
        var remote = new SharedWorld.Remote("ssh://git@ssh.github.com:443/owner/world.git", "git", null, access);
        var invite = new Invite("World", remote);
        assertEquals(access, Invite.decode(invite.encode()).remote().ssh());
        assertFalse(invite.toString().contains(access.privateKey()));
        assertThrows(IllegalArgumentException.class, () -> Invite.decode(new Invite("World",
                new SharedWorld.Remote(remote.url(), "git", "owner-token", access)).encode()));
        assertThrows(IllegalArgumentException.class, () -> Invite.decode(new Invite("World",
                new SharedWorld.Remote(temp.toString(), "", "")).encode()));
        assertThrows(IllegalArgumentException.class, () -> Invite.decode("hearth1:" + "x".repeat(20000)));
        Path inviteFile = temp.resolve("invite.txt");
        LocalSecrets.write(inviteFile, invite.encode());
        assertEquals(invite.encode(), Invite.readFile(inviteFile));
        Files.writeString(inviteFile, "\uFEFF" + invite.encode() + "\r\n");
        assertEquals(invite.encode(), Invite.readFile(inviteFile));
        Files.writeString(inviteFile, "broken invite");
        assertThrows(java.io.IOException.class, () -> Invite.readFile(inviteFile));
        Files.writeString(inviteFile, "x".repeat(65537));
        assertThrows(java.io.IOException.class, () -> Invite.readFile(inviteFile));
        assertThrows(java.io.IOException.class, () -> Invite.readFile(temp));
        assertThrows(java.io.IOException.class, () -> Invite.readFile(temp.resolve("missing")));
        Path file = temp.resolve("account.json");
        LocalSecrets.write(file, "first");
        LocalSecrets.write(file, "replacement");
        assertEquals("replacement", Files.readString(file));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        assertFalse(new GitHubLogin.Tokens("access-secret", "refresh-secret", 0, "owner").toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new GitHubLogin(""));
    }
}
