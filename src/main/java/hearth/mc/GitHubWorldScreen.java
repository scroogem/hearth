package hearth.mc;

import hearth.core.GitHubLogin;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.concurrent.TimeUnit;

/** The creator signs in once. Guests use the invite screen and never visit GitHub. */
final class GitHubWorldScreen extends Screen {
    private final String folder;
    private final Screen back;
    private GitHubLogin.Device device;
    private GitHubLogin login;
    private String accountName;
    private boolean busy;

    GitHubWorldScreen(String folder, Screen back) {
        super(Component.translatable("hearth.github_create"));
        this.folder = folder;
        this.back = back;
        try { var account = HearthClient.account(); accountName = account == null ? null : account.login(); }
        catch (Exception ignored) {}
    }

    @Override protected void init() {
        int x = width / 2 - 150;
        addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 20, font.width(title), 20, title, font));
        addRenderableWidget(new MultiLineTextWidget(x, 54,
                Component.translatable("hearth.github_creator_help"), font).setMaxWidth(300));
        if (accountName != null) {
            addRenderableWidget(new StringWidget(x, 130, 300, 20, Component.literal("GitHub: " + accountName), font));
            addRenderableWidget(Button.builder(Component.translatable("hearth.github_create"),
                    b -> HearthClient.createOnGitHub(folder, back)).bounds(x, height - 60, 300, 20).build());
        } else if (device != null) {
            addRenderableWidget(new StringWidget(x, 130, 300, 20, Component.literal(device.userCode()), font));
            addRenderableWidget(Button.builder(Component.translatable("hearth.github_open"), b -> {
                minecraft.keyboardHandler.setClipboard(device.userCode());
                Util.getPlatform().openUri("https://github.com/login/device");
            }).bounds(x, 158, 300, 20).build());
        } else {
            Button signIn = addRenderableWidget(Button.builder(Component.translatable("hearth.github_sign_in"), b -> start())
                    .bounds(x, 130, 300, 20).build());
            signIn.active = !busy;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, height - 32, 300, 20).build());
    }

    private void start() {
        busy = true;
        rebuildWidgets();
        HearthClient.async(() -> { login = HearthClient.loginClient(); return login.start(); }).whenComplete((value, error) -> {
            if (minecraft.gui.screen() != this) return;
            busy = false;
            if (error != null) { failed(error); return; }
            device = value;
            rebuildWidgets();
            poll(device, device.intervalSeconds());
        });
    }

    private void poll(GitHubLogin.Device expected, int interval) {
        HearthClient.GIT.schedule(() -> {
            if (minecraft.gui.screen() != this || device != expected) return;
            HearthClient.async(() -> {
                var result = login.poll(expected, interval);
                if (result.tokens() != null) HearthClient.saveAccount(result.tokens());
                return result;
            }).whenComplete((result, error) -> {
                if (minecraft.gui.screen() != this || device != expected) return;
                if (error != null) { failed(error); return; }
                if (result.tokens() == null) poll(expected, result.intervalSeconds());
                else { device = null; accountName = result.tokens().login(); rebuildWidgets(); }
            });
        }, interval, TimeUnit.SECONDS);
    }

    private void failed(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        device = null;
        minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(this),
                Component.translatable("hearth.error"), Component.literal(String.valueOf(cause.getMessage()))));
    }

    @Override public void onClose() { device = null; minecraft.gui.setScreen(back); }
}
