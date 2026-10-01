package hearth.core;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitSshTest {
    @Test
    void onlyMatchingKeysAndThePinnedGitHubEndpointAreAccepted() throws Exception {
        var first = GitSsh.generate();
        var second = GitSsh.generate();
        assertTrue(first.authorizedKey().startsWith("ssh-rsa "));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> new GitSsh.Access(first.privateKey(), second.publicKey(), 0).keyPair());
        assertFalse(first.toString().contains(first.privateKey()));
        assertEquals("owner/world", GitSsh.repositoryPath("ssh://git@ssh.github.com:443/owner/world.git"));
        assertThrows(IllegalArgumentException.class,
                () -> GitSsh.repositoryPath("ssh://git@attacker.example:443/owner/world.git"));
        assertThrows(IllegalArgumentException.class,
                () -> GitSsh.repositoryPath("ssh://git@ssh.github.com:443/owner/../world.git"));

        String publishedGitHubKey = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=";
        var server = PublicKeyEntry.parsePublicKeyEntry(publishedGitHubKey).resolvePublicKey(null, Map.of(), null);
        assertTrue(GitSsh.trustedHost("[ssh.github.com]:443", server));
        assertFalse(GitSsh.trustedHost("[attacker.example]:443", server));
        assertFalse(GitSsh.trustedHost("[ssh.github.com]:443", first.keyPair().getPublic()));
    }
}
