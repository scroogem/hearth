package hearth.mc;

import hearth.core.SharedWorld;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

final class WorldHistoryScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
    private final HearthClient.Entry entry;
    private final Screen back;
    private List<SharedWorld.History> saves;
    private int page;
    private boolean loading;
    private String error;

    WorldHistoryScreen(HearthClient.Entry entry, Screen back) {
        super(Component.translatable("hearth.history"));
        this.entry = entry;
        this.back = back;
    }

    private int pageSize() { return Math.max(1, (height - 108) / 44); }

    private void load() {
        if (loading) return;
        loading = true;
        int expected = page, count = pageSize();
        HearthClient.async(() -> { try (var world = HearthClient.open(entry)) { return world.history(expected * count, count + 1); } })
                .whenComplete((result, failure) -> {
                    loading = false;
                    if (page != expected) return;
                    if (failure != null) error = String.valueOf(failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage());
                    else { saves = result; error = null; }
                    if (minecraft.gui.screen() == this) rebuildWidgets();
                });
    }

    @Override protected void init() {
        int x = width / 2 - 150, y = 40;
        addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
        if (saves == null && error == null) load();
        if (saves == null) {
            addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable(error == null ? "hearth.checking" : "hearth.history_failed"), font));
            if (error != null) addRenderableWidget(Button.builder(Component.translatable("hearth.retry"), b -> { error = null; load(); rebuildWidgets(); }).bounds(x, y + 24, 300, 20).build());
        } else {
            for (var save : saves.stream().limit(pageSize()).toList()) {
                String who = save.activity() == null || save.activity().host() == null ? save.description() : save.activity().host().name();
                addRenderableWidget(new StringWidget(x, y, 220, 12, Component.literal(DATE.format(Instant.ofEpochMilli(save.atMillis())) + " · " + who), font).setMaxWidth(220));
                String players = save.activity() == null || save.activity().visits() == null ? save.commit().substring(0, 8)
                        : save.activity().visits().stream().filter(v -> v != null && v.player() != null).map(v -> v.player().name()).distinct().collect(Collectors.joining(", "));
                addRenderableWidget(new StringWidget(x, y + 15, 220, 12, Component.literal(players), font).setMaxWidth(220));
                addRenderableWidget(Button.builder(Component.translatable("hearth.session_details"), b -> minecraft.gui.setScreen(new SessionScreen(save)))
                        .bounds(x + 224, y, 76, 20).build());
                y += 44;
            }
            if (saves.isEmpty()) addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable("hearth.history_empty"), font));
        }
        Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; saves = null; error = null; rebuildWidgets(); }).bounds(x, height - 56, 146, 20).build());
        previous.active = !loading && page > 0;
        Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; saves = null; error = null; rebuildWidgets(); }).bounds(x + 154, height - 56, 146, 20).build());
        next.active = !loading && saves != null && saves.size() > pageSize();
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(back); }

    private final class SessionScreen extends Screen {
        private final SharedWorld.History save;
        private int page;
        SessionScreen(SharedWorld.History save) { super(Component.translatable("hearth.session_details")); this.save = save; }
        @Override protected void init() {
            int x = width / 2 - 150, y = 56;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            addRenderableWidget(new StringWidget(x, 34, 300, 12, Component.literal(DATE.format(Instant.ofEpochMilli(save.atMillis())) + " · " + save.description()), font).setMaxWidth(300));
            var visits = save.activity() == null || save.activity().visits() == null ? List.<SharedWorld.Visit>of() : save.activity().visits();
            int count = Math.max(1, (height - 160) / 36), from = page * count;
            for (var visit : visits.subList(Math.min(from, visits.size()), Math.min(visits.size(), from + count))) {
                if (visit == null || visit.player() == null) continue;
                String end = visit.leftAt() > 0 ? DATE.format(Instant.ofEpochMilli(visit.leftAt()))
                        : save.activity().endedAt() > 0 ? DATE.format(Instant.ofEpochMilli(save.activity().endedAt())) : "…";
                addRenderableWidget(new StringWidget(x, y, 300, 12, Component.literal(visit.player().name() + " · " + DATE.format(Instant.ofEpochMilli(visit.joinedAt())) + " — " + end), font).setMaxWidth(300));
                if (HearthClient.isOwner(entry)) addRenderableWidget(new StringWidget(x, y + 14, 300, 12, Component.literal(visit.ip() == null ? visit.player().id() : visit.ip()), font).setMaxWidth(300));
                y += 36;
            }
            Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 84, 146, 20).build()); previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 84, 146, 20).build()); next.active = from + count < visits.size();
            if (HearthClient.isOwner(entry)) addRenderableWidget(Button.builder(Component.translatable("hearth.restore"), b -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                if (yes) HearthClient.restoreSave(entry, save.commit(), new WorldHistoryScreen(entry, back));
                else minecraft.gui.setScreen(this);
            }, Component.translatable("hearth.restore"), Component.translatable("hearth.restore_warning", DATE.format(Instant.ofEpochMilli(save.atMillis())))))).bounds(x, height - 56, 300, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        }
        @Override public void onClose() { minecraft.gui.setScreen(WorldHistoryScreen.this); }
    }
}
