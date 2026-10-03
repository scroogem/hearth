package hearth.mc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Native UI screenshots and layout checks in an ordinary Minecraft client. */
public final class UiReviewDriver implements ClientModInitializer {
    private int ticks, page;
    private HearthClient.Entry entry;
    private Screen root;
    private String createdFolder;
    private int sharingStage;

    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("hearth.ui.create")) {
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                try {
                    if (root == null && mc.gui.screen() instanceof TitleScreen) {
                        root = new TitleScreen(); HearthClient.createNewWorld(root);
                    } else if (createdFolder == null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen create) {
                        var state = create.getUiState();
                        state.setName("Native creation check"); state.setSeed("12345");
                        state.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
                        state.setDifficulty(net.minecraft.world.Difficulty.HARD);
                        state.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, true, null);
                        state.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 7, null);
                        createdFolder = state.getTargetFolder();
                        create.children().stream().filter(child -> child instanceof Button button
                                && button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("selectWorld.create").getString()))
                                .map(child -> (Button) child).findFirst().orElseThrow().onPress(null);
                    } else if (createdFolder != null && mc.gui.screen() instanceof GitHubWorldScreen) {
                        var captured = WorldRules.capture(HearthClient.savesDir().resolve(createdFolder));
                        if (!captured.gameMode().equals("creative") || !captured.difficulty().equals("hard")
                                || !"true".equals(captured.gameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY.id()))
                                || !"7".equals(captured.gameRules().get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED.id())))
                            throw new AssertionError("Native world settings were lost before sharing");
                        WorldRules.validate(captured);
                        HearthClient.LOG.info("[creation-test] NATIVE_SAVE_AND_RULES_VERIFIED");
                        if (Boolean.getBoolean("hearth.ui.share")) createdFolder = null;
                        else mc.createWorldOpenFlows().openWorld(createdFolder, mc::stop);
                    } else if (createdFolder != null && mc.level != null) {
                        HearthClient.LOG.info("[creation-test] NATIVE_WORLD_OPENED");
                        mc.disconnectWithSavingScreen(); mc.stop();
                    }
                    if (Boolean.getBoolean("hearth.ui.share")) checkSharing(mc);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            return;
        }
        if (!Boolean.getBoolean("hearth.ui.review")) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (root == null) {
                if (!(mc.gui.screen() instanceof TitleScreen)) return;
                entry = HearthClient.entries().getFirst();
                root = Boolean.getBoolean("hearth.ui.membersError") ? new WorldAccessScreen(entry, new TitleScreen())
                        : new SharedWorldsScreen(new TitleScreen());
                mc.gui.setScreen(root);
            }
            ticks++;
            if (ticks % 240 == 180) {
                var screen = mc.gui.screen();
                for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.StringWidget label
                        && label.getY() < 35 && label.getMessage().equals(screen.getTitle())
                        && label.getX() != (screen.width - mc.font.width(screen.getTitle())) / 2)
                    throw new AssertionError("Screen title is not centered");
                var controls = screen.children().stream().filter(child -> child instanceof Button || child instanceof EditBox)
                        .map(child -> (AbstractWidget) child).toList();
                for (var control : controls) {
                    if (control.getX() < 0 || control.getY() < 0 || control.getX() + control.getWidth() > screen.width
                            || control.getY() + control.getHeight() > screen.height) throw new AssertionError("Control outside screen: " + control.getMessage().getString());
                    if (control.getMessage().getString().contains("hearth.")) throw new AssertionError("Missing translation: " + control.getMessage());
                    for (var other : controls) if (other != control && control.getX() < other.getX() + other.getWidth()
                            && control.getX() + control.getWidth() > other.getX() && control.getY() < other.getY() + other.getHeight()
                            && control.getY() + control.getHeight() > other.getY()) throw new AssertionError("Overlapping controls");
                }
                Screenshot.grab(mc.gameDirectory, "hearth-ui-" + page + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                HearthClient.LOG.info("[ui-test] Captured {}", screen.getClass().getSimpleName());
            }
            if (ticks % 240 == 0) {
                Screen next = Boolean.getBoolean("hearth.ui.membersError") ? null : switch (++page) {
                    case 1 -> new WorldDetailsScreen(entry, root);
                    case 2 -> new WorldSettingsScreen(entry, root);
                    case 3 -> new WorldAccessScreen(entry, root);
                    case 4 -> new WorldHistoryScreen(entry, root);
                    case 5 -> new SharedWorldsScreen.AddWorldScreen(root);
                    case 6 -> new GitHubWorldScreen(entry.folder(), root);
                    default -> null;
                };
                if (next != null) mc.gui.setScreen(next);
                else { HearthClient.LOG.info("[ui-test] All native screens checked"); mc.stop(); }
            }
        });
    }

    private void checkSharing(net.minecraft.client.Minecraft mc) throws Exception {
        var signals = HearthClient.gameDir().resolve("hearth-creation");
        java.nio.file.Files.createDirectories(signals);
        Screen screen = mc.gui.screen();
        switch (sharingStage) {
            case 0 -> {
                if (!(screen instanceof GitHubWorldScreen)) return;
                if (!java.nio.file.Files.exists(HearthClient.accountFile())) press(screen, "hearth.github_sign_in");
                sharingStage = 1;
            }
            case 1 -> {
                if (!(screen instanceof GitHubWorldScreen)) return;
                if (!java.nio.file.Files.exists(signals.resolve("device-code.txt"))) {
                    for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.StringWidget label
                            && label.getMessage().getString().matches("[A-Z0-9]{4}-[A-Z0-9]{4}")) {
                        hearth.core.LocalSecrets.write(signals.resolve("device-code.txt"), label.getMessage().getString());
                        Screenshot.grab(mc.gameDirectory, "hearth-github-device.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                        HearthClient.LOG.info("[creation-test] DEVICE_CODE_SCREEN_READY");
                    }
                }
                if (!java.nio.file.Files.exists(HearthClient.accountFile()) || !java.nio.file.Files.exists(signals.resolve("continue-creation"))) return;
                press(screen, "hearth.github_create"); sharingStage = 2;
            }
            case 2 -> {
                var session = HearthClient.session;
                if (mc.level == null || session == null || session.joinTarget().get() == null
                        || !session.joinTarget().get().endsWith(".steam")) return;
                var settings = session.policy().get().settings();
                if (!settings.gameMode().equals("creative") || !settings.difficulty().equals("hard")
                        || !"true".equals(settings.gameRules().get("keep_inventory"))) throw new AssertionError("Shared world lost native settings");
                HearthClient.LOG.info("[creation-test] NATIVE_SHARED_WORLD_AND_STEAM_HOST_VERIFIED");
                mc.gui.setScreen(new WorldDetailsScreen(session.entry(), new SharedWorldsScreen(new TitleScreen())));
                press(mc.gui.screen(), "hearth.invite"); sharingStage = 3;
            }
            case 3 -> {
                if (screen == null || !screen.getTitle().getString().equals(net.minecraft.network.chat.Component.translatable("hearth.copied").getString())) return;
                String code = mc.keyboardHandler.getClipboard();
                var invite = hearth.core.Invite.decode(code);
                var owner = HearthClient.session.entry().remote();
                if (invite.remote().ssh().publicKey().equals(owner.ssh().publicKey())
                        || invite.remote().trust().policyAccess().publicKey().equals(owner.trust().policyAccess().publicKey())
                        || !invite.remote().trust().worldId().equals(owner.trust().worldId())) throw new AssertionError("Invite reused creator access");
                hearth.core.LocalSecrets.write(signals.resolve("world-invite.txt"), code);
                HearthClient.LOG.info("[creation-test] UI_INVITE_HAS_SEPARATE_WORLD_AND_READ_ONLY_RULE_KEYS");
                sharingStage = 4;
            }
            case 4 -> {
                if (!java.nio.file.Files.exists(signals.resolve("release-host"))) return;
                mc.level.disconnect(net.minecraft.network.chat.Component.translatable("menu.savingLevel"));
                mc.disconnectWithSavingScreen(); sharingStage = 5;
            }
            case 5 -> {
                if (HearthClient.session != null || !(screen instanceof SharedWorldsScreen)) return;
                HearthClient.LOG.info("[creation-test] SHARED_WORLD_UPLOADED_AND_RELEASED"); mc.stop(); sharingStage = 6;
            }
        }
    }

    private static void press(Screen screen, String translation) {
        screen.children().stream().filter(child -> child instanceof Button button && button.active
                        && button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(translation).getString()))
                .map(child -> (Button) child).findFirst().orElseThrow().onPress(null);
    }
}
