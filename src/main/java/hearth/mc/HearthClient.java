package hearth.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import hearth.core.Invite;
import hearth.core.SharedWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Entry point and state: the list of shared worlds on this PC and the world we currently host. */
public final class HearthClient implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("hearth");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One shared world on this PC. The token stays on this PC, like a launcher keeps its login. */
	public record Entry(String name, String folder, SharedWorld.Remote remote) {}

	record Session(Entry entry, SharedWorld world, SharedWorld.Lock lock, ScheduledFuture<?> heartbeat) {}

	/** Every git operation runs here, one at a time, off the render thread. */
	static final ScheduledExecutorService GIT = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "hearth-git"));
	static volatile Session session;

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (screen instanceof SelectWorldScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("hearth.shared_worlds"),
						b -> mc.gui.setScreen(new SharedWorldsScreen(screen))).bounds(width - 130, 6, 124, 20).build());
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
			Session s = session;
			boolean ours = s != null && s.world().worldDir().toAbsolutePath().normalize().equals(dir);
			if (!ours && Files.exists(dir.resolve(".hearth/meta.json"))) {
				// Opened from the vanilla list: that would bypass the lock and fork the world.
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.disconnectFromWorld(Component.translatable("hearth.open_from_hearth")));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Session s = session;
			if (s != null) saveAndRelease(s);
		});
		// Closing the game while hosting: wait for the upload instead of leaving the world locked.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			GIT.shutdown();
			try {
				GIT.awaitTermination(3, TimeUnit.MINUTES);
			} catch (InterruptedException ignored) {
			}
		}));
	}

	static Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	static Path savesDir() {
		return gameDir().resolve("saves");
	}

	static Path gitDir(Entry e) {
		return gameDir().resolve("hearth/repos/" + e.folder() + ".git");
	}

	static SharedWorld.Player me() {
		var user = Minecraft.getInstance().getUser();
		return new SharedWorld.Player("mc:" + user.getProfileId(), user.getName());
	}

	static SharedWorld open(Entry e) throws IOException {
		return SharedWorld.open(gitDir(e), savesDir().resolve(e.folder()), e.remote());
	}

	static synchronized List<Entry> entries() {
		Path file = gameDir().resolve("hearth/worlds.json");
		try {
			if (Files.exists(file)) {
				List<Entry> list = GSON.fromJson(Files.readString(file), new TypeToken<List<Entry>>() {}.getType());
				if (list != null) return list;
			}
		} catch (IOException | RuntimeException e) {
			LOG.error("Can't read {}", file, e);
		}
		return new ArrayList<>();
	}

	static synchronized void addEntry(Entry entry) throws IOException {
		List<Entry> list = entries();
		list.add(entry);
		Path file = gameDir().resolve("hearth/worlds.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(list));
	}

	/** Runs {@code job} on the git thread and hands the result to the render thread. */
	static <T> CompletableFuture<T> async(GitJob<T> job) {
		CompletableFuture<T> result = new CompletableFuture<>();
		GIT.execute(() -> {
			try {
				result.complete(job.run());
			} catch (Throwable t) {
				LOG.warn("Hearth operation failed", t);
				result.completeExceptionally(t);
			}
		});
		return result.whenCompleteAsync((v, t) -> {}, Minecraft.getInstance());
	}

	interface GitJob<T> {
		T run() throws Exception;
	}

	static void createFrom(String folder, SharedWorld.Remote remote, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.uploading")));
		Entry entry = new Entry(folder, folder, remote);
		async(() -> {
			SharedWorld.Meta meta = new SharedWorld.Meta(folder, SharedConstants.getCurrentVersion().name());
			SharedWorld.create(gitDir(entry), savesDir().resolve(folder), remote, meta).close();
			addEntry(entry);
			return null;
		}).whenComplete((v, t) -> done(t, back));
	}

	static void addInvite(String text, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		Invite invite;
		try {
			invite = Invite.decode(text);
		} catch (RuntimeException e) {
			mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(back), Component.translatable("hearth.error"), Component.literal(e.getMessage())));
			return;
		}
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.downloading")));
		async(() -> {
			String folder = invite.name().replaceAll("[^\\p{L}\\p{N} _-]", "_");
			for (int i = 2; Files.exists(savesDir().resolve(folder)); i++) folder = invite.name() + " " + i;
			Entry entry = new Entry(invite.name(), folder, invite.remote());
			SharedWorld.cloneInto(gitDir(entry), savesDir().resolve(folder), invite.remote()).close();
			addEntry(entry);
			return null;
		}).whenComplete((v, t) -> done(t, back));
	}

	/** Becomes the host and opens the world. {@code takeover} replaces a host that stopped responding. */
	static void host(Entry entry, boolean takeover, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.downloading")));
		async(() -> {
			SharedWorld world = open(entry);
			try {
				String version = SharedConstants.getCurrentVersion().name();
				SharedWorld.Status status = world.status();
				if (status.meta() != null && !version.equals(status.meta().minecraftVersion())) {
					throw new IllegalStateException(Component.translatable("hearth.wrong_version", status.meta().minecraftVersion(), version).getString());
				}
				SharedWorld.Lock lock = world.acquire(me(), takeover);
				forgetPreviousHost(world.worldDir());
				ScheduledFuture<?> beat = GIT.scheduleWithFixedDelay(() -> {
					try {
						world.heartbeat(lock, null);
					} catch (Exception e) {
						LOG.warn("Heartbeat failed, will retry", e);
					}
				}, 60, 60, TimeUnit.SECONDS);
				session = new Session(entry, world, lock, beat);
				return null;
			} catch (Exception e) {
				world.close();
				throw e;
			}
		}).whenComplete((v, t) -> {
			if (t != null) {
				done(t, back);
			} else {
				mc.createWorldOpenFlows().openWorld(entry.folder(), () -> {
					Session s = session;
					if (s != null) saveAndRelease(s); // world failed to load: give the lock back
				});
			}
		});
	}

	/**
	 * Since 26.x level.dat names the singleplayer owner and the host loads that player's file,
	 * so the next host would get the previous host's inventory. Dropping it makes everyone load their own.
	 */
	static void forgetPreviousHost(Path worldDir) throws IOException {
		Path levelDat = worldDir.resolve("level.dat");
		if (!Files.exists(levelDat)) return;
		CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
		CompoundTag data = root.getCompoundOrEmpty("Data");
		if (data.remove("singleplayer_uuid") != null | data.remove("Player") != null) {
			NbtIo.writeCompressed(root, levelDat);
		}
	}

	static void saveAndRelease(Session s) {
		session = null;
		s.heartbeat().cancel(false);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.saving"))));
		async(() -> {
			try (SharedWorld world = s.world()) {
				world.release(s.lock());
			}
			return null;
		}).whenComplete((v, t) -> done(t, new SharedWorldsScreen(new SelectWorldScreen(new net.minecraft.client.gui.screens.TitleScreen()))));
	}

	private static void done(Throwable t, Screen next) {
		Minecraft mc = Minecraft.getInstance();
		if (t == null) {
			mc.gui.setScreen(next);
			return;
		}
		Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
		Component message = cause instanceof SharedWorld.LostLockException lost
				? Component.translatable("hearth.lost_lock", String.valueOf(lost.recoveryBranch))
				: Component.literal(String.valueOf(cause.getMessage()));
		mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(next), Component.translatable("hearth.error"), message));
	}
}
