package hearth.mc;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WorldAccessScreen extends Screen {
    private final HearthClient.Entry entry;
    private final Screen back;
    private int page;

    WorldAccessScreen(HearthClient.Entry entry, Screen back) {
        super(Component.translatable("hearth.access"));
        this.entry = entry;
        this.back = back;
    }

    @Override protected void init() {
        int x = width / 2 - 150;
        addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 14, font.width(title), 20, title, font));
        int y = 48;
        try {
            var accesses = HearthClient.owner(entry).accesses();
            int count = Math.max(1, (height - 144) / 24);
            page = Math.min(page, Math.max(0, (accesses.size() - 1) / count));
            int from = page * count;
            for (var access : accesses.subList(from, Math.min(accesses.size(), from + count))) {
                addRenderableWidget(new StringWidget(x, y, 190, 20, Component.literal(access.name()), font));
                addRenderableWidget(Button.builder(Component.translatable(access.banned() ? "hearth.unban" : "hearth.ban"), b -> {
                    minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                        if (yes) HearthClient.setMemberBan(entry, access, !access.banned(), this);
                        else minecraft.gui.setScreen(this);
                    }, Component.translatable(access.banned() ? "hearth.unban" : "hearth.ban"),
                            Component.translatable(access.banned() ? "hearth.unban_warning" : "hearth.revoke_warning", access.name())));
                }).bounds(x + 194, y, 106, 20).build());
                y += 24;
            }
            if (accesses.isEmpty()) addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable("hearth.members_empty"), font));
            Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 112, 146, 20).build());
            previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 112, 146, 20).build());
            next.active = from + count < accesses.size();
        } catch (Exception e) {
            addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable("hearth.members_failed"), font));
            addRenderableWidget(Button.builder(Component.translatable("hearth.retry"), b -> rebuildWidgets()).bounds(x, y + 24, 300, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("hearth.ip_bans"), b -> minecraft.gui.setScreen(new IpScreen(this))).bounds(x, height - 84, 300, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, height - 32, 300, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(back); }

    private static final class IpScreen extends Screen {
        private final WorldAccessScreen back;
        private hearth.core.WorldPolicy policy;
        private boolean loading;
        private int page;
        private EditBox ip;

        IpScreen(WorldAccessScreen back) { super(Component.translatable("hearth.ip_bans")); this.back = back; }

        @Override protected void init() {
            int x = width / 2 - 150;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            addRenderableWidget(new MultiLineTextWidget(x, 38, Component.translatable("hearth.ip_help"), font).setMaxWidth(300));
            String previousValue = ip == null ? "" : ip.getValue();
            ip = addRenderableWidget(new EditBox(font, x, 78, 220, 20, Component.translatable("hearth.ip_address")));
            ip.setMaxLength(45); ip.setValue(previousValue); ip.setHint(Component.literal("IPv4 / IPv6"));
            Button ban = addRenderableWidget(Button.builder(Component.translatable("hearth.ban"), b -> {
                try {
                    String value = hearth.core.WorldPolicy.normalizeIp(ip.getValue().strip());
                    HearthClient.updateRules(back.entry, rules -> rules.banIp(value, true), new IpScreen(back));
                } catch (IllegalArgumentException e) { minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(this), title, Component.translatable("hearth.invalid_ip"))); }
            }).bounds(x + 224, 78, 76, 20).build());
            ban.active = policy != null;
            if (policy == null && !loading) {
                loading = true;
                HearthClient.async(() -> { try (var world = HearthClient.open(back.entry)) { return world.policy(); } }).whenComplete((value, error) -> {
                    loading = false;
                    if (minecraft.gui.screen() != this) return;
                    if (error == null) { policy = value; rebuildWidgets(); }
                    else minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(back), title, Component.literal(String.valueOf(error.getMessage()))));
                });
            }
            if (policy != null) {
                int count = Math.max(1, (height - 188) / 24), from = page * count, y = 106;
                for (String address : policy.bannedIps().subList(Math.min(from, policy.bannedIps().size()), Math.min(policy.bannedIps().size(), from + count))) {
                    addRenderableWidget(new StringWidget(x, y, 220, 20, Component.literal(address), font).setMaxWidth(220));
                    addRenderableWidget(Button.builder(Component.translatable("hearth.unban"), b -> HearthClient.updateRules(back.entry, rules -> rules.banIp(address, false), new IpScreen(back))).bounds(x + 224, y, 76, 20).build());
                    y += 24;
                }
                Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 56, 146, 20).build());
                previous.active = page > 0;
                Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 56, 146, 20).build());
                next.active = from + count < policy.bannedIps().size();
            }
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
        }
        @Override public void onClose() { minecraft.gui.setScreen(back); }
    }
}
