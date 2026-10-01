package hearth.mc;

import com.google.gson.JsonParser;
import hearth.core.SharedWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Drives the real client: share a world, host it, leave, and check what landed in the remote repo. */
public final class HearthGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {

		try {
			run(ctx);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void run(ClientGameTestContext ctx) throws Exception {
		Path worldDir;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
					.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7)));
			worldDir = sp.getWorldSave().getSaveDirectory();
		}
		String folder = worldDir.getFileName().toString();
		Path origin = Files.createTempDirectory("hearth-origin").resolve("world.git");
		Git.init().setBare(true).setInitialBranch("main").setDirectory(origin.toFile()).call().close();
		SharedWorld.Remote remote = new SharedWorld.Remote(origin.toString(), "git", "");

		// Singleplayer → Shared worlds → Add a world → share the world we just made
		ctx.setScreen(() -> new SelectWorldScreen(new TitleScreen()));
		ctx.clickScreenButton("hearth.shared_worlds");
		ctx.waitForScreen(SharedWorldsScreen.class);
		ctx.clickScreenButton("hearth.add");
		ctx.waitForScreen(SharedWorldsScreen.AddWorldScreen.class);
		ctx.runOnClient(mc -> HearthClient.createFrom(folder, remote, new SharedWorldsScreen(new TitleScreen())));
		ctx.waitFor(mc -> mc.gui.screen() instanceof SharedWorldsScreen, 20 * 120);
		check(commits(origin).size() == 1, "world uploaded");

		// Host: wait for the status to load, press the button, play for a few seconds
		clickWhenShown(ctx, "hearth.play");
		ctx.waitFor(mc -> mc.level != null && mc.player != null, 20 * 120);
		ctx.waitTicks(100);
		check(ctx.computeOnClient(mc -> mc.player.getUUID().equals(SteamBridge.localUuid())), "host uses the guest-compatible Steam UUID");
		check(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND)
				&& mc.player.getInventory().getItem(0).getCount() == 7), "singleplayer inventory migrated to Steam profile");
		ctx.runOnClient(mc -> {
			var server = mc.getSingleplayerServer();
			String name = mc.getUser().getName();
			server.execute(() -> {
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + name + " minecraft:diamond 4");
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp " + name + " 12.5 -60 12.5");
			});
		});
		ctx.waitFor(mc -> mc.player.getInventory().getItem(0).getCount() == 11
				&& Math.abs(mc.player.getX() - 12.5) < 0.1 && Math.abs(mc.player.getZ() - 12.5) < 0.1);
		check(lockHolder(origin) != null, "lock is held while playing");
		ctx.waitFor(mc -> HearthClient.session != null && HearthClient.session.joinTarget().get() != null, 20 * 120);
		check(HearthClient.session.joinTarget().get().endsWith(".steam"), "Steam join address is available");
		SharedWorld.Status hosted = HearthClient.GIT.submit(() -> HearthClient.session.world().status()).get(30, TimeUnit.SECONDS);
		check(hosted.joinTarget() != null && hosted.joinTarget().endsWith(".steam"), "Steam join address is published for guests");
		var checkpoint = HearthClient.GIT.submit(() -> {
			try { HearthClient.checkpoint(HearthClient.session); }
			catch (Exception e) { throw new RuntimeException(e); }
		});
		ctx.waitFor(mc -> checkpoint.isDone(), 20 * 90);
		checkpoint.get(1, TimeUnit.SECONDS);
		check(commits(origin).getFirst().startsWith("Checkpoint:"), "running world checkpoint published");
		check(lockHolder(origin) != null, "checkpoint retains the active session");
		ctx.takeScreenshot("hearth-hosting");

		// Leave the world: Hearth saves and releases
		ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
		ctx.waitFor(mc -> HearthClient.session == null && mc.gui.screen() instanceof SharedWorldsScreen, 20 * 120);
		ctx.waitTicks(40);
		ctx.takeScreenshot("hearth-shared-worlds");
		List<String> log = commits(origin);
		check(log.size() == 4 && log.get(0).startsWith("Save:") && log.get(1).startsWith("Checkpoint:"), "create, host, checkpoint, save: " + log);
		check(lockHolder(origin) == null, "lock released");

		// Opening the shared world from the vanilla list must not bypass the lock
		ctx.runOnClient(mc -> mc.createWorldOpenFlows().openWorld(folder, () -> {}));
		ctx.waitForScreen(net.minecraft.client.gui.screens.AlertScreen.class);
		check(ctx.computeOnClient(mc -> mc.level == null && mc.getSingleplayerServer() == null), "vanilla open blocked before server launch");
		check(commits(origin).size() == 4, "vanilla open did not touch the remote");
		ctx.waitTicks(40); // the server thread finishes saving after Minecraft clears its client reference

		// Reopening must prefer the saved Steam profile over the older singleplayer backup.
		ctx.setScreen(() -> new SharedWorldsScreen(new TitleScreen()));
		clickWhenShown(ctx, "hearth.play");
		ctx.waitFor(mc -> mc.level != null && mc.player != null);
		ctx.waitTicks(40);
		check(ctx.computeOnClient(mc -> mc.player.getUUID().equals(SteamBridge.localUuid())
				&& mc.player.getInventory().getItem(0).is(Items.DIAMOND)
				&& mc.player.getInventory().getItem(0).getCount() == 11), "Steam inventory survives the next host session");
		check(ctx.computeOnClient(mc -> Math.abs(mc.player.getX() - 12.5) < 0.1 && Math.abs(mc.player.getZ() - 12.5) < 0.1),
				"Steam player position survives the next host session");
		ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
		ctx.waitFor(mc -> HearthClient.session == null && mc.gui.screen() instanceof SharedWorldsScreen, 20 * 120);
		ctx.waitTicks(40);
		check(lockHolder(origin) == null, "lock released after reopening");
		ctx.setScreen(TitleScreen::new);
	}

	private static void clickWhenShown(ClientGameTestContext ctx, String key) {
		for (int i = 0; i < 600; i++) {
			if (ctx.tryClickScreenButton(key)) return;
			ctx.waitTicks(5);
		}
		throw new AssertionError("No button " + key);
	}

	private static List<String> commits(Path origin) throws Exception {
		List<String> messages = new ArrayList<>();
		try (Git git = Git.open(origin.toFile())) {
			for (RevCommit c : git.log().call()) messages.add(c.getShortMessage());
		}
		return messages;
	}

	private static String lockHolder(Path origin) throws Exception {
		try (Git git = Git.open(origin.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
			Repository repo = git.getRepository();
			RevCommit head = walk.parseCommit(repo.resolve("refs/heads/main"));
			try (TreeWalk tree = TreeWalk.forPath(repo, ".hearth/lock.json", head.getTree())) {
				var holder = JsonParser.parseString(new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8))
						.getAsJsonObject().get("holder");
				return holder == null || holder.isJsonNull() ? null : holder.getAsJsonObject().get("name").getAsString();
			}
		}
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("Failed: " + what);
		HearthClient.LOG.info("[gametest] ok: {}", what);
	}
}
