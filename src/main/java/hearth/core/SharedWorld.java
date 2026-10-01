package hearth.core;

import com.google.gson.Gson;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * One shared world: a Minecraft save folder that is also the work tree of a git repo.
 * The remote holds the world history on {@code main} (with {@code .hearth/lock.json} saying who hosts)
 * and a force-pushed {@code hearth-heartbeat} branch the host rewrites every minute.
 * Only the lock holder writes {@code main}; a push that is not a fast-forward means someone else
 * took the world over, so git itself is the compare-and-swap for the lock.
 */
public final class SharedWorld implements AutoCloseable {
	public record Remote(String url, String username, String token, GitSsh.Access ssh, WorldPolicy.Trust trust) {
		public Remote(String url, String username, String token) {
			this(url, username, token, null, null);
		}
		public Remote(String url, String username, String token, GitSsh.Access ssh) { this(url, username, token, ssh, null); }
		@Override
		public String toString() {
			return "Remote[url=" + url + ", username=" + username + ", credentials=<redacted>]";
		}

		CredentialsProvider credentials() {
			return token == null || token.isEmpty() ? null : new UsernamePasswordCredentialsProvider(username, token);
		}
	}

	/** {@code id} must be stable across sessions (Steam id via e4steam, otherwise the Minecraft UUID). */
	public record Player(String id, String name) {}

	/** {@code holder == null} means nobody hosts. {@code epoch} grows by one with every host session. */
	public record Lock(Player holder, long epoch, String session) {}

	public record Heartbeat(Player holder, long epoch, String session, long atMillis, String joinTarget) {}

	public record Meta(String name, String minecraftVersion) {}

	public record Status(Meta meta, Lock lock, Heartbeat heartbeat, boolean stale, WorldPolicy policy) {
		/** Where guests connect, if the current host published it. */
		public String joinTarget() {
			return !stale && heartbeat != null
					&& sameSession(lock, new Lock(heartbeat.holder(), heartbeat.epoch(), heartbeat.session()))
					? heartbeat.joinTarget() : null;
		}
	}

	public static final class WorldBusyException extends Exception {
		public final Status status;

		WorldBusyException(Status status) {
			super("World is hosted by " + (status.lock().holder() == null ? "someone else" : status.lock().holder().name()));
			this.status = status;
		}
	}

	/** Our session was taken over; our progress (if any) was pushed to {@link #recoveryBranch}. */
	public static final class LostLockException extends Exception {
		public final String recoveryBranch;

		LostLockException(String recoveryBranch) {
			super("Another player took over the world" + (recoveryBranch == null ? "" : "; your progress was saved to " + recoveryBranch));
			this.recoveryBranch = recoveryBranch;
		}
	}

	static final String MAIN = "refs/heads/main";
	static final String ORIGIN_MAIN = "refs/remotes/origin/main";
	static final String HEARTBEAT = "hearth-heartbeat";
	static final String LOCK_FILE = ".hearth/lock.json";
	static final String META_FILE = ".hearth/meta.json";
	static final String SNAPSHOT_FILE = ".hearth/snapshot.json";
	// ponytail: staleness uses the host's clock (commit/heartbeat time); fine while PCs sync via NTP.
	static final Duration STALE_AFTER = Duration.ofMinutes(5);
	private static final String EXCLUDES = "session.lock\n*_old\n*.tmp\n";
	private static final Gson GSON = new Gson();

	private final Git git;
	private final Remote remote;
	private final Clock clock;
	private final SshdSessionFactory ssh;
	private WorldPolicy verifiedPolicy;
	private final Map<ObjectId, byte[]> blobHashes = new HashMap<>();

	private SharedWorld(Git git, Remote remote, Clock clock) throws IOException {
		this.git = git;
		this.remote = remote;
		this.clock = clock;
		try { this.ssh = GitSsh.factory(remote, git.getRepository().getDirectory().toPath()); }
		catch (IOException | RuntimeException e) { git.close(); throw e; }
	}

	/** Turns an existing save folder into a shared world and pushes it to an empty remote repo. */
	public static SharedWorld create(Path gitDir, Path worldDir, Remote remote, Meta meta) throws IOException, GitAPIException {
		return create(gitDir, worldDir, remote, meta, Clock.systemUTC());
	}

	static SharedWorld create(Path gitDir, Path worldDir, Remote remote, Meta meta, Clock clock) throws IOException, GitAPIException {
		Git git = Git.init().setGitDir(gitDir.toFile()).setDirectory(worldDir.toFile()).setInitialBranch("main").call();
		configure(git.getRepository(), remote.url());
		SharedWorld world = new SharedWorld(git, remote, clock);
		try {
			if (Files.exists(worldDir.resolve(LOCK_FILE))) {
				Lock existing = GSON.fromJson(Files.readString(worldDir.resolve(LOCK_FILE)), Lock.class);
				if (existing == null || existing.holder() != null || existing.epoch() != 0) throw new IOException("This world has already been played; open it from Hearth");
			}
			world.writeJson(META_FILE, meta);
			world.writeJson(LOCK_FILE, new Lock(null, 0, null));
			world.addAll();
			world.signSnapshot();
			world.commit("Create shared world " + meta.name());
			if (!world.push(MAIN, MAIN)) throw new IOException("The remote repo already contains a world; use an empty repo");
			world.fetch();
			return world;
		} catch (IOException | GitAPIException | RuntimeException e) {
			world.close();
			throw e;
		}
	}

	/** Downloads a shared world into an empty save folder. */
	public static SharedWorld cloneInto(Path gitDir, Path worldDir, Remote remote) throws IOException, GitAPIException {
		SshdSessionFactory cloneSsh = GitSsh.factory(remote, gitDir);
		Git git;
		try {
		git = Git.cloneRepository()
				.setURI(remote.url())
				.setCredentialsProvider(remote.credentials())
				.setTransportConfigCallback(transport -> configureTransport(transport, cloneSsh))
				.setTimeout(45)
				.setGitDir(gitDir.toFile())
				.setDirectory(worldDir.toFile())
				.setBranch(MAIN)
				.setNoCheckout(true)
				.call();
		} finally {
			if (cloneSsh != null) cloneSsh.close();
		}
		configure(git.getRepository(), remote.url());
		SharedWorld world = new SharedWorld(git, remote, Clock.systemUTC());
		try {
			world.verifiedPolicy = world.policy();
			world.verifySnapshot(ORIGIN_MAIN);
			git.reset().setMode(ResetType.HARD).setRef(ORIGIN_MAIN).call();
			return world;
		} catch (IOException | GitAPIException e) { world.close(); throw e; }
	}

	public static SharedWorld open(Path gitDir, Path worldDir, Remote remote) throws IOException {
		return open(gitDir, worldDir, remote, Clock.systemUTC());
	}

	static SharedWorld open(Path gitDir, Path worldDir, Remote remote, Clock clock) throws IOException {
		Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setWorkTree(worldDir.toFile()).build();
		return new SharedWorld(new Git(repo), remote, clock);
	}

	private static void configure(Repository repo, String url) throws IOException {
		StoredConfig config = repo.getConfig();
		// World files are binary: never let a user's global autocrlf rewrite them.
		config.setBoolean("core", null, "autocrlf", false);
		config.setBoolean("core", null, "filemode", false);
		config.setBoolean("commit", null, "gpgsign", false);
		config.setString("remote", "origin", "url", url);
		config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
		config.setString("branch", "main", "remote", "origin");
		config.setString("branch", "main", "merge", MAIN);
		config.save();
		Path exclude = repo.getDirectory().toPath().resolve("info/exclude");
		Files.createDirectories(exclude.getParent());
		Files.writeString(exclude, EXCLUDES);
	}

	public Path worldDir() {
		return git.getRepository().getWorkTree().toPath();
	}

	/** Fetches and reports who hosts. Safe to call from the world list. */
	public Status status() throws IOException, GitAPIException {
		fetch();
		return readStatus();
	}

	/**
	 * Becomes the host: brings the save folder to the latest version and pushes a new lock.
	 * {@code takeover} allows replacing a host whose heartbeat went stale (crashed PC).
	 */
	public Lock acquire(Player me, boolean takeover) throws IOException, GitAPIException, WorldBusyException {
		fetch();
		Status status = readStatus();
		verifySnapshot(ORIGIN_MAIN);
		if (status.policy() != null && !status.policy().canHost(profileId(me))) throw new IOException("Your profile cannot host this world");
		Lock current = status.lock();
		boolean mine = current.holder() != null && current.holder().id().equals(me.id())
				&& sameSession(current, localSession());
		if (current.holder() != null && !mine && !(takeover && status.stale())) {
			throw new WorldBusyException(status);
		}

		ObjectId originMain = resolve(ORIGIN_MAIN);
		ObjectId head = resolve(MAIN);
		// Our own session crashed before it was saved: keep playing from the local files.
		boolean resume = mine && isAncestor(originMain, head);
		if (!resume) {
			if (!isClean() || !isAncestor(head, originMain)) {
				saveRecovery(me);
			}
			git.reset().setMode(ResetType.HARD).setRef(ORIGIN_MAIN).call();
			git.clean().setCleanDirectories(true).call();
		}

		Lock lock = new Lock(me, current.epoch() + 1, UUID.randomUUID().toString());
		writeJson(LOCK_FILE, lock);
		git.add().addFilepattern(LOCK_FILE).call();
		PersonIdent who = ident();
		git.commit().setOnly(LOCK_FILE).setMessage("Host: " + me.name()).setAuthor(who).setCommitter(who).setSign(false).call();
		// Save the recovery marker before publishing ownership, so a disk error cannot strand a remote lock.
		Files.writeString(git.getRepository().getDirectory().toPath().resolve("hearth-session.json"), GSON.toJson(lock));
		if (!push(MAIN, MAIN)) {
			// Someone acquired it between our fetch and push: drop our lock commit, keep any resumed progress.
			git.reset().setMode(ResetType.MIXED).setRef(ORIGIN_MAIN).call();
			git.checkout().setStartPoint(ORIGIN_MAIN).addPath(LOCK_FILE).call();
			fetch();
			throw new WorldBusyException(readStatus());
		}
		// A failed initial heartbeat must not strand a successfully acquired lock.
		// The caller starts its heartbeat after opening the world.
		return lock;
	}

	/** Tells others we are alive and where to join. Call about once a minute while hosting. */
	public void heartbeat(Lock lock, String joinTarget) throws IOException, GitAPIException, LostLockException {
		fetch();
		if (!sameSession(lock, readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class))) {
			throw new LostLockException(null);
		}
		Heartbeat beat = new Heartbeat(lock.holder(), lock.epoch(), lock.session(), clock.millis(), joinTarget);
		Repository repo = git.getRepository();
		ObjectId commit;
		try (ObjectInserter inserter = repo.newObjectInserter()) {
			ObjectId blob = inserter.insert(Constants.OBJ_BLOB, GSON.toJson(beat).getBytes(StandardCharsets.UTF_8));
			TreeFormatter tree = new TreeFormatter();
			tree.append("heartbeat.json", FileMode.REGULAR_FILE, blob);
			CommitBuilder builder = new CommitBuilder();
			builder.setTreeId(inserter.insert(tree));
			PersonIdent who = ident();
			builder.setAuthor(who);
			builder.setCommitter(who);
			builder.setMessage("heartbeat");
			commit = inserter.insert(builder);
			inserter.flush();
		}
		RefUpdate update = repo.updateRef("refs/hearth/heartbeat");
		update.setNewObjectId(commit);
		update.forceUpdate();
		if (!push("+refs/hearth/heartbeat", "refs/heads/" + HEARTBEAT)) {
			throw new IOException("Could not publish the host heartbeat");
		}
	}

	/** Publishes a consistent Minecraft save without giving up the host session. */
	public void checkpoint(Lock lock) throws IOException, GitAPIException, LostLockException {
		fetch();
		if (!sameSession(lock, readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class))) {
			throw new LostLockException(null);
		}
		prepareCheckpoint(lock);
		publishCheckpoint(lock);
	}

	/** Run immediately after Minecraft flushes a save, while its server thread is still paused. No networking. */
	public void prepareCheckpoint(Lock lock) throws IOException, GitAPIException {
		Lock local = GSON.fromJson(Files.readString(worldDir().resolve(LOCK_FILE)), Lock.class);
		if (!sameSession(lock, local)) throw new IOException("The local save belongs to another host session");
		addAll();
		signSnapshot();
		if (!isClean()) commit("Checkpoint: " + lock.holder().name());
	}

	/** Upload the immutable Git snapshot off the server thread; the game can keep running. */
	public void publishCheckpoint(Lock lock) throws IOException, GitAPIException, LostLockException {
		fetch();
		if (!sameSession(lock, readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class))) throw new LostLockException(null);
		if (!push(MAIN, MAIN)) throw new LostLockException(null);
	}

	/**
	 * Saves the world and gives up the lock. Call after Minecraft closed the world.
	 * If someone took the world over meanwhile, our files go to a recovery branch instead.
	 */
	public void release(Lock lock) throws IOException, GitAPIException, LostLockException {
		fetch();
		Lock remoteLock = readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class);
		if (!sameSession(remoteLock, lock)) {
			throw new LostLockException(saveRecovery(lock.holder()));
		}
		writeJson(LOCK_FILE, new Lock(null, lock.epoch(), null));
		addAll();
		signSnapshot();
		commit("Save: " + lock.holder().name());
		if (!push(MAIN, MAIN)) {
			throw new LostLockException(saveRecovery(lock.holder()));
		}
		Files.deleteIfExists(git.getRepository().getDirectory().toPath().resolve("hearth-session.json"));
	}

	private Lock localSession() throws IOException {
		Path file = git.getRepository().getDirectory().toPath().resolve("hearth-session.json");
		if (!Files.exists(file)) return null;
		try {
			return GSON.fromJson(Files.readString(file), Lock.class);
		} catch (RuntimeException e) {
			throw new IOException("The local host-session record is damaged; progress was kept", e);
		}
	}

	private static boolean sameSession(Lock a, Lock b) {
		return a != null && b != null && a.holder() != null && b.holder() != null
				&& a.epoch() == b.epoch() && a.holder().id().equals(b.holder().id())
				&& a.session() != null && Objects.equals(a.session(), b.session());
	}

	/**
	 * Uploads whatever the save folder has beyond the remote to a new branch, then resets to the remote.
	 * Returns the branch, or null if there was nothing to keep.
	 */
	private String saveRecovery(Player me) throws IOException, GitAPIException {
		addAll();
		if (!isClean()) {
			signSnapshot();
			commit("Unsaved progress of " + me.name());
		}
		String branch = null;
		if (!isAncestor(resolve(MAIN), resolve(ORIGIN_MAIN))) {
			String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant());
			branch = "hearth-recovered/" + me.name().replaceAll("[^A-Za-z0-9_-]", "_") + "-" + stamp;
			if (!push(MAIN, "refs/heads/" + branch)) {
				throw new IOException("Could not upload recovered progress to " + branch);
			}
		}
		git.reset().setMode(ResetType.HARD).setRef(ORIGIN_MAIN).call();
		git.clean().setCleanDirectories(true).call();
		return branch;
	}

	private Status readStatus() throws IOException {
		WorldPolicy policy = policy();
		Meta meta = readJson(ORIGIN_MAIN, META_FILE, Meta.class);
		Lock lock = readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class);
		Heartbeat beat = readJson("refs/remotes/origin/" + HEARTBEAT, "heartbeat.json", Heartbeat.class);
		long lastSign;
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			lastSign = walk.parseCommit(resolve(ORIGIN_MAIN)).getCommitTime() * 1000L;
		}
		if (meta == null || meta.name() == null || meta.name().isBlank() || meta.name().length() > 255
				|| meta.minecraftVersion() == null || meta.minecraftVersion().length() > 100
				|| lock == null || lock.epoch() < 0 || (lock.holder() != null
				&& (!validPlayer(lock.holder()) || lock.session() == null || !lock.session().matches("[a-f0-9-]{36}")))) {
			throw new IOException("This repository does not contain a valid Hearth world");
		}
		if (beat != null && (!validPlayer(beat.holder()) || beat.atMillis() < 0
				|| beat.joinTarget() != null && beat.joinTarget().length() > 255)) throw new IOException("Invalid host heartbeat");
		if (beat != null && sameSession(lock, new Lock(beat.holder(), beat.epoch(), beat.session()))) {
			lastSign = Math.max(lastSign, beat.atMillis());
		}
		boolean stale = lock.holder() != null && (clock.millis() - lastSign > STALE_AFTER.toMillis()
				|| (policy != null && !policy.canHost(profileId(lock.holder()))));
		return new Status(meta, lock, beat, stale, policy);
	}

	private static boolean validPlayer(Player player) {
		return player != null && player.id() != null && !player.id().isBlank() && player.id().length() <= 255
				&& player.name() != null && !player.name().isBlank() && player.name().length() <= 255;
	}

	private static UUID profileId(Player player) throws IOException {
		try {
			if (!player.id().startsWith("hearth:")) throw new IllegalArgumentException();
			return UUID.fromString(player.id().substring(7));
		} catch (Exception e) { throw new IOException("The host has an invalid Hearth profile"); }
	}

	public WorldPolicy policy() throws IOException {
		WorldPolicy.Trust trust = remote.trust();
		if (trust == null) return null; // Older test worlds retain their existing Steam identity.
		try (var store = new PolicyStore(git.getRepository().getDirectory().toPath().resolve("policy.git"), trust.policyUrl(), trust.policyAccess())) {
			verifiedPolicy = store.load(trust);
			return verifiedPolicy;
		} catch (Exception e) { throw new IOException("Could not verify the creator's world rules", e); }
	}

	public record History(String commit, long atMillis, String description, Activity activity) {}
	public record Visit(Player player, long joinedAt, long leftAt, String ip) {
		public Visit(Player player, long joinedAt, long leftAt) { this(player, joinedAt, leftAt, null); }
	}
	public record Activity(String session, Player host, long startedAt, long endedAt, List<Visit> visits) {}

	public void recordActivity(Activity activity) throws IOException { writeJson(".hearth/activity.json", activity); }

	public List<History> history() throws IOException, GitAPIException {
		return history(0, 100);
	}

	public List<History> history(int offset, int limit) throws IOException, GitAPIException {
		if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid history page");
		fetch();
		verifiedPolicy = policy();
		List<History> history = new ArrayList<>();
		int skipped = 0;
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			walk.markStart(walk.parseCommit(resolve(ORIGIN_MAIN)));
			for (var commit : walk) {
				String title = commit.getShortMessage();
				if (title.startsWith("Save:") || title.startsWith("Checkpoint:") || title.startsWith("Restore:") || title.startsWith("Create shared world")) {
					if (skipped++ < offset) continue;
					Activity activity = readJson(commit.name(), ".hearth/activity.json", Activity.class);
					history.add(new History(commit.name(), commit.getCommitTime() * 1000L, title, activity));
					if (history.size() >= limit) break;
				}
			}
		}
		return history;
	}

	/** Restore files as a new commit. Ownership and current bans live in a separate repository. */
	public void restore(String commitId, Player actor) throws IOException, GitAPIException, WorldBusyException {
		if (commitId == null || !commitId.matches("[a-f0-9]{40}")) throw new IOException("Invalid save identifier");
		fetch();
		Status status = readStatus();
		if (status.policy() != null && !status.policy().ownerUuid().equals(profileId(actor))) throw new IOException("Only the creator can restore a save");
		if (status.lock().holder() != null) throw new WorldBusyException(status);
		ObjectId latest = resolve(ORIGIN_MAIN);
		ObjectId target = resolve(commitId);
		if (target == null || !isAncestor(target, latest)) throw new IOException("That save is outside this world's history");
		verifySnapshot(commitId);
		if (!isClean() || !isAncestor(resolve(MAIN), latest)) saveRecovery(actor);
		git.reset().setMode(ResetType.HARD).setRef(commitId).call();
		git.clean().setCleanDirectories(true).call();
		// Keep the selected files, but parent the new save to the current history.
		git.reset().setMode(ResetType.MIXED).setRef(ORIGIN_MAIN).call();
		writeJson(LOCK_FILE, new Lock(null, status.lock().epoch() + 1, null));
		writeJson(".hearth/restore.json", Map.of("from", commitId, "by", actor.id(), "atMillis", clock.millis()));
		addAll();
		signSnapshot();
		commit("Restore: " + actor.name() + " from " + commitId.substring(0, 8));
		if (!push(MAIN, MAIN)) {
			fetch();
			throw new WorldBusyException(readStatus());
		}
	}

	private void signSnapshot() throws IOException, GitAPIException {
		if (remote.trust() == null) return;
		if (verifiedPolicy == null) verifiedPolicy = policy();
		ObjectId tree;
		try (var inserter = git.getRepository().newObjectInserter()) {
			tree = git.getRepository().readDirCache().writeTree(inserter);
			inserter.flush();
		}
		writeJson(SNAPSHOT_FILE, WorldSnapshot.sign(verifiedPolicy, remote.ssh(), treeDigest(tree), clock.millis()));
		git.add().addFilepattern(SNAPSHOT_FILE).call();
	}

	private void verifySnapshot(String ref) throws IOException {
		if (remote.trust() == null) return;
		try (var walk = new RevWalk(git.getRepository())) {
			WorldSnapshot snapshot = readJson(ref, SNAPSHOT_FILE, WorldSnapshot.class);
			if (snapshot == null) throw new IOException("The world has no signed save; only its creator can migrate it");
			snapshot.verify(verifiedPolicy, treeDigest(walk.parseCommit(resolve(ref)).getTree()));
		}
	}

	/** Hash actual blob contents; cache unchanged Git objects during a host session to keep checkpoints short. */
	private String treeDigest(ObjectId treeId) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (TreeWalk tree = new TreeWalk(git.getRepository())) {
				tree.addTree(treeId);
				tree.setRecursive(true);
				while (tree.next()) {
					String path = tree.getPathString();
					if (path.equals(LOCK_FILE) || path.equals(SNAPSHOT_FILE)) continue;
					if (!tree.getFileMode(0).equals(FileMode.REGULAR_FILE)) throw new IOException("World saves cannot contain links or executable files");
					ObjectId blob = tree.getObjectId(0);
					byte[] hash = blobHashes.get(blob);
					if (hash == null) {
						MessageDigest content = MessageDigest.getInstance("SHA-256");
						try (var stream = git.getRepository().open(blob).openStream()) {
							byte[] buffer = new byte[65536];
							int size;
							while ((size = stream.read(buffer)) != -1) content.update(buffer, 0, size);
						}
						hash = content.digest();
						blobHashes.put(blob, hash);
					}
					digest.update(path.getBytes(StandardCharsets.UTF_8));
					digest.update((byte) 0);
					digest.update(hash);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.GeneralSecurityException e) { throw new IOException(e); }
	}

	private void fetch() throws GitAPIException {
		git.fetch().setRemote("origin").setRemoveDeletedRefs(true).setCredentialsProvider(remote.credentials())
				.setTransportConfigCallback(transport -> configureTransport(transport, ssh)).setTimeout(45).call();
	}

	private static void configureTransport(Transport transport, SshdSessionFactory ssh) {
		if (transport instanceof SshTransport sshTransport && ssh != null) sshTransport.setSshSessionFactory(ssh);
	}

	/** Pushes without force unless {@code src} starts with '+'. Returns false if the remote rejected it. */
	private boolean push(String src, String dst) throws GitAPIException {
		for (int attempt = 1; ; attempt++) {
			RemoteRefUpdate update = null;
			for (PushResult result : git.push()
					.setRemote("origin")
					.setRefSpecs(new RefSpec(src + ":" + dst))
					.setCredentialsProvider(remote.credentials())
					.setTransportConfigCallback(transport -> configureTransport(transport, ssh))
					.setTimeout(45)
					.call()) {
				if (result.getRemoteUpdate(dst) != null) update = result.getRemoteUpdate(dst);
			}
			if (update == null) throw new IllegalStateException("The remote did not confirm the update to " + dst);
			RemoteRefUpdate.Status s = update.getStatus();
			if (s == RemoteRefUpdate.Status.OK || s == RemoteRefUpdate.Status.UP_TO_DATE) return true;
			if (s == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD || s == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) return false;
			// Another push was updating refs at the same moment ("failed to lock" / "cannot lock ref").
			// That says nothing about who won, so retry: the retry is either accepted or rejected as non-fast-forward.
			boolean busy = s == RemoteRefUpdate.Status.REJECTED_OTHER_REASON
					&& update.getMessage() != null && update.getMessage().contains("lock");
			if (!busy) throw new IllegalStateException("Push to " + dst + " failed: " + s + " " + update.getMessage());
			if (attempt == 5) return false;
			try {
				Thread.sleep(100L * attempt + (long) (Math.random() * 200));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	/** Stages every change. Goes through status because JGit's "add ." re-reads every file of the world. */
	private void addAll() throws GitAPIException {
		org.eclipse.jgit.api.Status status = git.status().call();
		Set<String> changed = new HashSet<>(status.getModified());
		changed.addAll(status.getUntracked());
		if (!changed.isEmpty()) {
			AddCommand add = git.add();
			changed.forEach(add::addFilepattern);
			add.call();
		}
		if (!status.getMissing().isEmpty()) {
			RmCommand rm = git.rm().setCached(true);
			status.getMissing().forEach(rm::addFilepattern);
			rm.call();
		}
	}

	private RevCommit commit(String message) throws GitAPIException {
		PersonIdent who = ident();
		return git.commit().setMessage(message).setAuthor(who).setCommitter(who).setAllowEmpty(true).setSign(false).call();
	}

	private PersonIdent ident() {
		return new PersonIdent("Hearth", "hearth@localhost", clock.instant(), ZoneOffset.UTC);
	}

	private boolean isClean() throws GitAPIException {
		return git.status().call().isClean();
	}

	private boolean isAncestor(ObjectId maybeAncestor, ObjectId of) throws IOException {
		if (maybeAncestor == null) return true;
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			return walk.isMergedInto(walk.parseCommit(maybeAncestor), walk.parseCommit(of));
		}
	}

	private ObjectId resolve(String ref) throws IOException {
		return git.getRepository().resolve(ref);
	}

	private void writeJson(String path, Object value) throws IOException {
		Path file = worldDir().resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, GSON.toJson(value));
	}

	private <T> T readJson(String ref, String path, Class<T> type) throws IOException {
		ObjectId commit = resolve(ref);
		if (commit == null) return null;
		Repository repo = git.getRepository();
		try (RevWalk walk = new RevWalk(repo); TreeWalk tree = TreeWalk.forPath(repo, path, walk.parseCommit(commit).getTree())) {
			if (tree == null) return null;
			if (!tree.getFileMode(0).equals(FileMode.REGULAR_FILE)) throw new IOException("Invalid Hearth metadata file");
			try { return GSON.fromJson(new String(repo.open(tree.getObjectId(0)).getBytes(1_000_000), StandardCharsets.UTF_8), type); }
			catch (RuntimeException e) { throw new IOException("Damaged Hearth metadata", e); }
		}
	}

	@Override
	public void close() {
		git.close();
		if (ssh != null) ssh.close();
	}
}
