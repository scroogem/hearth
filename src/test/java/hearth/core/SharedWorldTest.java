package hearth.core;

import hearth.core.SharedWorld.Lock;
import hearth.core.SharedWorld.Player;
import hearth.core.SharedWorld.Remote;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class SharedWorldTest {
	static final Player ALICE = new Player("steam:1", "Alice");
	static final Player BOB = new Player("steam:2", "Bob");

	@TempDir
	Path tmp;
	Remote remote;

	SharedWorld aliceCreates() throws Exception {
		Path origin = tmp.resolve("origin.git");
		Git.init().setBare(true).setInitialBranch("main").setDirectory(origin.toFile()).call().close();
		remote = new Remote(origin.toString(), "", "");
		Path world = tmp.resolve("alice/saves/World");
		Files.createDirectories(world.resolve("region"));
		Files.write(world.resolve("level.dat"), new byte[]{1, 2, 3});
		Files.write(world.resolve("region/r.0.0.mca"), new byte[]{10, 0, 13, 10}); // binary with \r\n
		Files.writeString(world.resolve("session.lock"), "x");
		return SharedWorld.create(tmp.resolve("alice/repo.git"), world, remote, new SharedWorld.Meta("World", "26.2"));
	}

	SharedWorld bobClones() throws Exception {
		Invite invite = Invite.decode(new Invite("World", remote).encode());
		return SharedWorld.cloneInto(tmp.resolve("bob/repo.git"), tmp.resolve("bob/saves/World"), invite.remote());
	}

	@Test
	void worldMovesBetweenHostsWithDeletes() throws Exception {
		try (SharedWorld alice = aliceCreates(); SharedWorld bob = bobClones()) {
			Path bw = bob.worldDir();
			assertArrayEquals(new byte[]{10, 0, 13, 10}, Files.readAllBytes(bw.resolve("region/r.0.0.mca")));
			assertFalse(Files.exists(bw.resolve("session.lock")), "session.lock must not sync");

			Lock lock = alice.acquire(ALICE, false);
			assertEquals(1, lock.epoch());
			assertEquals("Alice", bob.status().lock().holder().name());
			assertThrows(SharedWorld.WorldBusyException.class, () -> bob.acquire(BOB, true), "live host can't be taken over");

			Path aw = alice.worldDir();
			Files.write(aw.resolve("region/r.1.0.mca"), new byte[]{7});
			Files.delete(aw.resolve("level.dat"));
			alice.release(lock);

			Files.write(bw.resolve("stray.dat"), new byte[]{9}); // a file only Bob had, not part of any version
			Lock bobLock = bob.acquire(BOB, false);
			assertEquals(2, bobLock.epoch());
			assertArrayEquals(new byte[]{7}, Files.readAllBytes(bw.resolve("region/r.1.0.mca")));
			assertFalse(Files.exists(bw.resolve("level.dat")), "deletions must sync");
			assertFalse(Files.exists(bw.resolve("stray.dat")), "local extras must not leak into the world");
			assertTrue(bob.status().joinTarget() == null);
			bob.heartbeat(bobLock, "iw_123");
			assertEquals("iw_123", alice.status().joinTarget());
		}
	}

	@Test
	void onlyOneOfTwoSimultaneousHostsWins() throws Exception {
		try (SharedWorld alice = aliceCreates(); SharedWorld bob = bobClones()) {
			ExecutorService pool = Executors.newFixedThreadPool(2);
			for (int round = 0; round < 5; round++) {
				List<Callable<Lock>> tries = List.of(() -> alice.acquire(ALICE, false), () -> bob.acquire(BOB, false));
				List<Lock> won = new ArrayList<>();
				for (Future<Lock> f : pool.invokeAll(tries)) {
					try {
						won.add(f.get());
					} catch (Exception e) {
						assertInstanceOf(SharedWorld.WorldBusyException.class, e.getCause(), String.valueOf(e.getCause()));
					}
				}
				assertEquals(1, won.size(), "exactly one host per round");
				(won.get(0).holder() == ALICE ? alice : bob).release(won.get(0));
			}
			pool.shutdown();
		}
	}

	@Test
	void staleHostIsTakenOverAndItsLateSaveGoesToRecovery() throws Exception {
		try (SharedWorld alice = aliceCreates()) {
			Lock aliceLock = alice.acquire(ALICE, false);
			bobClones().close();
			Clock later = Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
			try (SharedWorld bob = SharedWorld.open(tmp.resolve("bob/repo.git"), tmp.resolve("bob/saves/World"), remote, later)) {
				assertTrue(bob.status().stale());
				assertThrows(SharedWorld.WorldBusyException.class, () -> bob.acquire(BOB, false), "takeover needs consent");
				Lock bobLock = bob.acquire(BOB, true);

				Files.write(alice.worldDir().resolve("region/r.0.0.mca"), new byte[]{42});
				SharedWorld.LostLockException lost = assertThrows(SharedWorld.LostLockException.class, () -> alice.release(aliceLock));
				assertNotNull(lost.recoveryBranch);
				assertArrayEquals(new byte[]{10, 0, 13, 10}, Files.readAllBytes(alice.worldDir().resolve("region/r.0.0.mca")),
						"alice's folder is back to the current version");

				bob.release(bobLock);
				try (Git origin = Git.open(tmp.resolve("origin.git").toFile())) {
					assertNotNull(origin.getRepository().exactRef("refs/heads/" + lost.recoveryBranch),
							"alice's late progress is kept on the remote");
				}
			}
		}
	}

	@Test
	void crashedHostResumesFromItsLocalFiles() throws Exception {
		Path gitDir;
		Path world;
		try (SharedWorld alice = aliceCreates()) {
			alice.acquire(ALICE, false);
			Files.write(alice.worldDir().resolve("region/r.0.0.mca"), new byte[]{5});
			gitDir = tmp.resolve("alice/repo.git");
			world = alice.worldDir();
		} // PC dies here: no release
		try (SharedWorld alice = SharedWorld.open(gitDir, world, remote); SharedWorld bob = bobClones()) {
			assertThrows(SharedWorld.WorldBusyException.class, () -> bob.acquire(BOB, false));
			Lock lock = alice.acquire(ALICE, false);
			assertArrayEquals(new byte[]{5}, Files.readAllBytes(world.resolve("region/r.0.0.mca")), "progress kept");
			alice.release(lock);
			bob.acquire(BOB, false);
			assertArrayEquals(new byte[]{5}, Files.readAllBytes(bob.worldDir().resolve("region/r.0.0.mca")));
		}
	}
}
