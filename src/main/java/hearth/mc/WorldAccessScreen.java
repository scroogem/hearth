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
    private hearth.core.WorldPolicy policy;
    private boolean loading, failed;

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
            if (!HearthClient.isOwner(entry)) throw new java.io.IOException("Only the creator manages access");
            var accesses = HearthClient.owner(entry).accesses();
            if (policy == null && !loading && !failed) {
                loading = true;
                HearthClient.async(() -> { try (var world = HearthClient.open(entry)) { return world.policy(); } }).whenComplete((value, error) -> {
                    loading = false; failed = error != null; policy = value;
                    if (minecraft.gui.screen() == this) rebuildWidgets();
                });
            }
            if (failed) throw new java.io.IOException("Could not load permissions");
            int count = Math.max(1, (height - 144) / 40);
            page = Math.min(page, Math.max(0, (accesses.size() - 1) / count));
            int from = page * count;
            for (var access : accesses.subList(from, Math.min(accesses.size(), from + count))) {
                var member = policy == null ? null : policy.member(hearth.core.WorldPolicy.identity(access.publicKey()));
                addRenderableWidget(new StringWidget(x, y, 220, 12, Component.literal(member == null ? access.name() : member.name()), font).setMaxWidth(220));
                addRenderableWidget(new StringWidget(x, y + 14, 220, 12, Component.translatable(member == null ? "hearth.checking" : member.banned() ? "hearth.role_banned" : member.operator() ? "hearth.role_operator" : "hearth.role_member"), font));
                Button details = addRenderableWidget(Button.builder(Component.translatable("hearth.member_rights"), b -> minecraft.gui.setScreen(new MemberScreen(access, member)))
                        .bounds(x + 224, y, 76, 20).build());
                details.active = member != null;
                y += 40;
            }
            if (accesses.isEmpty()) addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable("hearth.members_empty"), font));
            Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; rebuildWidgets(); }).bounds(x, height - 112, 146, 20).build());
            previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; rebuildWidgets(); }).bounds(x + 154, height - 112, 146, 20).build());
            next.active = from + count < accesses.size();
        } catch (Exception e) {
            addRenderableWidget(new StringWidget(x, y, 300, 20, Component.translatable("hearth.members_failed"), font));
            addRenderableWidget(Button.builder(Component.translatable("hearth.retry"), b -> { failed = false; policy = null; rebuildWidgets(); }).bounds(x, y + 24, 300, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("hearth.ip_bans"), b -> minecraft.gui.setScreen(new IpScreen(this))).bounds(x, height - 84, 300, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, height - 32, 300, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(back); }

    private final class MemberScreen extends Screen {
        private final HearthClient.GrantedAccess access;
        private final hearth.core.WorldPolicy.Member member;
        private EditBox name;
        private boolean operator;
        MemberScreen(HearthClient.GrantedAccess access, hearth.core.WorldPolicy.Member member) {
            super(Component.translatable("hearth.member_rights")); this.access = access; this.member = member; operator = member.operator();
        }
        @Override protected void init() {
            int x = width / 2 - 150;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            addRenderableWidget(new StringWidget(x, 38, 300, 12, Component.translatable("hearth.member_name"), font));
            String typed = name == null ? member.name() : name.getValue();
            name = addRenderableWidget(new EditBox(font, x, 52, 300, 20, Component.translatable("hearth.member_name")));
            name.setMaxLength(64); name.setValue(typed);
            addRenderableWidget(Button.builder(Component.translatable("hearth.operator", Component.translatable(operator ? "options.on" : "options.off")), b -> { operator = !operator; rebuildWidgets(); })
                    .bounds(x, 80, 300, 20).build());
            addRenderableWidget(new MultiLineTextWidget(x, 106, Component.translatable("hearth.operator_help"), font).setMaxWidth(300));
            addRenderableWidget(Button.builder(Component.translatable(member.banned() ? "hearth.unban" : "hearth.ban"), b -> minecraft.gui.setScreen(new ConfirmScreen(yes -> {
                if (yes) HearthClient.setMemberBan(entry, access, !member.banned(), new WorldAccessScreen(entry, back));
                else minecraft.gui.setScreen(this);
            }, Component.translatable(member.banned() ? "hearth.unban" : "hearth.ban"),
                    Component.translatable(member.banned() ? "hearth.unban_warning" : "hearth.revoke_warning", member.name())))).bounds(x, height - 56, 300, 20).build());
            Button save = addRenderableWidget(Button.builder(Component.translatable("hearth.save_settings"), b -> HearthClient.updateRules(entry,
                    rules -> rules.withMember(access.publicKey(), name.getValue().strip(), operator), new WorldAccessScreen(entry, back), this)).bounds(x, height - 28, 146, 20).build());
            save.active = !typed.isBlank(); name.setResponder(value -> save.active = !value.isBlank());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 154, height - 28, 146, 20).build());
        }
        @Override public void onClose() { minecraft.gui.setScreen(WorldAccessScreen.this); }
    }

    static final class InviteScreen extends Screen {
        private final HearthClient.Entry entry;
        private final Screen back;
        private EditBox name;
        InviteScreen(HearthClient.Entry entry, Screen back) { super(Component.translatable("hearth.invite")); this.entry = entry; this.back = back; }
        @Override protected void init() {
            int x = width / 2 - 150;
            addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
            addRenderableWidget(new StringWidget(x, 40, 300, 12, Component.translatable("hearth.member_name"), font));
            String typed = name == null ? "" : name.getValue();
            name = addRenderableWidget(new EditBox(font, x, 56, 300, 20, Component.translatable("hearth.member_name")));
            name.setMaxLength(64); name.setValue(typed); name.setHint(Component.translatable("hearth.member_name_hint"));
            addRenderableWidget(new MultiLineTextWidget(x, 88, Component.translatable("hearth.named_invite_help"), font).setMaxWidth(300));
            Button invite = addRenderableWidget(Button.builder(Component.translatable("hearth.create_invite"), b -> HearthClient.createInvite(entry, name.getValue().strip(), back))
                    .bounds(x, height - 56, 300, 20).build());
            invite.active = !typed.isBlank(); name.setResponder(value -> invite.active = !value.isBlank());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
            setInitialFocus(name);
        }
        @Override public void onClose() { minecraft.gui.setScreen(back); }
    }

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
