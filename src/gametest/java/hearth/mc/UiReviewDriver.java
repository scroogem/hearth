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
    private HearthClient.Entry guest;
    private int memberStage;
    private hearth.core.WorldPolicy.Member originalMember;
    private String transferCode;

    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("hearth.ui.editMember")) {
            ClientTickEvents.END_CLIENT_TICK.register(mc -> checkMemberEdit(mc));
            return;
        }
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
        if (!Boolean.getBoolean("hearth.ui.review") && !Boolean.getBoolean("hearth.ui.inviteTransfer")) return;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.gui.overlay() != null) return;
            if (root == null) {
                if (!(mc.gui.screen() instanceof TitleScreen)) return;
                if (Boolean.getBoolean("hearth.ui.inviteTransfer")) root = new SharedWorldsScreen.AddWorldScreen(new TitleScreen());
                else {
                    entry = HearthClient.entries().getFirst();
                    if (Boolean.getBoolean("hearth.ui.roles")) try {
                        guest = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(HearthClient.gameDir().resolve("hearth/review-guest.json")), HearthClient.Entry.class);
                    } catch (Exception e) { throw new RuntimeException(e); }
                    root = Boolean.getBoolean("hearth.ui.membersError") ? new WorldAccessScreen(entry, new TitleScreen())
                            : new SharedWorldsScreen(new TitleScreen());
                }
                mc.gui.setScreen(root);
            }
            if (!ready(mc.gui.screen())) return;
            int period = Integer.getInteger("hearth.ui.period", 240);
            ticks++;
            if (ticks % period == period * 3 / 4) {
                var screen = mc.gui.screen();
                for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.StringWidget label
                        && label.getY() < 35 && label.getMessage().equals(screen.getTitle())
                        && label.getX() != (screen.width - label.getWidth()) / 2)
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
            if (ticks % period == 0) {
                Screen next = Boolean.getBoolean("hearth.ui.inviteTransfer") ? transferScreen(++page, mc)
                        : Boolean.getBoolean("hearth.ui.membersError") ? null : Boolean.getBoolean("hearth.ui.roles") ? roleScreen(++page, mc) : switch (++page) {
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

    private Screen transferScreen(int next, net.minecraft.client.Minecraft mc) {
        try {
            var folder = HearthClient.gameDir().resolve("hearth/invites");
            var screen = mc.gui.screen();
            switch (next) {
                case 1 -> {
                    var input = HearthClient.gameDir().resolve("hearth/review-invite.txt");
                    transferCode = hearth.core.Invite.readFile(input);
                    ((SharedWorldsScreen.AddWorldScreen) root).onFilesDrop(java.util.List.of(input));
                    var field = root.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c).findFirst().orElseThrow();
                    if (!field.getValue().equals(transferCode)) throw new AssertionError("File drop lost the invite");
                    if (root.children().stream().noneMatch(c -> c instanceof Button b && b.active && b.getMessage().getString().equals(
                            net.minecraft.network.chat.Component.translatable("hearth.add_button").getString()))) throw new AssertionError("Imported invite cannot be added");
                    mc.gui.setScreen(new WorldAccessScreen.InviteResultScreen(transferCode, "Друг с Windows", root));
                    mc.keyboardHandler.setClipboard(""); press(mc.gui.screen(), "hearth.copy_invite");
                    if (!mc.keyboardHandler.getClipboard().equals(transferCode)) throw new AssertionError("Copy lost the invite");
                    return mc.gui.screen();
                }
                case 2 -> {
                    press(screen, "hearth.save_invite_file");
                    try (var files = java.nio.file.Files.list(folder)) {
                        var exported = files.toList();
                        if (exported.size() != 1 || !hearth.core.Invite.readFile(exported.getFirst()).equals(transferCode)) throw new AssertionError("Export lost the invite");
                        if (java.nio.file.Files.getFileStore(folder).supportsFileAttributeView("posix") && !java.nio.file.Files.getPosixFilePermissions(exported.getFirst())
                                .equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))) throw new AssertionError("Export exposed private access");
                    }
                    return mc.gui.screen();
                }
                case 3 -> {
                    java.nio.file.Files.move(folder, folder.resolveSibling("invites-saved"));
                    java.nio.file.Files.writeString(folder, "simulate an unavailable destination");
                    mc.gui.setScreen(new WorldAccessScreen.InviteResultScreen(transferCode, "Друг с Windows", root));
                    press(mc.gui.screen(), "hearth.save_invite_file");
                    if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.AlertScreen)) throw new AssertionError("Export failure had no recovery");
                    return mc.gui.screen();
                }
                case 4 -> {
                    java.nio.file.Files.delete(folder);
                    java.nio.file.Files.move(folder.resolveSibling("invites-saved"), folder);
                    screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).findFirst().orElseThrow().onPress(null);
                    press(mc.gui.screen(), "hearth.save_invite_file");
                    try (var files = java.nio.file.Files.list(folder)) {
                        var exported = files.toList();
                        if (exported.size() != 2) throw new AssertionError("Export retry did not save");
                        for (var file : exported) if (!hearth.core.Invite.readFile(file).equals(transferCode)) throw new AssertionError("Export retry changed access");
                    }
                    return mc.gui.screen();
                }
                case 5 -> {
                    mc.gui.setScreen(root);
                    root.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c).findFirst().orElseThrow().setValue("existing draft");
                    var invalid = HearthClient.gameDir().resolve("hearth/invalid-invite.txt");
                    java.nio.file.Files.writeString(invalid, "not an invite");
                    ((SharedWorldsScreen.AddWorldScreen)root).onFilesDrop(java.util.List.of(invalid));
                    if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.AlertScreen)) throw new AssertionError("Invalid file had no recovery");
                    return mc.gui.screen();
                }
                case 6 -> {
                    screen.children().stream().filter(c -> c instanceof Button).map(c -> (Button)c).findFirst().orElseThrow().onPress(null);
                    if (mc.gui.screen() != root || root.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c)
                            .noneMatch(c -> c.getValue().equals("existing draft"))) throw new AssertionError("Invalid file lost the draft");
                    return root;
                }
                default -> { HearthClient.LOG.info("[ui-test] NATIVE_INVITE_FILE_EXPORT_IMPORT_AND_RECOVERY_VERIFIED"); return null; }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static boolean ready(Screen screen) {
        String action = screen instanceof SharedWorldsScreen ? "hearth.play" : screen instanceof WorldSettingsScreen ? "hearth.game_rules"
                : screen instanceof WorldAccessScreen ? "hearth.member_rights" : screen instanceof WorldHistoryScreen ? "hearth.session_details" : null;
        return action == null || screen.children().stream().anyMatch(c -> c instanceof Button b && b.active
                && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(action).getString()));
    }

    private void checkMemberEdit(net.minecraft.client.Minecraft mc) {
        try {
            Screen screen = mc.gui.screen();
            if (memberStage == 0 && screen instanceof TitleScreen) {
                entry = HearthClient.entries().getFirst(); memberStage = 1;
                HearthClient.async(() -> { try (var world = HearthClient.open(entry)) {
                    return world.policy().member(hearth.core.WorldPolicy.identity(HearthClient.owner(entry).accesses().getFirst().publicKey()));
                } }).whenComplete((member, error) -> {
                    if (error != null) throw new AssertionError(error);
                    originalMember = member; mc.gui.setScreen(new WorldAccessScreen(entry, new TitleScreen())); memberStage = 2;
                });
            } else if (memberStage == 2 && screen instanceof WorldAccessScreen && screen.children().stream().anyMatch(c -> c instanceof Button b && b.active
                    && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("hearth.member_rights").getString()))) {
                press(screen, "hearth.member_rights"); memberStage = 3;
            } else if (memberStage == 3 && screen.getClass().getSimpleName().equals("MemberScreen")) {
                screen.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c).findFirst().orElseThrow().setValue("Named through Hearth UI");
                String off = net.minecraft.network.chat.Component.translatable("hearth.operator", net.minecraft.network.chat.Component.translatable("options.off")).getString();
                screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(off)).map(c -> (Button)c).findFirst().ifPresent(b -> b.onPress(null));
                press(screen, "hearth.save_settings"); memberStage = 4;
            } else if (memberStage == 4 && screen instanceof WorldAccessScreen) {
                memberStage = 5;
                HearthClient.async(() -> { try (var world = HearthClient.open(entry)) {
                    var member = world.policy().member(originalMember.uuid());
                    if (!member.operator() || !member.name().equals("Named through Hearth UI")) throw new AssertionError("Native member edit was not signed and persisted");
                    return null;
                } }).whenComplete((value, error) -> {
                    if (error != null) throw new AssertionError(error);
                    HearthClient.LOG.info("[ui-test] NATIVE_MEMBER_RENAME_AND_OPERATOR_SAVE_VERIFIED");
                    HearthClient.updateRules(entry, policy -> policy.withMember(originalMember.publicKey(), originalMember.name(), originalMember.operator()), new TitleScreen());
                    memberStage = 6;
                });
            } else if (memberStage == 6 && screen instanceof TitleScreen) { memberStage = 7; mc.stop(); }
        } catch (Exception e) { throw new RuntimeException(e); }
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
                if (screen instanceof WorldAccessScreen.InviteScreen) {
                    screen.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c).findFirst().orElseThrow().setValue("Named friend");
                    press(screen, "hearth.create_invite"); return;
                }
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

    private Screen roleScreen(int next, net.minecraft.client.Minecraft mc) {
        return switch (next) {
            case 1 -> new WorldDetailsScreen(entry, root);
            case 2 -> new WorldSettingsScreen(entry, root);
            case 3 -> { press(mc.gui.screen(), "hearth.game_rules"); checkRuleSearch(mc.gui.screen(), false); yield mc.gui.screen(); }
            case 4 -> new WorldAccessScreen(entry, root);
            case 5 -> { press(mc.gui.screen(), "hearth.member_rights"); yield mc.gui.screen(); }
            case 6 -> new WorldAccessScreen.InviteScreen(entry, root);
            case 7 -> new WorldAccessScreen(entry, root);
            case 8 -> { press(mc.gui.screen(), "hearth.ip_bans"); yield mc.gui.screen(); }
            case 9 -> new WorldHistoryScreen(entry, root);
            case 10 -> { press(mc.gui.screen(), "hearth.session_details"); yield mc.gui.screen(); }
            case 11 -> new WorldDetailsScreen(guest, root);
            case 12 -> new WorldSettingsScreen(guest, root);
            case 13 -> {
                if (mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("hearth.save_settings").getString())))
                    throw new AssertionError("A participant can save creator settings");
                press(mc.gui.screen(), "hearth.game_rules"); checkRuleSearch(mc.gui.screen(), true); yield mc.gui.screen();
            }
            case 14 -> new SharedWorldsScreen.AddWorldScreen(root);
            case 15 -> new GitHubWorldScreen(entry.folder(), root);
            default -> null;
        };
    }

    private static void checkRuleSearch(Screen screen, boolean readOnly) {
        var input = screen.children().stream().filter(c -> c instanceof EditBox).map(c -> (EditBox)c).findFirst().orElseThrow();
        input.setValue("keep_inventory");
        if (!screen.children().contains(input)) throw new AssertionError("Search recreated the native input and lost its caret");
        var on = net.minecraft.network.chat.Component.translatable("options.on").getString();
        var off = net.minecraft.network.chat.Component.translatable("options.off").getString();
        var toggle = screen.children().stream().filter(c -> c instanceof Button b && (b.getMessage().getString().equals(on) || b.getMessage().getString().equals(off)))
                .map(c -> (Button)c).findFirst().orElseThrow();
        if (toggle.active == readOnly) throw new AssertionError("Wrong game-rule edit permission");
        if (!readOnly) {
            String previous = toggle.getMessage().getString(); toggle.onPress(null);
            if (toggle.getMessage().getString().equals(previous)) throw new AssertionError("Boolean game-rule toggle failed");
        }
        HearthClient.LOG.info("[ui-test] RULE_SEARCH_AND_BOOLEAN_PERMISSION_VERIFIED readOnly={}", readOnly);
    }

    private static void press(Screen screen, String translation) {
        screen.children().stream().filter(child -> child instanceof Button button && button.active
                        && button.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(translation).getString()))
                .map(child -> (Button) child).findFirst().orElseThrow().onPress(null);
    }
}
