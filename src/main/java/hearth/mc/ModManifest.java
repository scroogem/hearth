package hearth.mc;

import hearth.core.WorldPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModEnvironment;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Checks declared mod versions. A manifest cannot attest to an unmodified participant computer. */
final class ModManifest {
    static Map<String, String> current() {
        Map<String, String> mods = new TreeMap<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            var metadata = mod.getMetadata();
            String id = metadata.getId();
            if (id.equals("java") || id.equals("fabricloader") || mod.getContainingMod().isPresent() || id.startsWith("e4steam") || id.startsWith("fabric-") && !id.equals("fabric-api")) continue;
            mods.put(id, (metadata.getEnvironment() == ModEnvironment.CLIENT ? "client:" : "shared:") + metadata.getVersion().getFriendlyString());
        }
        return mods;
    }

    static void verify(WorldPolicy policy, Map<String, String> actual) throws IOException {
        if (policy.mods().isEmpty()) return; // Existing prototype worlds had no manifest.
        for (var required : policy.mods().entrySet()) {
            if (policy.settings().allowClientMods() && required.getValue().startsWith("client:")) continue;
            if (!required.getValue().equals(actual.get(required.getKey()))) throw new IOException("World mod required: " + required.getKey() + " " + required.getValue());
        }
        for (var mod : actual.entrySet()) {
            if (!policy.mods().containsKey(mod.getKey()) && !(policy.settings().allowClientMods() && mod.getValue().startsWith("client:")))
                throw new IOException("The creator has not approved this mod: " + mod.getKey());
        }
    }
}
