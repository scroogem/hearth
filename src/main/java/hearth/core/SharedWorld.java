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
import java.util.Set;
import java.util.UUID;

/**
 * One shared world: a Minecraft save folder that is also the work tree of a git repo.
 * The remote holds the world history on {@code main} (with {@code .hearth/lock.json} saying who hosts)
 * and a force-pushed {@code hearth-heartbeat} branch the host rewrites every minute.
 * Only the lock holder writes {@code main}; a push that is not a fast-forward means someone else
 * took the world over, so git itself is the compare-and-swap for the lock.
 */
public final class SharedWorld implements AutoCloseable {
	public record Remote(String url, String username, String token) {
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

	public record Status(Meta meta, Lock lock, Heartbeat heartbeat, boolean stale) {
		/** Where guests connect, if the current host published it. */
		public String joinTarget() {
			return !stale && heartbeat != null && lock.holder() != null
					&& heartbeat.epoch() == lock.epoch() && lock.session() != null
					&& lock.session().equals(heartbeat.session()) ? heartbeat.joinTarget() : null;
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
	// ponytail: staleness uses the host's clock (commit/heartbeat time); fine while PCs sync via NTP.
	static final Duration STALE_AFTER = Duration.ofMinutes(5);
	private static final String EXCLUDES = "session.lock\n*_old\n*.tmp\n";
	private static final Gson GSON = new Gson();

	private final Git git;
	private final Remote remote;
	private final Clock clock;

	private SharedWorld(Git git, Remote remote, Clock clock) {
		this.git = git;
		this.remote = remote;
		this.clock = clock;
	}

	/** Turns an existing save folder into a shared world and pushes it to an empty remote repo. */
	public static SharedWorld create(Path gitDir, Path worldDir, Remote remote, Meta meta) throws IOException, GitAPIException {
		return create(gitDir, worldDir, remote, meta, Clock.systemUTC());
	}

	static SharedWorld create(Path gitDir, Path worldDir, Remote remote, Meta meta, Clock clock) throws IOException, GitAPIException {
		Git git = Git.init().setGitDir(gitDir.toFile()).setDirectory(worldDir.toFile()).setInitialBranch("main").call();
		configure(git.getRepository(), remote.url());
		SharedWorld world = new SharedWorld(git, remote, clock);
		world.writeJson(META_FILE, meta);
		world.writeJson(LOCK_FILE, new Lock(null, 0, null));
		world.addAll();
		world.commit("Create shared world " + meta.name());
		if (!world.push(MAIN, MAIN)) {
			throw new IOException("The remote repo already contains a world; use an empty repo");
		}
		world.git.fetch().setRemote("origin").setCredentialsProvider(remote.credentials()).call();
		return world;
	}

	/** Downloads a shared world into an empty save folder. */
	public static SharedWorld cloneInto(Path gitDir, Path worldDir, Remote remote) throws IOException, GitAPIException {
		Git git = Git.cloneRepository()
				.setURI(remote.url())
				.setCredentialsProvider(remote.credentials())
				.setGitDir(gitDir.toFile())
				.setDirectory(worldDir.toFile())
				.setBranch(MAIN)
				.setNoCheckout(true)
				.call();
		configure(git.getRepository(), remote.url());
		git.reset().setMode(ResetType.HARD).setRef(ORIGIN_MAIN).call();
		return new SharedWorld(git, remote, Clock.systemUTC());
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
		Lock current = status.lock();
		boolean mine = current.holder() != null && current.holder().id().equals(me.id());
		if (current.holder() != null && !mine && !(takeover && status.stale())) {
			throw new WorldBusyException(status);
		}

		ObjectId originMain = resolve(ORIGIN_MAIN);
		ObjectId head = resolve(MAIN);
		// Our own session crashed before it was saved: keep playing from the local files.
		boolean resume = mine && originMain.equals(head);
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
		if (!push(MAIN, MAIN)) {
			// Someone acquired it between our fetch and push: drop our lock commit, keep any resumed progress.
			git.reset().setMode(ResetType.MIXED).setRef(ORIGIN_MAIN).call();
			git.checkout().setStartPoint(ORIGIN_MAIN).addPath(LOCK_FILE).call();
			fetch();
			throw new WorldBusyException(readStatus());
		}
		heartbeat(lock, null);
		return lock;
	}

	/** Tells others we are alive and where to join. Call about once a minute while hosting. */
	public void heartbeat(Lock lock, String joinTarget) throws IOException, GitAPIException {
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

	/**
	 * Saves the world and gives up the lock. Call after Minecraft closed the world.
	 * If someone took the world over meanwhile, our files go to a recovery branch instead.
	 */
	public void release(Lock lock) throws IOException, GitAPIException, LostLockException {
		fetch();
		Lock remoteLock = readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class);
		if (remoteLock == null || remoteLock.epoch() != lock.epoch()) {
			throw new LostLockException(saveRecovery(lock.holder()));
		}
		writeJson(LOCK_FILE, new Lock(null, lock.epoch(), null));
		addAll();
		commit("Save: " + lock.holder().name());
		if (!push(MAIN, MAIN)) {
			throw new LostLockException(saveRecovery(lock.holder()));
		}
	}

	/**
	 * Uploads whatever the save folder has beyond the remote to a new branch, then resets to the remote.
	 * Returns the branch, or null if there was nothing to keep.
	 */
	private String saveRecovery(Player me) throws IOException, GitAPIException {
		addAll();
		if (!isClean()) {
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
		Meta meta = readJson(ORIGIN_MAIN, META_FILE, Meta.class);
		Lock lock = readJson(ORIGIN_MAIN, LOCK_FILE, Lock.class);
		Heartbeat beat = readJson("refs/remotes/origin/" + HEARTBEAT, "heartbeat.json", Heartbeat.class);
		long lastSign;
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			lastSign = walk.parseCommit(resolve(ORIGIN_MAIN)).getCommitTime() * 1000L;
		}
		if (beat != null && lock.holder() != null && beat.epoch() == lock.epoch()) {
			lastSign = Math.max(lastSign, beat.atMillis());
		}
		boolean stale = lock.holder() != null && clock.millis() - lastSign > STALE_AFTER.toMillis();
		return new Status(meta, lock, beat, stale);
	}

	private void fetch() throws GitAPIException {
		git.fetch().setRemote("origin").setRemoveDeletedRefs(true).setCredentialsProvider(remote.credentials()).call();
	}

	/** Pushes without force unless {@code src} starts with '+'. Returns false if the remote rejected it. */
	private boolean push(String src, String dst) throws GitAPIException {
		for (int attempt = 1; ; attempt++) {
			RemoteRefUpdate update = null;
			for (PushResult result : git.push()
					.setRemote("origin")
					.setRefSpecs(new RefSpec(src + ":" + dst))
					.setCredentialsProvider(remote.credentials())
					.call()) {
				if (result.getRemoteUpdate(dst) != null) update = result.getRemoteUpdate(dst);
			}
			if (update == null) return true;
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
		Ref r = git.getRepository().exactRef(ref);
		return r == null ? null : r.getObjectId();
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
			return GSON.fromJson(new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8), type);
		}
	}

	@Override
	public void close() {
		git.close();
	}
}
