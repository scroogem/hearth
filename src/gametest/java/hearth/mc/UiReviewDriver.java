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
                        mc.createWorldOpenFlows().openWorld(createdFolder, mc::stop);
                    } else if (createdFolder != null && mc.level != null) {
                        HearthClient.LOG.info("[creation-test] NATIVE_WORLD_OPENED");
                        mc.disconnectWithSavingScreen(); mc.stop();
                    }
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
}
