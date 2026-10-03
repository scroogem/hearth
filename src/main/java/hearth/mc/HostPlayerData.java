package hearth.mc;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Migrates an existing singleplayer profile once; existing Steam progress always takes priority. */
final class HostPlayerData {
	private HostPlayerData() {}

	static void prepare(Path world, UUID minecraftUuid, UUID steamUuid) throws IOException {
		if (minecraftUuid.equals(steamUuid)) return;
		Path data = world.resolve("players/data");
		Path oldPlayer = data.resolve(minecraftUuid + ".dat");
		Path steamPlayer = data.resolve(steamUuid + ".dat");
		if (Files.exists(steamPlayer)) return;
		if (Files.exists(oldPlayer)) {
			var tag = NbtIo.readCompressed(oldPlayer, NbtAccounter.create(16 * 1024 * 1024));
			tag.store("UUID", UUIDUtil.CODEC, steamUuid);
			NbtIo.writeCompressed(tag, steamPlayer);
		}
		for (String kind : new String[]{"advancements", "stats"}) {
			Path from = world.resolve("players/" + kind + "/" + minecraftUuid + ".json");
			Path to = from.resolveSibling(steamUuid + ".json");
			if (Files.exists(from) && !Files.exists(to)) Files.copy(from, to);
		}
	}
}
