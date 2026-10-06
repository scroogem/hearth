package hearth.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import hearth.core.Invite;
import hearth.core.GitHubStorage;
import hearth.core.GitHubLogin;
import hearth.core.LocalSecrets;
import hearth.core.WorldPolicy;
import hearth.core.PolicyStore;
import hearth.core.SharedWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry point and state: the list of shared worlds on this PC and the world we currently host. */
public final class HearthClient implements ClientModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("hearth");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** One shared world on this PC. The token stays on this PC, like a launcher keeps its login. */
	public record Entry(String name, String folder, SharedWorld.Remote remote) {}

	record Session(Entry entry, SharedWorld world, SharedWorld.Lock lock,
			ScheduledFuture<?> heartbeat, AtomicReference<String> joinTarget,
			AtomicReference<String> lanTarget,
			AtomicBoolean publishStarted, UUID playerUuid, AtomicReference<WorldPolicy> policy,
			long startedAt, java.util.concurrent.CopyOnWriteArrayList<SharedWorld.Visit> visits) {}

	/** Every git operation runs here, one at a time, off the render thread. */
	static final ScheduledExecutorService GIT = gitExecutor();
	static volatile Session session;
	static volatile Entry joiningEntry;
	static volatile WorldPolicy joiningPolicy;
	private static String transport;

	static String transport() {
		String override = System.getProperty("hearth.transport");
		if ("lan".equals(override)) return "lan";
		if (transport == null) {
			try { transport = Files.readString(gameDir().resolve("hearth/transport.txt")).strip(); }
			catch (IOException e) { transport = "auto"; }
		}
		return "lan".equals(transport) ? "lan" : "auto";
	}

	static void setTransport(String value) {
		try {
			Files.createDirectories(gameDir().resolve("hearth"));
			Files.writeString(gameDir().resolve("hearth/transport.txt"), value);
			transport = value;
		} catch (IOException e) { done(e, Minecraft.getInstance().gui.screen()); }
	}

	private static ScheduledExecutorService gitExecutor() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
			Thread thread = new Thread(r, "hearth-git");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return executor;
	}

	@Override
	public void onInitializeClient() {
		HearthLogin.register();
		WorldRules.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { joiningEntry = null; joiningPolicy = null; });
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Session active = session;
			if (active == null) return;
			var player = handler.getPlayer();
			String ip = WorldRules.peerIp(((hearth.mc.mixin.PlayConnectionAccess) handler).hearth$connection().getRemoteAddress());
			active.visits().add(new SharedWorld.Visit(new SharedWorld.Player(player.getUUID().toString(), player.getGameProfile().name()), System.currentTimeMillis(), 0, ip));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Session active = session;
			if (active == null) return;
			String id = handler.getPlayer().getUUID().toString();
			active.visits().replaceAll(visit -> visit.leftAt() == 0 && visit.player().id().equals(id)
					? new SharedWorld.Visit(visit.player(), visit.joinedAt(), System.currentTimeMillis(), visit.ip()) : visit);
		});
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (screen instanceof SelectWorldScreen) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("hearth.shared_worlds"),
						b -> mc.gui.setScreen(new SharedWorldsScreen(screen))).bounds(width - 130, 6, 124, 20).build());
			}
			Entry activeEntry = session != null ? session.entry() : mc.level != null ? joiningEntry : null;
			if (screen instanceof net.minecraft.client.gui.screens.PauseScreen && activeEntry != null) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("hearth.shared_worlds"),
						b -> mc.gui.setScreen(new WorldDetailsScreen(activeEntry, screen))).bounds(width - 130, 6, 124, 20).build());
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Session s = session;
			IntegratedServer integrated = mc.getSingleplayerServer();
			if (mc.level == null || mc.getConnection() == null || integrated == null) return;
			Path dir = integrated.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
			if (s == null || !s.world().worldDir().toAbsolutePath().normalize().equals(dir)) {
				return;
			}
			if (!s.publishStarted().compareAndSet(false, true)) return;
			try {
				if (integrated.isPublished() || integrated.publishServer(MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort())) {
					if (s.entry().remote().trust() != null) {
						s.lanTarget().set(lanAddress(integrated.getPort()));
						s.joinTarget().set(s.lanTarget().get());
						GIT.execute(() -> {
							try { s.world().heartbeat(s.lock(), s.joinTarget().get()); }
							catch (Exception e) { LOG.warn("Could not publish the LAN address", e); }
						});
					}
					if ((s.entry().remote().trust() == null || !transport().equals("lan")) && SteamBridge.installed()) pollSteamAddress(s, 0);
					else if (s.entry().remote().trust() != null && !transport().equals("lan")) {
						GIT.execute(() -> {
							try {
								String target = hearth.steam.EmbeddedSteam.host(gameDir(), integrated.getPort());
								if (session != s) { hearth.steam.EmbeddedSteam.stopHost(); return; }
								s.joinTarget().set(target);
								s.world().heartbeat(s.lock(), target);
							} catch (Exception e) {
								LOG.info("Steam relay unavailable; LAN is still open: {}", e.getClass().getSimpleName());
								notifyHost(s, "hearth.steam_lan_fallback");
							}
						});
					}
				} else {
					LOG.error("Could not open the shared world to e4steam");
					notifyHost(s, "hearth.steam_open_failed");
				}
			} catch (Exception e) {
				LOG.error("Could not open the shared world to e4steam", e);
				notifyHost(s, "hearth.steam_open_failed");
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

	/** Reject vanilla opening before a server starts, rather than blocking the client during shutdown. */
	public static boolean allowWorldOpen(String folder) {
		Session active = session;
		if (active != null && active.entry().folder().equals(folder)) return true;
		Path directory = savesDir().resolve(folder).normalize();
		if (!Files.exists(directory.resolve(".hearth/meta.json"))) return true;
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(new SharedWorldsScreen(new SelectWorldScreen(new net.minecraft.client.gui.screens.TitleScreen()))),
				Component.translatable("hearth.shared_worlds"), Component.translatable("hearth.open_from_hearth")));
		return false;
	}

	static Path gitDir(Entry e) {
		return gameDir().resolve("hearth/repos/" + e.folder() + ".git");
	}

	static SharedWorld.Player me() {
		var user = Minecraft.getInstance().getUser();
		return new SharedWorld.Player("mc:" + user.getProfileId(), user.getName());
	}

	static SharedWorld.Player me(Entry entry) {
		if (entry.remote().trust() == null) return me();
		return new SharedWorld.Player("hearth:" + WorldPolicy.identity(entry.remote().ssh().publicKey()),
				Minecraft.getInstance().getUser().getName());
	}

	static SharedWorld open(Entry e) throws IOException {
		return SharedWorld.open(gitDir(e), savesDir().resolve(e.folder()), e.remote());
	}

	private static String lanAddress(int port) throws java.net.SocketException {
		var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
		while (interfaces.hasMoreElements()) {
			var network = interfaces.nextElement();
			if (!network.isUp() || network.isLoopback()) continue;
			var addresses = network.getInetAddresses();
			while (addresses.hasMoreElements()) {
				var address = addresses.nextElement();
				if (address instanceof java.net.Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress() + ":" + port;
			}
		}
		return "127.0.0.1:" + port;
	}

	/** Only the local player of the shared integrated server receives the Steam profile. */
	public static UUID hostPlayerUuid(MinecraftServer server) {
		Session s = session;
		if (s == null || !(server instanceof IntegratedServer)) return null;
		Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
		return s.world().worldDir().toAbsolutePath().normalize().equals(dir) ? s.playerUuid() : null;
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
		if (list.stream().anyMatch(existing -> existing.remote().url().equals(entry.remote().url()))) return;
		list.add(entry);
		Path file = gameDir().resolve("hearth/worlds.json");
		Files.createDirectories(file.getParent());
		LocalSecrets.write(file, GSON.toJson(list));
	}

	static Path ownerFile(Entry entry) { return gameDir().resolve("hearth/owners/" + entry.folder() + ".json"); }
	static boolean isOwner(Entry entry) {
		return entry.remote().trust() != null && entry.remote().ssh() != null
				&& entry.remote().trust().ownerKey().equals(entry.remote().ssh().publicKey()) && Files.isRegularFile(ownerFile(entry));
	}
	static WorldPolicy cachedPolicy(Entry entry) {
		Session active = session;
		return active != null && active.entry().equals(entry) ? active.policy().get() : entry.equals(joiningEntry) ? joiningPolicy : null;
	}
	record GrantedAccess(long keyId, long policyKeyId, String publicKey, String policyPublicKey, String name, boolean banned) {}
	record Owner(GitHubStorage.Repository repository, GitHubStorage.Repository policyRepository, List<GrantedAccess> accesses) {}

	static GitHubLogin loginClient() throws IOException {
		try (var stream = HearthClient.class.getResourceAsStream("/hearth-github-client-id.txt")) {
			if (stream == null) throw new IOException("GitHub sign-in is not configured in this build");
			return new GitHubLogin(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip());
		}
	}

	static Path accountFile() { return gameDir().resolve("hearth/github-account.json"); }
	static GitHubLogin.Tokens account() throws IOException {
		if (!Files.exists(accountFile())) return null;
		return GSON.fromJson(Files.readString(accountFile()), GitHubLogin.Tokens.class);
	}
	static void saveAccount(GitHubLogin.Tokens tokens) throws IOException { LocalSecrets.write(accountFile(), GSON.toJson(tokens)); }
	static GitHubStorage storage() throws Exception {
		GitHubLogin.Tokens tokens = account();
		if (tokens == null) throw new IOException("Sign in to GitHub to create a shared world");
		if (tokens.expired()) { tokens = loginClient().refresh(tokens); saveAccount(tokens); }
		return new GitHubStorage(tokens.accessToken());
	}

	static Path pendingCreation(String folder) { return gameDir().resolve("hearth/owners/" + folder + "-pending.json"); }

	static void createNewWorld(Screen back) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.openFresh(mc,
				() -> mc.gui.setScreen(new SharedWorldsScreen.AddWorldScreen(back)),
				(screen, registries, data, rules, packs) -> {
			String folder = screen.getUiState().getTargetFolder();
			try (var access = mc.getLevelSource().createAccess(folder)) {
				if (access.hasWorldData()) throw new IOException("The selected world folder already exists");
				Path root = access.getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT);
				if (packs != null) try (var paths = Files.walk(packs)) {
					for (Path source : paths.toList()) {
						if (Files.isSymbolicLink(source)) throw new IOException("A data pack contains a symbolic link");
						Path target = root.resolve("datapacks").resolve(packs.relativize(source));
						if (Files.isDirectory(source)) Files.createDirectories(target);
						else Files.copy(source, target);
					}
				}
				net.minecraft.world.level.storage.LevelStorageSource.writeWorldGenSettings(registries.compositeAccess(), root, data.genSettings());
				net.minecraft.world.level.storage.LevelStorageSource.writeGameRules(data.data(), root, rules.orElse(screen.getUiState().getGameRules()));
				access.saveDataTag(data.data());
				if (!access.hasWorldData()) throw new IOException("Minecraft could not save the new world");
				mc.gui.setScreen(new GitHubWorldScreen(folder, back));
			} catch (Exception e) { done(e, back); }
			return true;
		});
	}

	static void createOnGitHub(String folder, Screen back) {
		Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.uploading")));
		async(() -> {
			Path pending = pendingCreation(folder);
			Path savedAccess = gameDir().resolve("hearth/owners/" + folder + "-access.json");
			Entry entry;
			WorldPolicy policy;
			if (Files.exists(pending)) {
				entry = GSON.fromJson(Files.readString(savedAccess), Entry.class);
				policy = GSON.fromJson(Files.readString(pending), WorldPolicy.class);
			} else {
				var settings = WorldRules.capture(savesDir().resolve(folder));
				WorldRules.validate(settings);
				GitHubStorage storage = storage();
				var repository = storage.createRepository(folder);
				var key = storage.createAccess(repository, "Creator");
				GitHubStorage.Repository policyRepository = null;
				hearth.core.GitSsh.Access policyKey = null;
				try {
					policyRepository = storage.createRepository(folder + " — creator rules", true);
					policyKey = storage.createAccess(policyRepository, "Creator rules");
					var initial = WorldPolicy.initial(key, Minecraft.getInstance().getUser().getName(), ModManifest.current());
					policy = new WorldPolicy(initial.worldId(), initial.ownerKey(), initial.revision(), settings,
							initial.members(), initial.bannedIps(), initial.mods());
					var trust = new WorldPolicy.Trust(policy.worldId(), policy.ownerKey(), policyRepository.sshUrl(), policyKey);
					entry = new Entry(folder, folder, new SharedWorld.Remote(repository.sshUrl(), "git", null, key, trust));
					LocalSecrets.write(ownerFile(entry), GSON.toJson(new Owner(repository, policyRepository, new ArrayList<>())));
					LocalSecrets.write(savedAccess, GSON.toJson(entry));
					LocalSecrets.write(pending, GSON.toJson(policy));
				} catch (Exception e) {
					try { storage.revokeAccess(repository, key.deployKeyId()); }
					catch (Exception failure) { e.addSuppressed(failure); }
					if (policyKey != null) try { storage.revokeAccess(policyRepository, policyKey.deployKeyId()); }
					catch (Exception failure) { e.addSuppressed(failure); }
					throw e;
				}
			}
			var trust = entry.remote().trust();
			try (var rules = new PolicyStore(gitDir(entry).resolve("policy.git"), trust.policyUrl(), trust.policyAccess())) {
				rules.publish(policy, entry.remote().ssh());
			}
			try (var world = SharedWorld.create(gitDir(entry), savesDir().resolve(folder), entry.remote(),
					new SharedWorld.Meta(folder, SharedConstants.getCurrentVersion().name()))) {
				addEntry(entry);
			}
			Files.deleteIfExists(pending);
			return entry;
		}).whenComplete((entry, t) -> {
			if (t != null) done(t, new SharedWorldsScreen.AddWorldScreen(back));
			else play(entry, back);
		});
	}

	static Owner owner(Entry entry) throws IOException {
		return GSON.fromJson(Files.readString(ownerFile(entry)), Owner.class);
	}

	static void updateRules(Entry entry, java.util.function.UnaryOperator<WorldPolicy> update, Screen back) {
		updateRules(entry, update, back, back);
	}

	static void updateRules(Entry entry, java.util.function.UnaryOperator<WorldPolicy> update, Screen success, Screen failure) {
		Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.updating_rules")));
		async(() -> {
			try (var store = new PolicyStore(gitDir(entry).resolve("policy.git"), entry.remote().trust().policyUrl(), entry.remote().trust().policyAccess())) {
				var changed = update.apply(store.load(entry.remote().trust()));
				// A signed permission change must not be hosted by a version that ignores that permission.
				var mods = new java.util.TreeMap<>(changed.mods());
				mods.put("hearth", ModManifest.current().get("hearth"));
				changed = new WorldPolicy(changed.worldId(), changed.ownerKey(), changed.revision(), changed.settings(), changed.members(), changed.bannedIps(), mods);
				WorldRules.validate(changed.settings());
				store.publish(changed, entry.remote().ssh());
				var active = session;
				if (active != null && active.entry().equals(entry)) active.policy().set(changed);
			}
			return null;
		}).whenComplete((value, error) -> done(error, error == null ? success : failure));
	}

	static void restoreSave(Entry entry, String commit, Screen back) {
		Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.restoring")));
		async(() -> {
			try (var world = open(entry)) { world.restore(commit, me(entry)); }
			return null;
		}).whenComplete((value, error) -> done(error, back));
	}

	static void revokeInvite(Entry entry, GrantedAccess access, Screen back) {
		setMemberBan(entry, access, true, back);
	}

	static void setMemberBan(Entry entry, GrantedAccess access, boolean banned, Screen back) {
		Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.updating_access")));
		async(() -> {
			Owner owner = owner(entry);
			GitHubStorage storage = storage();
			long keyId = access.keyId(), policyKeyId = access.policyKeyId();
			try (var rules = new PolicyStore(gitDir(entry).resolve("policy.git"), entry.remote().trust().policyUrl(), entry.remote().trust().policyAccess())) {
				var policy = rules.load(entry.remote().trust());
				if (banned) {
					if (!policy.member(WorldPolicy.identity(access.publicKey())).banned()) rules.publish(policy.ban(access.publicKey(), true), entry.remote().ssh());
					storage.revokeAccess(owner.repository(), keyId);
					storage.revokeAccess(owner.policyRepository(), policyKeyId);
				} else {
					keyId = storage.restoreAccess(owner.repository(), access.publicKey(), access.name(), false);
					try {
						policyKeyId = storage.restoreAccess(owner.policyRepository(), access.policyPublicKey(), access.name(), true);
						try { rules.publish(policy.ban(access.publicKey(), false), entry.remote().ssh()); }
						catch (Exception e) { storage.revokeAccess(owner.policyRepository(), policyKeyId); throw e; }
					} catch (Exception e) { storage.revokeAccess(owner.repository(), keyId); throw e; }
				}
			}
			GrantedAccess updated = new GrantedAccess(keyId, policyKeyId, access.publicKey(), access.policyPublicKey(), access.name(), banned);
			owner.accesses().replaceAll(a -> a.publicKey().equals(access.publicKey()) ? updated : a);
			LocalSecrets.write(ownerFile(entry), GSON.toJson(owner));
			Session active = session;
			if (active != null && active.entry().folder().equals(entry.folder())) active.policy().set(active.world().policy());
			return null;
		}).whenComplete((v, t) -> done(t, back));
	}

	static void createInvite(Entry entry, Screen back) {
		Minecraft.getInstance().gui.setScreen(new WorldAccessScreen.InviteScreen(entry, back));
	}

	static void createInvite(Entry entry, String name, Screen back) {
		if (entry.remote().ssh() == null) {
			Minecraft.getInstance().keyboardHandler.setClipboard(new Invite(entry.name(), entry.remote()).encode());
			return;
		}
		Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.creating_invite")));
		async(() -> {
			if (!isOwner(entry)) throw new IOException("Only the world creator can issue new invites");
			Owner owner = GSON.fromJson(Files.readString(ownerFile(entry)), Owner.class);
			if (name == null || name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) throw new IOException("Enter a name for your friend");
			GitHubStorage storage = storage();
			var key = storage.createAccess(owner.repository(), name);
			hearth.core.GitSsh.Access policyKey;
			try { policyKey = storage.createAccess(owner.policyRepository(), name + " — read rules", true); }
			catch (Exception e) {
				try { storage.revokeAccess(owner.repository(), key.deployKeyId()); }
				catch (Exception failure) { e.addSuppressed(failure); }
				throw e;
			}
			try {
				try (var rules = new PolicyStore(gitDir(entry).resolve("policy.git"), entry.remote().trust().policyUrl(), entry.remote().trust().policyAccess())) {
					var policy = rules.load(entry.remote().trust());
					var changed = policy.addMember(key, name);
					rules.publish(changed, entry.remote().ssh());
					var active = session;
					if (active != null && active.entry().equals(entry)) active.policy().set(changed);
				}
				owner.accesses().add(new GrantedAccess(key.deployKeyId(), policyKey.deployKeyId(), key.publicKey(), policyKey.publicKey(), name, false));
				LocalSecrets.write(ownerFile(entry), GSON.toJson(owner));
			} catch (Exception e) {
				try { storage.revokeAccess(owner.repository(), key.deployKeyId()); }
				catch (Exception revokeFailure) { e.addSuppressed(revokeFailure); }
				try { storage.revokeAccess(owner.policyRepository(), policyKey.deployKeyId()); }
				catch (Exception revokeFailure) { e.addSuppressed(revokeFailure); }
				throw e;
			}
			var trust = new WorldPolicy.Trust(entry.remote().trust().worldId(), entry.remote().trust().ownerKey(), owner.policyRepository().sshUrl(), policyKey);
			return new Invite(entry.name(), new SharedWorld.Remote(owner.repository().sshUrl(), "git", null, key, trust)).encode();
		}).whenComplete((code, t) -> {
			if (t != null) { done(t, back); return; }
			Minecraft mc = Minecraft.getInstance();
			mc.keyboardHandler.setClipboard(code);
			mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(back), Component.translatable("hearth.copied"),
					Component.translatable("hearth.invite_private")));
		});
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
		addInvite(text, back, back);
	}

	static void addInvite(String text, Screen success, Screen failure) {
		Minecraft mc = Minecraft.getInstance();
		Invite invite;
		try {
			invite = Invite.decode(text);
		} catch (RuntimeException e) {
			mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(failure), Component.translatable("hearth.error"), Component.translatable("hearth.invalid_invite")));
			return;
		}
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.downloading")));
		async(() -> {
			if (entries().stream().anyMatch(e -> e.remote().url().equals(invite.remote().url())))
				throw new IOException(Component.translatable("hearth.already_added").getString());
			String base = invite.name().replaceAll("[^\\p{L}\\p{N} _-]", "_").strip();
			if (base.isEmpty()) base = "Shared world";
			String folder = base;
			for (int i = 2; Files.exists(savesDir().resolve(folder)) || Files.exists(gameDir().resolve("hearth/repos/" + folder + ".git")); i++) {
				folder = base + " " + i;
			}
			Entry entry = new Entry(invite.name(), folder, invite.remote());
			SharedWorld.cloneInto(gitDir(entry), savesDir().resolve(folder), invite.remote()).close();
			addEntry(entry);
			return null;
		}).whenComplete((v, t) -> done(t, t == null ? success : failure));
	}

	/** Recheck the live status when Play is pressed; screen snapshots can be out of date. */
	static void play(Entry entry, Screen back) {
		if (session != null) {
			if (session.entry().equals(entry) && Minecraft.getInstance().level != null) Minecraft.getInstance().gui.setScreen(null);
			else done(new IOException(Component.translatable("hearth.finish_current_session").getString()), back);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.connecting")));
		async(() -> {
			try (SharedWorld world = open(entry)) {
				return world.status();
			}
		}).whenComplete((status, failure) -> {
			if (failure != null) {
				done(failure, back);
			} else if (status.policy() != null && (status.policy().member(WorldPolicy.identity(entry.remote().ssh().publicKey())) == null
					|| status.policy().member(WorldPolicy.identity(entry.remote().ssh().publicKey())).banned())) {
				done(new IllegalStateException(Component.translatable("hearth.profile_rejected").getString()), back);
			} else if (status.lock().holder() == null || status.lock().holder().id().equals(me(entry).id())) {
				host(entry, false, back);
			} else if (status.stale()) {
				mc.gui.setScreen(new ConfirmScreen(yes -> {
					if (yes) host(entry, true, back);
					else mc.gui.setScreen(back);
				}, Component.translatable("hearth.take_over"),
						Component.translatable("hearth.take_over_warning", status.lock().holder().name())));
			} else if (status.joinTarget() != null) {
				joiningEntry = entry;
				joiningPolicy = status.policy();
				join(entry, status.joinTarget(), back);
			} else {
				waitForHost(entry, back, 0);
			}
		});
	}

	private static void waitForHost(Entry entry, Screen back, int attempt) {
		Minecraft mc = Minecraft.getInstance();
		Screen waiting = new Screen(Component.translatable("hearth.waiting_host")) {
			@Override protected void init() {
				addRenderableWidget(new net.minecraft.client.gui.components.StringWidget((width - font.width(title)) / 2, height / 2 - 20, font.width(title), 20, title, font));
				addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(width / 2 - 100, height / 2 + 12, 200, 20).build());
			}
			@Override public void onClose() { minecraft.gui.setScreen(back); }
		};
		mc.gui.setScreen(waiting);
		GIT.schedule(() -> {
			if (mc.gui.screen() != waiting) return;
			async(() -> {
				try (SharedWorld world = open(entry)) { return world.status(); }
			}).whenComplete((status, failure) -> {
				if (mc.gui.screen() != waiting) return;
				if (failure != null) done(failure, back);
				else if (status.lock().holder() == null || status.stale() || status.joinTarget() != null) play(entry, back);
				else if (attempt < 30) waitForHost(entry, back, attempt + 1);
				else done(new IllegalStateException(Component.translatable("hearth.host_not_ready").getString()), back);
			});
		}, 3, TimeUnit.SECONDS);
	}

	/** Becomes the host and opens the world. {@code takeover} replaces a host that stopped responding. */
	static void host(Entry entry, boolean takeover, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		if (entry.remote().trust() == null && !SteamBridge.installed()) {
			done(new IllegalStateException(Component.translatable("hearth.steam_required").getString()), back);
			return;
		}
		mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.downloading")));
		async(() -> {
			UUID steamUuid = SteamBridge.localUuid();
			if (entry.remote().trust() == null && steamUuid == null) throw new IllegalStateException(Component.translatable("hearth.steam_required").getString());
			SharedWorld world = open(entry);
			SharedWorld.Lock acquired = null;
			try {
				String version = SharedConstants.getCurrentVersion().name();
				SharedWorld.Status status = world.status();
				if (status.policy() != null) {
					WorldRules.validate(status.policy().settings());
					ModManifest.verify(status.policy(), ModManifest.current());
				}
				if (status.meta() != null && !version.equals(status.meta().minecraftVersion())) {
					throw new IllegalStateException(Component.translatable("hearth.wrong_version", status.meta().minecraftVersion(), version).getString());
				}
				SharedWorld.Lock lock = world.acquire(me(entry), takeover);
				acquired = lock;
				UUID playerUuid = entry.remote().trust() == null ? steamUuid : WorldPolicy.identity(entry.remote().ssh().publicKey());
				HostPlayerData.prepare(world.worldDir(), Minecraft.getInstance().getUser().getProfileId(), playerUuid);
				forgetPreviousHost(world.worldDir());
				AtomicReference<String> joinTarget = new AtomicReference<>();
				long[] lastCheckpoint = {System.nanoTime()};
				ScheduledFuture<?> beat = GIT.scheduleWithFixedDelay(() -> {
					try {
						Session active = session;
						String target = transport().equals("lan") && entry.remote().trust() != null ? null
								: SteamBridge.installed() ? SteamBridge.readyAddress() : hearth.steam.EmbeddedSteam.hostAddress();
						if (target == null) target = active != null && active.world() == world ? active.lanTarget().get() : joinTarget.get();
						joinTarget.set(target);
						world.heartbeat(lock, target);
						if (active != null && active.world() == world && entry.remote().trust() != null) {
							active.policy().set(world.policy());
						}
						if (active != null && active.world() == world
								&& System.nanoTime() - lastCheckpoint[0] >= TimeUnit.MINUTES.toNanos(2)) {
							checkpoint(active);
							lastCheckpoint[0] = System.nanoTime();
						}
					} catch (SharedWorld.LostLockException e) {
						mc.execute(() -> {
							if (session != null && session.world() == world) mc.disconnectWithSavingScreen();
						});
					} catch (Exception e) {
						LOG.warn("Heartbeat failed, will retry", e);
					}
				}, 60, 60, TimeUnit.SECONDS);
				session = new Session(entry, world, lock, beat, joinTarget, new AtomicReference<>(), new AtomicBoolean(), playerUuid, new AtomicReference<>(status.policy()),
						System.currentTimeMillis(), new java.util.concurrent.CopyOnWriteArrayList<>());
				return null;
			} catch (Exception e) {
				if (acquired != null) {
					try {
						world.release(acquired);
					} catch (Exception releaseFailure) {
						e.addSuppressed(releaseFailure);
					}
				}
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

	/** Called on the Git executor; only saving/staging blocks the server, never the upload. */
	static void checkpoint(Session expected) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (session != expected || server == null || server.isStopped() || server.isShutdown()) return;
		var staged = server.submit(() -> {
			if (session != expected || server.isStopped() || server.isShutdown()) return false;
			server.saveEverything(true, true, true);
			try {
				expected.world().recordActivity(new SharedWorld.Activity(expected.lock().session(), expected.lock().holder(), expected.startedAt(), 0, List.copyOf(expected.visits())));
				expected.world().prepareCheckpoint(expected.lock());
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
			return true;
		});
		try {
			if (staged.get(60, TimeUnit.SECONDS)) {
				expected.world().publishCheckpoint(expected.lock());
				LOG.info("Published a checkpoint for {}", expected.entry().name());
			}
		} catch (java.util.concurrent.TimeoutException e) {
			staged.cancel(false);
			throw e;
		}
	}

	/** e4steam starts asynchronously after Minecraft opens the LAN listener. */
	private static void pollSteamAddress(Session expected, int attempts) {
		GIT.schedule(() -> {
			if (session != expected) return;
			String target = SteamBridge.readyAddress();
			if (target != null) {
				expected.joinTarget().set(target);
				try {
					expected.world().heartbeat(expected.lock(), target);
				} catch (Exception e) {
					LOG.warn("Could not publish the Steam join address", e);
				}
			} else if (attempts < 90) {
				pollSteamAddress(expected, attempts + 1);
			} else {
				LOG.error("e4steam did not provide a join address within 90 seconds");
				notifyHost(expected, "hearth.steam_timeout");
			}
		}, 1, TimeUnit.SECONDS);
	}

	private static void notifyHost(Session expected, String key) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (session == expected && mc.player != null) {
				mc.player.sendSystemMessage(Component.translatable(key));
			}
		});
	}

	static void join(Entry entry, String target, Screen back) {
		if (target.endsWith(".steam") && !SteamBridge.installed() && entry.remote().trust() == null) {
			done(new IllegalStateException(Component.translatable("hearth.steam_required").getString()), back);
			return;
		}
		if (entry.remote().trust() != null) {
			Minecraft.getInstance().gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.connecting")));
			async(() -> { try (var world = open(entry)) { return world.policy(); } }).whenComplete((policy, failure) -> {
				if (failure != null) { done(failure, back); return; }
				joiningEntry = entry;
				joiningPolicy = policy;
				try { ModManifest.verify(policy, ModManifest.current()); }
				catch (IOException e) { done(e, back); return; }
				connect(entry, target, back);
			});
			return;
		}
		connect(entry, target, back);
	}

	private static void connect(Entry entry, String target, Screen back) {
		Minecraft mc = Minecraft.getInstance();
		if (target.endsWith(".steam") && !SteamBridge.installed()) {
			mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.connecting")));
			async(() -> hearth.steam.EmbeddedSteam.connect(gameDir(), target)).whenComplete((endpoint, error) -> {
				if (error != null) done(error, back);
				else connect(entry, endpoint, back);
			});
			return;
		}
		ConnectScreen.startConnecting(back, mc, ServerAddress.parseString(target),
				new ServerData(entry.name(), target, ServerData.Type.OTHER), false, null);
	}

	/**
	 * Since 26.x level.dat names the singleplayer owner and the host loads that player's file,
	 * so the next host would get the previous host's inventory. Dropping it makes everyone load their own.
	 */
	static void forgetPreviousHost(Path worldDir) throws IOException {
		Path levelDat = worldDir.resolve("level.dat");
		if (!Files.exists(levelDat)) return;
		CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.create(16 * 1024 * 1024));
		CompoundTag data = root.getCompoundOrEmpty("Data");
		if (data.remove("singleplayer_uuid") != null | data.remove("Player") != null) {
			NbtIo.writeCompressed(root, levelDat);
		}
	}

	static void saveAndRelease(Session s) {
		session = null;
		hearth.steam.EmbeddedSteam.stopHost();
		s.heartbeat().cancel(false);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setScreen(new GenericMessageScreen(Component.translatable("hearth.saving"))));
		async(() -> {
			try (SharedWorld world = s.world()) {
				world.recordActivity(new SharedWorld.Activity(s.lock().session(), s.lock().holder(), s.startedAt(), System.currentTimeMillis(), List.copyOf(s.visits())));
				world.release(s.lock());
			}
			return null;
		}).whenComplete((v, t) -> {
			Screen next = new SharedWorldsScreen(new SelectWorldScreen(new net.minecraft.client.gui.screens.TitleScreen()));
			Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
			if (cause != null && !(cause instanceof SharedWorld.LostLockException))
				mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(next), Component.translatable("hearth.error"), Component.translatable("hearth.save_failed", String.valueOf(cause.getMessage()))));
			else done(t, next);
		});
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
