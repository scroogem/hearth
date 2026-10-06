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
        var guestId = WorldPolicy.identity(guest.publicKey());
        assertFalse(invited.canUseCommands(guestId));
        var promoted = invited.withMember(guest.publicKey(), "Friend renamed", true);
        assertFalse(promoted.canUseCommands(guestId)); // The world command switch still applies to operators.
        var s = promoted.settings();
        var commands = promoted.withSettings(new WorldPolicy.Settings(s.gameMode(), s.difficulty(), s.allowCreative(), s.allowModeChanges(),
                s.allowClientMods(), true, s.gameRules()));
        assertTrue(commands.canUseCommands(guestId));
        assertTrue(commands.canUseCommands(commands.ownerUuid()));
        assertFalse(commands.withMember(guest.publicKey(), "Friend", false).canUseCommands(guestId));
        assertFalse(commands.ban(guest.publicKey(), true).canUseCommands(guestId));
        assertTrue(commands.ban(guest.publicKey(), true).ban(guest.publicKey(), false).member(guestId).operator());
        assertEquals(commands, WorldPolicy.verify(commands.sign(owner), first.trust()));
        assertThrows(java.io.IOException.class, () -> commands.sign(guest));
        assertThrows(IllegalArgumentException.class, () -> commands.withMember(owner.publicKey(), "Creator", false));
        String legacyPayload = invited.sign(owner).payload().replace(",\"operator\":false", "");
        var legacy = WorldPolicy.verify(new WorldPolicy.Signed(legacyPayload, WorldPolicy.signBytes(owner,
                legacyPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8))), first.trust());
        assertFalse(legacy.member(guestId).operator());
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
