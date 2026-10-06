package hearth.mc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks signed roles, actual commands and the connected friend's native menus. */
public final class RoleReviewDriver implements ClientModInitializer {
    private boolean pending;
    private String checked;
    private long ticks;
    @Override public void onInitializeClient() {
        String directory = System.getProperty("hearth.roles.dir");
        if (directory == null) return;
        Path signals = Path.of(directory);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ticks++;
            try {
                var session = HearthClient.session;
                if (session != null && mc.level != null && mc.getSingleplayerServer() != null
                        && mc.getSingleplayerServer().getPlayerList().getPlayers().stream().anyMatch(p -> !p.getUUID().equals(session.policy().get().ownerUuid()))
                        && Files.exists(signals.resolve("check-role")) && !pending) {
                    String phase = Files.readString(signals.resolve("check-role")).strip();
                    Files.delete(signals.resolve("check-role")); pending = true;
                    HearthClient.async(() -> session.world().policy()).whenComplete((policy, error) -> {
                        if (error != null) throw new AssertionError("Could not refresh signed roles", error);
                        session.policy().set(policy);
                        mc.getSingleplayerServer().execute(() -> {
                            var server = mc.getSingleplayerServer();
                            var player = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(policy.ownerUuid())).findFirst().orElseThrow();
                            boolean operator = phase.equals("operator");
                            var expected = operator || phase.equals("selfmode") ? LevelBasedPermissionSet.GAMEMASTER : LevelBasedPermissionSet.ALL;
                            var profile = new net.minecraft.server.players.NameAndId(player.getUUID(), player.getGameProfile().name());
                            if (!server.getProfilePermissions(profile).equals(expected)) throw new AssertionError("Wrong role permissions: " + phase);
                            int before = diamonds(player);
                            var source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
                            server.getCommands().performPrefixedCommand(source, "give @s diamond");
                            if (diamonds(player) != before + (operator ? 1 : 0)) throw new AssertionError("Give bypassed signed role: " + phase);
                            if (operator) server.getCommands().performPrefixedCommand(source, "clear @s diamond 1");
                            server.getCommands().performPrefixedCommand(source, "execute run give @s diamond");
                            if (diamonds(player) != before + (operator ? 1 : 0)) throw new AssertionError("Execute bypassed signed role");
                            if (operator) server.getCommands().performPrefixedCommand(source, "clear @s diamond 1");
                            server.getCommands().performPrefixedCommand(source, "gamerule keep_inventory true");
                            if (server.getGameRules().get(GameRules.KEEP_INVENTORY)) throw new AssertionError("A command changed signed game rules");
                            server.getCommands().performPrefixedCommand(source, "gamemode creative");
                            if (player.gameMode.getGameModeForPlayer() == GameType.CREATIVE) throw new AssertionError("A role bypassed creative restriction");
                            server.getCommands().performPrefixedCommand(source, "gamemode adventure");
                            if ((player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) != phase.equals("selfmode")) throw new AssertionError("Wrong self-mode permission");
                            if (phase.equals("selfmode")) {
                                var creator = server.getPlayerList().getPlayers().stream().filter(p -> p.getUUID().equals(policy.ownerUuid())).findFirst().orElseThrow();
                                var prior = creator.gameMode.getGameModeForPlayer();
                                server.getCommands().performPrefixedCommand(source, "gamemode adventure " + creator.getGameProfile().name());
                                if (creator.gameMode.getGameModeForPlayer() != prior) throw new AssertionError("A participant changed another player's mode");
                            }
                            server.getCommands().sendCommands(player);
                            try { Files.writeString(signals.resolve("ready-role"), phase); } catch (Exception e) { throw new RuntimeException(e); }
                            HearthClient.LOG.info("[role-test] SERVER_ROLE_AND_COMMANDS_VERIFIED {}", phase);
                            pending = false;
                        });
                    });
                }
                if (mc.level != null && mc.getConnection() != null && ticks % 40 == 0 && Files.exists(signals.resolve("ready-role"))) {
                    String phase = Files.readString(signals.resolve("ready-role")).strip();
                    if (phase.equals(checked)) return;
                    var entry = session != null ? session.entry() : HearthClient.joiningEntry;
                    if (entry == null || HearthClient.isOwner(entry)) return;
                    var commands = mc.getConnection().getCommands().getRoot();
                    if ((commands.getChild("give") != null) != phase.equals("operator")) throw new AssertionError("Forbidden command appears in suggestions: " + phase);
                    if (commands.getChild("gamerule") != null || commands.getChild("difficulty") != null) throw new AssertionError("Signed settings commands appear in suggestions");
                    if ((commands.getChild("gamemode") != null) != (phase.equals("operator") || phase.equals("selfmode"))) throw new AssertionError("Wrong mode suggestions");
                    var pause = new PauseScreen(true); mc.gui.setScreen(pause);
                    pause.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(Component.translatable("hearth.shared_worlds").getString()))
                            .map(c -> (Button)c).findFirst().orElseThrow().onPress(null);
                    var details = mc.gui.screen();
                    if (details.children().stream().anyMatch(c -> c instanceof Button b && (b.getMessage().getString().equals(Component.translatable("hearth.invite").getString())
                            || b.getMessage().getString().equals(Component.translatable("hearth.access").getString())))) throw new AssertionError("Participant sees creator actions");
                    mc.gui.setScreen(null); checked = phase;
                    Files.writeString(signals.resolve("client-verified-" + phase), "ok");
                    HearthClient.LOG.info("[role-test] COMMAND_SUGGESTIONS_AND_PARTICIPANT_PAUSE_MENU_VERIFIED {}", phase);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static int diamonds(net.minecraft.server.level.ServerPlayer player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var item = player.getInventory().getItem(i); if (item.is(Items.DIAMOND)) total += item.getCount();
        }
        return total;
    }
}
