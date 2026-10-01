package hearth.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorldPolicyTest {
    @TempDir Path temp;

    @Test void onlyTheCreatorChangesRulesAndWorldRollbackCannotRestoreOldPermissions() throws Exception {
        var owner = GitSsh.generate();
        var guest = GitSsh.generate();
        var first = WorldPolicy.initial(owner, "Creator", Map.of("hearth", "0.1.1"));
        var invited = first.addMember(guest, "Friend");
        assertEquals("2001:db8:0:0:0:0:0:1", invited.banIp("2001:db8::1", true).bannedIps().getFirst());
        assertThrows(IllegalArgumentException.class, () -> invited.banIp("example.com", true));
        var invalid = new WorldPolicy.Settings("survival", "normal", false, false, false, false, Map.of("minecraft:random_tick_speed", "9999999999"));
        assertThrows(java.io.IOException.class, () -> invited.withSettings(invalid).sign(owner));
        assertEquals(invited, WorldPolicy.verify(invited.sign(owner), first.trust()));
        assertTrue(invited.canHost(WorldPolicy.identity(guest.publicKey())));
        assertThrows(java.io.IOException.class, () -> invited.sign(guest));
        var signed = invited.sign(owner);
		byte[] nonce = new byte[32];
		new java.security.SecureRandom().nextBytes(nonce);
		byte[] challenge = LoginProof.guest(first.worldId(), nonce, owner.publicKey(), guest.publicKey());
		String proof = WorldPolicy.signBytes(guest, challenge);
		assertTrue(WorldPolicy.verifyBytes(guest.publicKey(), challenge, proof));
		nonce[0] ^= 1;
		assertFalse(WorldPolicy.verifyBytes(guest.publicKey(), LoginProof.guest(first.worldId(), nonce, owner.publicKey(), guest.publicKey()), proof));
		assertFalse(WorldPolicy.verifyBytes(owner.publicKey(), challenge, proof));
        byte[] bound = LoginProof.guest(first.worldId(), nonce, owner.publicKey(), guest.publicKey(), "{\"hearth\":\"shared:0.1.1\"}");
        String boundProof = WorldPolicy.signBytes(guest, bound);
        assertTrue(WorldPolicy.verifyBytes(guest.publicKey(), bound, boundProof));
        assertFalse(WorldPolicy.verifyBytes(guest.publicKey(), LoginProof.guest(first.worldId(), nonce,
                owner.publicKey(), guest.publicKey(), "{}"), boundProof));
        assertThrows(java.io.IOException.class, () -> WorldPolicy.verify(new WorldPolicy.Signed(
                signed.payload().replace("survival", "creative"), signed.signature()), first.trust()));
        assertThrows(java.io.IOException.class, () -> WorldPolicy.verify(signed,
                new WorldPolicy.Trust(java.util.UUID.randomUUID().toString(), owner.publicKey())));

        Path origin = temp.resolve("policy.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(origin.toFile()).call().close();
        try (var writer = new PolicyStore(temp.resolve("owner.git"), origin.toString(), null);
             var reader = new PolicyStore(temp.resolve("guest.git"), origin.toString(), null)) {
            writer.publish(first, owner);
            writer.publish(first, owner); // Retry an upload whose response was lost.
            writer.publish(invited, owner);
            assertTrue(reader.load(first.trust()).canHost(WorldPolicy.identity(guest.publicKey())));
            var banned = invited.ban(guest.publicKey(), true);
            writer.publish(banned, owner);
            assertFalse(reader.load(first.trust()).canHost(WorldPolicy.identity(guest.publicKey())));
            assertThrows(java.io.IOException.class, () -> writer.publish(invited, owner));
            writer.publish(banned.ban(guest.publicKey(), false), owner);
            assertTrue(reader.load(first.trust()).canHost(WorldPolicy.identity(guest.publicKey())));
        }
    }
}
