package hearth.mc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Live peer check in a normal client, without the gametest framework's thread barriers. */
public final class PeerSessionDriver implements ClientModInitializer {
	private enum Stage { STARTING, OPENING, HOSTING, RELEASING, WAITING_FOR_PEER, JOINING, JOINED, FAILED }
	private Stage stage = Stage.STARTING;
	private HearthClient.Entry entry;
	private Path signals;
	private long ticks;
	private long guestJoinedAt;
	private boolean checkingPeer;
	private boolean guestVerified;

	@Override
	public void onInitializeClient() {
		String remote = System.getProperty("hearth.peer.remote");
		if (remote == null) return;
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				tick(mc, remote);
			} catch (Exception e) {
				stage = Stage.FAILED;
				HearthClient.LOG.error("[peer-test] Live check failed", e);
			}
		});
	}

	private void tick(Minecraft mc, String remote) throws IOException {
		ticks++;
		switch (stage) {
			case STARTING -> {
				if (!(mc.gui.screen() instanceof TitleScreen)) return;
				entry = HearthClient.entries().stream().filter(e -> e.remote().url().equals(remote)).findFirst().orElseThrow();
				signals = HearthClient.gameDir().resolve("hearth-peer");
				Files.createDirectories(signals);
				Files.deleteIfExists(signals.resolve("release-host"));
				stage = Stage.OPENING;
				HearthClient.host(entry, false, new SharedWorldsScreen(new TitleScreen()));
			}
			case OPENING -> {
				if (mc.level == null || HearthClient.session == null || HearthClient.session.joinTarget().get() == null) return;
				stage = Stage.HOSTING;
				signal("HOST_READY");
			}
			case HOSTING -> {
				var server = mc.getSingleplayerServer();
				if (server == null) return;
				int players = server.getPlayerList().getPlayerCount();
				if (players >= 2 && guestJoinedAt == 0) {
					guestJoinedAt = System.currentTimeMillis();
					signal("GUEST_CONNECTED");
				}
				if (players >= 2 && !guestVerified && guestJoinedAt > 0 && System.currentTimeMillis() - guestJoinedAt >= 60_000) {
					guestVerified = true;
					signal("GUEST_STABLE");
				}
				if (ticks % 200 == 0) Files.writeString(signals.resolve("health.txt"),
						System.currentTimeMillis() + " players=" + players + " serverTicks=" + server.getTickCount());
				if (Files.exists(signals.resolve("release-host"))) {
					stage = Stage.RELEASING;
					signal("RELEASING_HOST");
					mc.level.disconnect(Component.translatable("menu.savingLevel"));
					mc.disconnectWithSavingScreen();
				}
			}
			case RELEASING -> {
				if (HearthClient.session != null || !(mc.gui.screen() instanceof SharedWorldsScreen)) return;
				stage = Stage.WAITING_FOR_PEER;
				signal("WAITING_FOR_PEER_HOST");
			}
			case WAITING_FOR_PEER -> {
				if (checkingPeer || ticks % 100 != 0) return;
				checkingPeer = true;
				HearthClient.async(() -> {
					try (var world = HearthClient.open(entry)) {
						return world.status();
					}
				}).whenComplete((status, failure) -> {
					checkingPeer = false;
					if (failure != null || status.joinTarget() == null || status.lock().holder().id().equals(HearthClient.me().id())) return;
					stage = Stage.JOINING;
					HearthClient.join(entry, status.joinTarget(), new SharedWorldsScreen(new TitleScreen()));
				});
			}
			case JOINING -> {
				if (mc.level == null || mc.player == null || mc.getSingleplayerServer() != null) return;
				stage = Stage.JOINED;
				signal("PEER_JOIN_VERIFIED");
			}
			case JOINED, FAILED -> { }
		}
	}

	private void signal(String value) throws IOException {
		Files.writeString(signals.resolve("stage.txt"), value);
		HearthClient.LOG.info("[peer-test] {}", value);
	}
}
