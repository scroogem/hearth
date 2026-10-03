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
	private boolean survivedGuestExit;
	private volatile long rulesChangedAt;

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
				HearthClient.play(entry, new SharedWorldsScreen(new TitleScreen()));
			}
			case OPENING -> {
				if (mc.level != null && mc.player != null && mc.getSingleplayerServer() == null) {
					stage = Stage.JOINED;
					signal("PEER_JOIN_VERIFIED");
					return;
				}
				if (mc.level == null || HearthClient.session == null || HearthClient.session.joinTarget().get() == null) return;
				if (Boolean.getBoolean("hearth.peer.expectSteam") && !HearthClient.session.joinTarget().get().endsWith(".steam")) return;
				stage = Stage.HOSTING;
				signal("HOST_READY");
			}
			case HOSTING -> {
				var server = mc.getSingleplayerServer();
				if (server == null || server.isStopped()) throw new IllegalStateException("Participant host stopped unexpectedly");
				int players = server.getPlayerList().getPlayerCount();
				var local = new net.minecraft.server.players.NameAndId(HearthClient.session.playerUuid(), mc.getUser().getName());
				if (!server.isSingleplayerOwner(local)) throw new IllegalStateException("Local host lost lifecycle ownership");
				var policy = HearthClient.session.policy().get();
				if (Files.exists(signals.resolve("approve-pack"))) {
					Files.delete(signals.resolve("approve-pack"));
					var active = HearthClient.session;
					HearthClient.async(() -> {
						try (var store = new hearth.core.PolicyStore(HearthClient.gitDir(entry).resolve("policy.git"),
								entry.remote().trust().policyUrl(), entry.remote().trust().policyAccess())) {
							var latest = store.load(entry.remote().trust());
							var settings = new hearth.core.WorldPolicy.Settings("survival", "normal", false, false, false, false,
									java.util.Map.of("keep_inventory", "false"));
							var approved = new hearth.core.WorldPolicy(latest.worldId(), latest.ownerKey(), latest.revision() + 1,
									settings, latest.members(), latest.bannedIps(), ModManifest.current());
							store.publish(approved, entry.remote().ssh()); active.policy().set(approved);
							HearthClient.LOG.info("[peer-test] OWNER_RULES_AND_PACK_APPROVED");
						}
						return null;
					});
				}
				if (policy != null && !policy.ownerUuid().equals(local.id())) {
					if (server.isSingleplayerOwner(new net.minecraft.server.players.NameAndId(policy.ownerUuid(), "Creator")))
						throw new IllegalStateException("Creator must not control another host's lifecycle");
					var expected = policy.settings().cheats() || policy.settings().allowModeChanges()
							? net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER
							: net.minecraft.server.permissions.LevelBasedPermissionSet.ALL;
					if (!server.getProfilePermissions(local).equals(expected))
						throw new IllegalStateException("Participant permissions differ from signed world rules");
				}
				if (players == 1 && guestJoinedAt > 0 && !survivedGuestExit) {
					survivedGuestExit = true;
					HearthClient.LOG.info("[peer-test] HOST_SURVIVED_GUEST_EXIT");
				}
				if (Files.exists(signals.resolve("write-marker"))) {
					Files.delete(signals.resolve("write-marker"));
					server.execute(() -> {
						server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(0, 65, 0), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
						HearthClient.LOG.info("[peer-test] WORLD_MARKER_WRITTEN");
					});
				}
				if (Files.exists(signals.resolve("check-rules"))) {
					Files.delete(signals.resolve("check-rules"));
					server.execute(() -> {
						server.setDefaultGameType(net.minecraft.world.level.GameType.CREATIVE);
						server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
						if (server.getDefaultGameType() != net.minecraft.world.level.GameType.byName(policy.settings().gameMode())
								|| server.getWorldData().getDifficulty() != net.minecraft.world.Difficulty.byName(policy.settings().difficulty()))
							throw new AssertionError("Host changed signed world settings");
						var guest = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(policy.ownerUuid())).findFirst().orElseThrow();
						guest.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
						if (guest.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.CREATIVE)
							throw new AssertionError("Guest enabled forbidden creative mode");
						server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, true, server);
						rulesChangedAt = server.getTickCount();
					});
				}
				if (rulesChangedAt > 0 && server.getTickCount() - rulesChangedAt > 25) {
					rulesChangedAt = 0;
					if (server.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY))
						throw new AssertionError("Signed game rule was not restored");
					HearthClient.LOG.info("[peer-test] SIGNED_GAME_RULES_AND_CREATIVE_GUARDS_VERIFIED");
				}
				if (Files.exists(signals.resolve("verify-marker"))) {
					Files.delete(signals.resolve("verify-marker"));
					server.execute(() -> {
						if (!server.overworld().getBlockState(new net.minecraft.core.BlockPos(0, 65, 0)).is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK))
							throw new IllegalStateException("Next host did not receive the friend's world changes");
						HearthClient.LOG.info("[peer-test] FRIEND_WORLD_CHANGES_VERIFIED");
					});
				}
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
					if (failure != null || status.joinTarget() == null || status.lock().holder().id().equals(HearthClient.me(entry).id())) return;
					stage = Stage.JOINING;
					HearthClient.join(entry, status.joinTarget(), new SharedWorldsScreen(new TitleScreen()));
				});
			}
			case JOINING -> {
				if (mc.level == null || mc.player == null || mc.getSingleplayerServer() != null) return;
				stage = Stage.JOINED;
				signal("PEER_JOIN_VERIFIED");
			}
			case JOINED -> {
				if (mc.level == null && Files.exists(signals.resolve("host-now"))) {
					Files.delete(signals.resolve("host-now"));
					stage = Stage.OPENING;
					HearthClient.play(entry, new SharedWorldsScreen(new TitleScreen()));
				}
			}
			case FAILED -> { }
		}
	}

	private void signal(String value) throws IOException {
		Files.writeString(signals.resolve("stage.txt"), value);
		HearthClient.LOG.info("[peer-test] {}", value);
	}
}
