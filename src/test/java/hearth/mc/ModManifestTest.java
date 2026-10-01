package hearth.mc;

import hearth.core.GitSsh;
import hearth.core.WorldPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModManifestTest {
    @Test void clientModPermissionNeverAdmitsAnUnapprovedSharedMod() throws Exception {
        var policy = WorldPolicy.initial(GitSsh.generate(), "Owner", Map.of("worldmod", "shared:1"));
        ModManifest.verify(policy, Map.of("worldmod", "shared:1"));
        assertThrows(IOException.class, () -> ModManifest.verify(policy, Map.of("worldmod", "shared:2")));
        assertThrows(IOException.class, () -> ModManifest.verify(policy,
                Map.of("worldmod", "shared:1", "shader", "client:1")));
        var settings = policy.settings();
        var clientAllowed = policy.withSettings(new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(),
                settings.allowCreative(), settings.allowModeChanges(), true, settings.cheats(), settings.gameRules()));
        ModManifest.verify(clientAllowed, Map.of("worldmod", "shared:1", "shader", "client:1"));
        assertThrows(IOException.class, () -> ModManifest.verify(clientAllowed,
                Map.of("worldmod", "shared:1", "extra", "shared:1")));
        assertThrows(IOException.class, () -> ModManifest.verify(clientAllowed, Map.of("shader", "client:1")));
    }
}
