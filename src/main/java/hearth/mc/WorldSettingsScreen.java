package hearth.mc;

import hearth.core.WorldPolicy;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRule;

import java.util.List;
import java.util.TreeMap;

final class WorldSettingsScreen extends Screen {
    private final HearthClient.Entry entry;
    private final Screen back;
    private WorldPolicy.Settings settings;
    private boolean loading;

    WorldSettingsScreen(HearthClient.Entry entry, Screen back) {
        super(Component.translatable("hearth.settings"));
        this.entry = entry;
        this.back = back;
    }

    @Override protected void init() {
        int x = width / 2 - 150;
        addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
        if (settings == null) {
            addRenderableWidget(new StringWidget(x, 48, 300, 20, Component.translatable("hearth.checking"), font));
            if (!loading) {
                loading = true;
                HearthClient.async(() -> { try (var world = HearthClient.open(entry)) { return world.policy().settings(); } }).whenComplete((value, error) -> {
                    loading = false;
                    if (minecraft.gui.screen() != this) return;
                    if (error != null) minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(back), Component.translatable("hearth.error"), Component.literal(String.valueOf(error.getMessage()))));
                    else { settings = value; if (minecraft.gui.screen() == this) rebuildWidgets(); }
                });
            }
        } else {
            addRenderableWidget(Button.builder(Component.translatable("hearth.default_mode", Component.translatable("gameMode." + settings.gameMode())), b -> {
                var modes = List.of("survival", "creative", "adventure", "spectator");
                String mode = modes.get((modes.indexOf(settings.gameMode()) + 1) % modes.size());
                settings = new WorldPolicy.Settings(mode, settings.difficulty(), settings.allowCreative() || mode.equals("creative"), settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules());
                rebuildWidgets();
            }).bounds(x, 40, 300, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("hearth.difficulty", Component.translatable("options.difficulty." + settings.difficulty())), b -> {
                var values = List.of("peaceful", "easy", "normal", "hard");
                settings = new WorldPolicy.Settings(settings.gameMode(), values.get((values.indexOf(settings.difficulty()) + 1) % values.size()), settings.allowCreative(), settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules());
                rebuildWidgets();
            }).bounds(x, 64, 300, 20).build());
            toggle(x, 88, "hearth.allow_creative", settings.allowCreative(), () -> {
                boolean enabled = !settings.allowCreative();
                settings = new WorldPolicy.Settings(!enabled && settings.gameMode().equals("creative") ? "survival" : settings.gameMode(), settings.difficulty(), enabled, settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules());
            });
            toggle(x, 112, "hearth.allow_mode_changes", settings.allowModeChanges(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), !settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules()));
            toggle(x, 136, "hearth.allow_client_mods", settings.allowClientMods(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), settings.allowModeChanges(), !settings.allowClientMods(), settings.cheats(), settings.gameRules()));
            toggle(x, 160, "hearth.cheats", settings.cheats(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), settings.allowModeChanges(), settings.allowClientMods(), !settings.cheats(), settings.gameRules()));
            addRenderableWidget(Button.builder(Component.translatable("hearth.game_rules"), b -> minecraft.gui.setScreen(new RulesScreen(this))).bounds(x, 184, 146, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("hearth.approve_mods"), b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                if (yes) HearthClient.updateRules(entry, policy -> policy.withMods(ModManifest.current()), this);
                else minecraft.gui.setScreen(this);
            }, Component.translatable("hearth.approve_mods"), Component.translatable("hearth.approve_mods_warning")))).bounds(x + 154, 184, 146, 20).build());
        }
        Button save = addRenderableWidget(Button.builder(Component.translatable("hearth.save_settings"), b -> HearthClient.updateRules(entry, policy -> policy.withSettings(settings), back)).bounds(x, height - 28, 146, 20).build());
        save.active = settings != null;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 154, height - 28, 146, 20).build());
    }

    private void toggle(int x, int y, String label, boolean value, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(label, Component.translatable(value ? "options.on" : "options.off")), b -> { action.run(); rebuildWidgets(); }).bounds(x, y, 300, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(back); }

    private static final class RulesScreen extends Screen {
        private final WorldSettingsScreen back;
        private final List<GameRule<?>> rules = WorldRules.available().availableRules().sorted(java.util.Comparator.comparing(GameRule::id)).toList();
        private int page;

        RulesScreen(WorldSettingsScreen back) { super(Component.translatable("hearth.game_rules")); this.back = back; }
        private int pageSize() { return Math.max(1, (height - 104) / 32); }

        @Override protected void init() {
            int x = width / 2 - 150, y = 40;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            int from = Math.min(page * pageSize(), rules.size());
            for (var rule : rules.subList(from, Math.min(rules.size(), from + pageSize()))) {
                addRenderableWidget(new StringWidget(x, y, 208, 20, Component.literal(rule.id()), font).setMaxWidth(208));
                EditBox value = addRenderableWidget(new EditBox(font, x + 212, y, 88, 20, Component.literal(rule.id())));
                value.setMaxLength(11);
                value.setValue(back.settings.gameRules().getOrDefault(rule.id(), rule.defaultValue().toString()));
                value.setResponder(text -> {
                    var changed = new TreeMap<>(back.settings.gameRules()); changed.put(rule.id(), text);
                    var s = back.settings;
                    back.settings = new WorldPolicy.Settings(s.gameMode(), s.difficulty(), s.allowCreative(), s.allowModeChanges(), s.allowClientMods(), s.cheats(), changed);
                    value.setTextColor(rule.deserialize(text).result().isPresent() ? 0xFFFFFFFF : 0xFFFF5555);
                });
                y += 32;
            }
            Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 56, 146, 20).build());
            previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 56, 146, 20).build());
            next.active = (page + 1) * pageSize() < rules.size();
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        }
        @Override public void onClose() { minecraft.gui.setScreen(back); }
    }
}
