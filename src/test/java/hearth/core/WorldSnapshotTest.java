package hearth.core;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotTest {
    @Test void alteredBytesAndUnknownAuthorsAreRejected() throws Exception {
        var owner = GitSsh.generate();
        var guest = GitSsh.generate();
        var outsider = GitSsh.generate();
        var policy = WorldPolicy.initial(owner, "Owner", Map.of()).addMember(guest, "Friend");
        String hash = "a".repeat(64);
        var save = WorldSnapshot.sign(policy, guest, hash, 12345);
        save.verify(policy, hash);
        assertThrows(java.io.IOException.class, () -> save.verify(policy, "b".repeat(64)));
        assertThrows(java.io.IOException.class, () -> WorldSnapshot.sign(policy, outsider, hash, 12345));
        var changed = new WorldSnapshot(save.worldId(), owner.publicKey(), hash, save.atMillis(), save.signature());
        assertThrows(java.io.IOException.class, () -> changed.verify(policy, hash));
        // Banning someone does not destroy the ability to recover a save they previously published.
        save.verify(policy.ban(guest.publicKey(), true), hash);
    }
}
