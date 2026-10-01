package hearth.mc;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WorldDetailsScreen extends Screen {
    private final HearthClient.Entry entry;
    private final Screen back;

    WorldDetailsScreen(HearthClient.Entry entry, Screen back) {
        super(Component.literal(entry.name()));
        this.entry = entry;
        this.back = back;
    }

    @Override protected void init() {
        int x = width / 2 - 150, y = 40;
        addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
        if (HearthClient.isOwner(entry) || entry.remote().ssh() == null) {
            addRenderableWidget(Button.builder(Component.translatable("hearth.invite"), b -> HearthClient.createInvite(entry, this)).bounds(x, y, 300, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("hearth.history"), b -> minecraft.gui.setScreen(new WorldHistoryScreen(entry, this))).bounds(x, y, 300, 20).build());
        y += 24;
        if (HearthClient.isOwner(entry)) {
            addRenderableWidget(Button.builder(Component.translatable("hearth.settings"), b -> minecraft.gui.setScreen(new WorldSettingsScreen(entry, this))).bounds(x, y, 300, 20).build());
            y += 24;
            addRenderableWidget(Button.builder(Component.translatable("hearth.access"), b -> minecraft.gui.setScreen(new WorldAccessScreen(entry, this))).bounds(x, y, 300, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(transportLabel(), b -> {
            HearthClient.setTransport(HearthClient.transport().equals("lan") ? "auto" : "lan");
            b.setMessage(transportLabel());
        }).bounds(x, y, 300, 20).build());
        addRenderableWidget(new MultiLineTextWidget(x, y + 24, Component.translatable("hearth.transport_help"), font).setMaxWidth(300));
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
    }

    private Component transportLabel() { return Component.translatable("hearth.transport_" + HearthClient.transport()); }
    @Override public void onClose() { minecraft.gui.setScreen(back); }
}
