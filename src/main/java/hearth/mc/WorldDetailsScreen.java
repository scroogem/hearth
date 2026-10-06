package hearth.mc;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WorldDetailsScreen extends Screen {
    private final HearthClient.Entry entry;
    private final Screen back;
    private hearth.core.WorldPolicy policy;
    private boolean loading;
    private boolean failed;
    private boolean loaded;

    WorldDetailsScreen(HearthClient.Entry entry, Screen back) {
        super(Component.literal(entry.name()));
        this.entry = entry;
        this.back = back;
    }

    @Override protected void init() {
        int x = width / 2 - 150, y = 52;
        int titleWidth = Math.min(300, font.width(title));
        addRenderableWidget(new StringWidget((width - titleWidth) / 2, 12, titleWidth, 20, title, font).setMaxWidth(titleWidth));
        var cached = HearthClient.cachedPolicy(entry);
        if (cached != null && !loaded) policy = cached;
        var member = policy == null || entry.remote().ssh() == null ? null : policy.member(hearth.core.WorldPolicy.identity(entry.remote().ssh().publicKey()));
        String role = HearthClient.isOwner(entry) ? "creator" : member != null ? member.banned() ? "banned" : member.operator() ? "operator" : "member" : failed ? "unknown" : "checking";
        Component status = Component.translatable("hearth.role_" + role);
        if (minecraft.level != null && (entry.equals(HearthClient.joiningEntry) || HearthClient.session != null && HearthClient.session.entry().equals(entry)))
            status = Component.translatable("hearth.role_session", status, Component.translatable(HearthClient.session != null ? "hearth.session_host" : "hearth.session_guest"));
        addRenderableWidget(new StringWidget(x, 34, 300, 12, status, font).setMaxWidth(300));
        if (entry.remote().trust() != null && !loading && !loaded && !failed) {
            loading = true;
            HearthClient.async(() -> { try (var world = HearthClient.open(entry)) { return world.policy(); } }).whenComplete((value, error) -> {
                loading = false;
                failed = error != null;
                if (error == null) {
                    loaded = true; policy = value;
                    if (entry.equals(HearthClient.joiningEntry)) HearthClient.joiningPolicy = value;
                }
                if (minecraft.gui.screen() == this) rebuildWidgets();
            });
        }
        if (HearthClient.isOwner(entry) || entry.remote().ssh() == null) {
            addRenderableWidget(Button.builder(Component.translatable("hearth.invite"), b -> HearthClient.createInvite(entry, this)).bounds(x, y, 300, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("hearth.history"), b -> minecraft.gui.setScreen(new WorldHistoryScreen(entry, this))).bounds(x, y, 300, 20).build());
        y += 24;
        if (entry.remote().trust() != null) {
            addRenderableWidget(Button.builder(Component.translatable(HearthClient.isOwner(entry) ? "hearth.settings" : "hearth.rules_view"), b -> minecraft.gui.setScreen(new WorldSettingsScreen(entry, this)))
                    .bounds(x, y, 300, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(HearthClient.isOwner(entry) ? "hearth.creator_help" : "hearth.member_help"))).build());
            y += 24;
        }
        if (HearthClient.isOwner(entry)) {
            addRenderableWidget(Button.builder(Component.translatable("hearth.access"), b -> minecraft.gui.setScreen(new WorldAccessScreen(entry, this))).bounds(x, y, 300, 20).build());
            y += 24;
        }
        Button transport = addRenderableWidget(Button.builder(transportLabel(), b -> {
            HearthClient.setTransport(HearthClient.transport().equals("lan") ? "auto" : "lan");
            b.setMessage(transportLabel());
        }).bounds(x, y, 300, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("hearth.transport_next_session"))).build());
        transport.active = HearthClient.session == null;
        addRenderableWidget(new MultiLineTextWidget(x, y + 24, Component.translatable("hearth.transport_help"), font).setMaxWidth(300));
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
    }

    private Component transportLabel() { return Component.translatable("hearth.transport_" + HearthClient.transport()); }
    @Override public void onClose() { minecraft.gui.setScreen(back); }
}
