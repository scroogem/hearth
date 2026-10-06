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
            // Mark the world protocol as shared so earlier Hearth versions also enforce its version.
            mods.put(id, (!id.equals("hearth") && metadata.getEnvironment() == ModEnvironment.CLIENT ? "client:" : "shared:") + metadata.getVersion().getFriendlyString());
        }
        return mods;
    }

    static void verify(WorldPolicy policy, Map<String, String> actual) throws IOException {
        if (policy.mods().isEmpty()) return; // Existing prototype worlds had no manifest.
        for (var required : policy.mods().entrySet()) {
            if (!required.getKey().equals("hearth") && policy.settings().allowClientMods() && required.getValue().startsWith("client:")) continue;
            if (!required.getValue().equals(actual.get(required.getKey()))) throw new IOException(net.minecraft.network.chat.Component.translatable(
                    required.getKey().equals("hearth") ? "hearth.hearth_version_required" : "hearth.mod_required", required.getKey(),
                    required.getValue().replaceFirst("^(client|shared):", "")).getString());
        }
        for (var mod : actual.entrySet()) {
            if (!policy.mods().containsKey(mod.getKey()) && !(policy.settings().allowClientMods() && mod.getValue().startsWith("client:")))
                throw new IOException(net.minecraft.network.chat.Component.translatable("hearth.mod_not_approved", mod.getKey()).getString());
        }
    }
}
