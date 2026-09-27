package hearth.mc;

import hearth.core.Invite;
import hearth.core.SharedWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** "Shared worlds": who is playing each world, and Host / Take over buttons. */
public final class SharedWorldsScreen extends Screen {
	private final Screen back;
	private List<HearthClient.Entry> entries = List.of();
	private final Map<HearthClient.Entry, Object> statuses = new HashMap<>(); // Status or Throwable
	private int refreshTicks;

	public SharedWorldsScreen(Screen back) {
		super(Component.translatable("hearth.shared_worlds"));
		this.back = back;
	}

	private void refreshStatuses() {
		for (HearthClient.Entry e : entries) {
			HearthClient.async(() -> {
				try (SharedWorld world = HearthClient.open(e)) {
					return world.status();
				}
			}).whenComplete((status, t) -> {
				statuses.put(e, t != null ? t : status);
				if (minecraft.gui.screen() == this) rebuildWidgets();
			});
		}
	}

	@Override
	public void tick() {
		if (++refreshTicks >= 200) {
			refreshTicks = 0;
			refreshStatuses();
		}
	}

	@Override
	protected void init() {
		List<HearthClient.Entry> current = HearthClient.entries();
		if (!current.equals(entries)) {
			entries = current;
			statuses.clear();
			refreshStatuses();
		}
		addRenderableWidget(new StringWidget(0, 12, width, 12, title, font));
		int y = 36;
		for (HearthClient.Entry e : entries) {
			if (y > height - 60) break;
			Object st = statuses.get(e);
			addRenderableWidget(new StringWidget(width / 2 - 180, y + 4, 200, 12, Component.literal(e.name()).append("  ").append(line(st)), font)
					.setMaxWidth(200));
			if (st instanceof SharedWorld.Status status) {
				SharedWorld.Player holder = status.lock().holder();
				boolean mine = holder != null && holder.id().equals(HearthClient.me().id());
				if (holder == null || mine) {
					addRenderableWidget(Button.builder(Component.translatable("hearth.host"), b -> HearthClient.host(e, false, this))
							.bounds(width / 2 + 30, y, 70, 20).build());
				} else if (status.joinTarget() != null) {
					addRenderableWidget(Button.builder(Component.translatable("hearth.join"),
							b -> HearthClient.join(e, status.joinTarget(), this))
							.bounds(width / 2 + 30, y, 70, 20).build());
				} else if (status.stale()) {
					addRenderableWidget(Button.builder(Component.translatable("hearth.take_over"), b -> minecraft.gui.setScreen(new ConfirmScreen(
							yes -> {
								if (yes) HearthClient.host(e, true, this);
								else minecraft.gui.setScreen(this);
							},
							Component.translatable("hearth.take_over"),
							Component.translatable("hearth.take_over_warning", holder.name())))).bounds(width / 2 + 30, y, 70, 20).build());
				}
			}
			addRenderableWidget(Button.builder(Component.translatable("hearth.invite"), b -> {
				minecraft.keyboardHandler.setClipboard(new Invite(e.name(), e.remote()).encode());
				b.setMessage(Component.translatable("hearth.copied"));
			}).bounds(width / 2 + 104, y, 76, 20).build());
			y += 24;
		}
		if (entries.isEmpty()) {
			addRenderableWidget(new MultiLineTextWidget(width / 2 - 150, 40, Component.translatable("hearth.empty"), font).setMaxWidth(300));
		}
		addRenderableWidget(Button.builder(Component.translatable("hearth.add"), b -> minecraft.gui.setScreen(new AddWorldScreen(this)))
				.bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	private static Component line(Object st) {
		if (st == null) return Component.literal("…").withStyle(ChatFormatting.GRAY);
		if (st instanceof Throwable t) {
			Throwable c = t.getCause() != null ? t.getCause() : t;
			return Component.translatable("hearth.offline", String.valueOf(c.getMessage())).withStyle(ChatFormatting.RED);
		}
		SharedWorld.Status s = (SharedWorld.Status) st;
		SharedWorld.Player holder = s.lock().holder();
		if (holder == null) return Component.translatable("hearth.nobody").withStyle(ChatFormatting.GRAY);
		if (holder.id().equals(HearthClient.me().id())) return Component.translatable("hearth.you_unsaved").withStyle(ChatFormatting.GOLD);
		if (s.stale()) return Component.translatable("hearth.not_responding", holder.name()).withStyle(ChatFormatting.GOLD);
		return Component.translatable("hearth.playing", holder.name()).withStyle(ChatFormatting.GREEN);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(back);
	}

	/** Paste an invite, or share one of your own worlds through an empty git repo. */
	static final class AddWorldScreen extends Screen {
		private final Screen back;
		private final List<String> folders;
		private int folder;
		private EditBox invite;
		private EditBox url;
		private EditBox token;

		AddWorldScreen(Screen back) {
			super(Component.translatable("hearth.add"));
			this.back = back;
			List<String> list;
			try (var dirs = Files.list(HearthClient.savesDir())) {
				list = dirs.filter(d -> Files.exists(d.resolve("level.dat")) && !Files.exists(d.resolve(".hearth")))
						.map(d -> d.getFileName().toString()).sorted().toList();
			} catch (Exception e) {
				list = List.of();
			}
			folders = list;
		}

		@Override
		protected void init() {
			int x = width / 2 - 150;
			addRenderableWidget(new StringWidget(0, 12, width, 12, title, font));

			addRenderableWidget(new StringWidget(x, 34, 300, 12, Component.translatable("hearth.paste_invite"), font));
			invite = addRenderableWidget(new EditBox(font, x, 48, 224, 20, Component.translatable("hearth.paste_invite")));
			invite.setMaxLength(4096);
			addRenderableWidget(Button.builder(Component.translatable("hearth.add_button"), b -> HearthClient.addInvite(invite.getValue(), back))
					.bounds(x + 228, 48, 72, 20).build());

			addRenderableWidget(new StringWidget(x, 84, 300, 12, Component.translatable("hearth.share_own"), font));
			addRenderableWidget(Button.builder(folderLabel(), b -> {
				if (!folders.isEmpty()) folder = (folder + 1) % folders.size();
				b.setMessage(folderLabel());
			}).bounds(x, 98, 300, 20).build());
			url = addRenderableWidget(new EditBox(font, x, 122, 300, 20, Component.translatable("hearth.repo_url")));
			url.setMaxLength(512);
			url.setHint(Component.literal("https://github.com/you/world.git").withStyle(ChatFormatting.DARK_GRAY));
			token = addRenderableWidget(new EditBox(font, x, 146, 300, 20, Component.translatable("hearth.token")));
			token.setMaxLength(512);
			token.setHint(Component.translatable("hearth.token").withStyle(ChatFormatting.DARK_GRAY));
			addRenderableWidget(new MultiLineTextWidget(x, 170, Component.translatable("hearth.repo_help"), font).setMaxWidth(300));
			addRenderableWidget(Button.builder(Component.translatable("hearth.share_button"), b -> {
				if (folders.isEmpty() || url.getValue().isBlank()) return;
				HearthClient.createFrom(folders.get(folder), new SharedWorld.Remote(url.getValue().strip(), "git", token.getValue().strip()), back);
			}).bounds(x, height - 52, 300, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x, height - 28, 300, 20).build());
		}

		private Component folderLabel() {
			return folders.isEmpty()
					? Component.translatable("hearth.no_local_worlds")
					: Component.translatable("hearth.world", folders.get(folder));
		}

		@Override
		public void onClose() {
			minecraft.gui.setScreen(back);
		}
	}
}
