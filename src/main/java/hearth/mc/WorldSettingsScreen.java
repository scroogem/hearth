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
    private final boolean readOnly;

    WorldSettingsScreen(HearthClient.Entry entry, Screen back) {
        super(Component.translatable(HearthClient.isOwner(entry) ? "hearth.settings" : "hearth.rules_view"));
        this.entry = entry;
        this.back = back;
        readOnly = !HearthClient.isOwner(entry);
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
            }).bounds(x, 40, 300, 20).build()).active = !readOnly;
            addRenderableWidget(Button.builder(Component.translatable("hearth.difficulty", Component.translatable("options.difficulty." + settings.difficulty())), b -> {
                var values = List.of("peaceful", "easy", "normal", "hard");
                settings = new WorldPolicy.Settings(settings.gameMode(), values.get((values.indexOf(settings.difficulty()) + 1) % values.size()), settings.allowCreative(), settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules());
                rebuildWidgets();
            }).bounds(x, 64, 300, 20).build()).active = !readOnly;
            toggle(x, 88, "hearth.allow_creative", settings.allowCreative(), () -> {
                boolean enabled = !settings.allowCreative();
                settings = new WorldPolicy.Settings(!enabled && settings.gameMode().equals("creative") ? "survival" : settings.gameMode(), settings.difficulty(), enabled, settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules());
            });
            toggle(x, 112, "hearth.allow_mode_changes", settings.allowModeChanges(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), !settings.allowModeChanges(), settings.allowClientMods(), settings.cheats(), settings.gameRules()));
            toggle(x, 136, "hearth.allow_client_mods", settings.allowClientMods(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), settings.allowModeChanges(), !settings.allowClientMods(), settings.cheats(), settings.gameRules()));
            toggle(x, 160, "hearth.cheats", settings.cheats(), () -> settings = new WorldPolicy.Settings(settings.gameMode(), settings.difficulty(), settings.allowCreative(), settings.allowModeChanges(), settings.allowClientMods(), !settings.cheats(), settings.gameRules()));
            addRenderableWidget(Button.builder(Component.translatable("hearth.game_rules"), b -> minecraft.gui.setScreen(new RulesScreen(this))).bounds(x, 184, 146, 20).build());
            if (!readOnly) addRenderableWidget(Button.builder(Component.translatable("hearth.approve_mods"), b -> minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
                if (yes) HearthClient.updateRules(entry, policy -> policy.withMods(ModManifest.current()), this);
                else minecraft.gui.setScreen(this);
            }, Component.translatable("hearth.approve_mods"), Component.translatable("hearth.approve_mods_warning")))).bounds(x + 154, 184, 146, 20).build());
        }
        if (readOnly) addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        else {
            Button save = addRenderableWidget(Button.builder(Component.translatable("hearth.save_settings"), b -> HearthClient.updateRules(entry, policy -> policy.withSettings(settings), back, this)).bounds(x, height - 28, 146, 20).build());
            try { if (settings == null) save.active = false; else WorldRules.validate(settings); }
            catch (java.io.IOException e) { save.active = false; save.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("hearth.invalid_rules"))); }
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 154, height - 28, 146, 20).build());
        }
    }

    private void toggle(int x, int y, String label, boolean value, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(label, Component.translatable(value ? "options.on" : "options.off")), b -> { action.run(); rebuildWidgets(); })
                .bounds(x, y, 300, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(label + "_help"))).build()).active = !readOnly;
    }

    @Override public void onClose() { minecraft.gui.setScreen(back); }

    private static final class RulesScreen extends Screen {
        private final WorldSettingsScreen back;
        private final List<GameRule<?>> rules = WorldRules.available().availableRules().sorted(java.util.Comparator.comparing(GameRule::id)).toList();
        private int page;
        private EditBox search;

        RulesScreen(WorldSettingsScreen back) { super(Component.translatable("hearth.game_rules")); this.back = back; }
        private int pageSize() { return Math.max(1, (height - 136) / 32); }

        @Override protected void init() {
            int x = width / 2 - 150, y = 72;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            if (search == null) {
                search = new EditBox(font, x, 40, 300, 20, Component.translatable("hearth.find_rule"));
                search.setMaxLength(100); search.setHint(Component.translatable("hearth.find_rule"));
                search.setResponder(value -> { page = 0; rebuildWidgets(); setFocused(search); });
            }
            search.setX(x); addRenderableWidget(search);
            String query = search.getValue();
            var filtered = rules.stream().filter(rule -> (rule.id() + " " + Component.translatable(rule.getDescriptionId()).getString())
                    .toLowerCase(java.util.Locale.ROOT).contains(query.strip().toLowerCase(java.util.Locale.ROOT))).toList();
            int from = Math.min(page * pageSize(), filtered.size());
            for (var rule : filtered.subList(from, Math.min(filtered.size(), from + pageSize()))) {
                Component label = Component.translatable(rule.getDescriptionId());
                var labelWidget = addRenderableWidget(new StringWidget(x, y, 208, 20, label, font).setMaxWidth(208));
                labelWidget.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label));
                String current = back.settings.gameRules().getOrDefault(rule.id(), rule.defaultValue().toString());
                if (rule.defaultValue() instanceof Boolean) {
                    Button toggle = addRenderableWidget(Button.builder(Component.translatable(Boolean.parseBoolean(current) ? "options.on" : "options.off"), b -> {
                        boolean enabled = !Boolean.parseBoolean(back.settings.gameRules().getOrDefault(rule.id(), rule.defaultValue().toString()));
                        updateRule(rule, Boolean.toString(enabled));
                        b.setMessage(Component.translatable(enabled ? "options.on" : "options.off"));
                    }).bounds(x + 212, y, 88, 20).build());
                    toggle.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label));
                    toggle.active = !back.readOnly;
                    y += 32;
                    continue;
                }
                EditBox value = addRenderableWidget(new EditBox(font, x + 212, y, 88, 20, label));
                value.setMaxLength(11);
                value.setValue(current);
                value.setEditable(!back.readOnly);
                value.setTextColor(rule.deserialize(value.getValue()).result().isPresent() ? 0xFFFFFFFF : 0xFFFF5555);
                value.setResponder(text -> {
                    updateRule(rule, text);
                    value.setTextColor(rule.deserialize(text).result().isPresent() ? 0xFFFFFFFF : 0xFFFF5555);
                });
                y += 32;
            }
            if (filtered.isEmpty()) addRenderableWidget(new StringWidget(x, 72, 300, 20, Component.translatable("hearth.rules_not_found"), font));
            Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 56, 146, 20).build());
            previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 56, 146, 20).build());
            next.active = (page + 1) * pageSize() < filtered.size();
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        }
        private void updateRule(GameRule<?> rule, String value) {
            var changed = new TreeMap<>(back.settings.gameRules()); changed.put(rule.id(), value);
            var s = back.settings;
            back.settings = new WorldPolicy.Settings(s.gameMode(), s.difficulty(), s.allowCreative(), s.allowModeChanges(), s.allowClientMods(), s.cheats(), changed);
        }
        @Override public void onClose() { minecraft.gui.setScreen(back); }
    }
}
