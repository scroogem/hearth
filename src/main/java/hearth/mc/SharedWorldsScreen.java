package hearth.mc;

import hearth.core.SharedWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Shared worlds: one Play button decides whether to connect or host. */
public final class SharedWorldsScreen extends Screen {
	private final Screen back;
	private List<HearthClient.Entry> entries = List.of();
	private final Map<HearthClient.Entry, Object> statuses = new HashMap<>(); // Status or Throwable
	private int refreshTicks;
	private int page;
	private final Set<HearthClient.Entry> pending = new HashSet<>();

	public SharedWorldsScreen(Screen back) {
		super(Component.translatable("hearth.shared_worlds"));
		this.back = back;
	}

	private void refreshStatuses() {
		for (HearthClient.Entry e : visibleEntries()) {
			if (!pending.add(e)) continue;
			HearthClient.async(() -> {
				try (SharedWorld world = HearthClient.open(e)) {
					return world.status();
				}
			}).whenComplete((status, t) -> {
				pending.remove(e);
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
		addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));
		int content = Math.min(420, width - 32), x = (width - content) / 2, y = 40;
		for (HearthClient.Entry e : visibleEntries()) {
			Object st = statuses.get(e);
			addRenderableWidget(new StringWidget(x, y, content - 154, 12, Component.literal(e.name()), font).setMaxWidth(content - 154));
			addRenderableWidget(new StringWidget(x, y + 16, content - 154, 12, line(st, e), font).setMaxWidth(content - 154));
			Button play = addRenderableWidget(Button.builder(Component.translatable("hearth.play"), b -> HearthClient.play(e, this))
					.bounds(x + content - 146, y + 4, 70, 20).build());
			play.active = st instanceof SharedWorld.Status;
			addRenderableWidget(Button.builder(Component.translatable("hearth.details"),
					b -> minecraft.gui.setScreen(new WorldDetailsScreen(e, this))).bounds(x + content - 72, y + 4, 72, 20).build());
			y += 44;
		}
		if (entries.size() > pageSize()) {
			Button previous = addRenderableWidget(Button.builder(Component.translatable("hearth.previous"), b -> { page--; refreshStatuses(); rebuildWidgets(); })
					.bounds(width / 2 - 154, height - 56, 90, 20).build());
			previous.active = page > 0;
			addRenderableWidget(new StringWidget(width / 2 - 60, height - 56, 120, 20,
					Component.literal((page + 1) + " / " + ((entries.size() + pageSize() - 1) / pageSize())), font));
			Button next = addRenderableWidget(Button.builder(Component.translatable("hearth.next"), b -> { page++; refreshStatuses(); rebuildWidgets(); })
					.bounds(width / 2 + 64, height - 56, 90, 20).build());
			next.active = (page + 1) * pageSize() < entries.size();
		}
		if (entries.isEmpty()) {
			addRenderableWidget(new MultiLineTextWidget(width / 2 - 150, 40, Component.translatable("hearth.empty"), font).setMaxWidth(300));
		}
		addRenderableWidget(Button.builder(Component.translatable("hearth.add"), b -> minecraft.gui.setScreen(new AddWorldScreen(this)))
				.bounds(width / 2 - 154, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(width / 2 + 4, height - 28, 150, 20).build());
	}

	private int pageSize() { return Math.max(1, (height - 110) / 44); }
	private List<HearthClient.Entry> visibleEntries() {
		page = Math.min(page, Math.max(0, (entries.size() - 1) / pageSize()));
		int from = page * pageSize();
		return entries.subList(from, Math.min(entries.size(), from + pageSize()));
	}

	private static Component line(Object st, HearthClient.Entry entry) {
		if (st == null) return Component.translatable("hearth.checking").withStyle(ChatFormatting.GRAY);
		if (st instanceof Throwable t) {
			Throwable c = t.getCause() != null ? t.getCause() : t;
			return Component.translatable("hearth.offline", String.valueOf(c.getMessage())).withStyle(ChatFormatting.RED);
		}
		SharedWorld.Status s = (SharedWorld.Status) st;
		SharedWorld.Player holder = s.lock().holder();
		if (holder == null) return Component.translatable("hearth.nobody").withStyle(ChatFormatting.GRAY);
		if (holder.id().equals(HearthClient.me(entry).id())) return Component.translatable("hearth.you_unsaved").withStyle(ChatFormatting.GOLD);
		if (s.stale()) return Component.translatable("hearth.not_responding", holder.name()).withStyle(ChatFormatting.GOLD);
		return Component.translatable("hearth.playing", holder.name()).withStyle(ChatFormatting.GREEN);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(back);
	}

	/** Friends paste one invite; only creators need the GitHub flow. */
	static final class AddWorldScreen extends Screen {
		private final Screen back;
		private List<String> folders;
		private int folder;
		private EditBox invite;

		AddWorldScreen(Screen back) {
			super(Component.translatable("hearth.add"));
			this.back = back;
			List<String> list;
			try (var dirs = Files.list(HearthClient.savesDir())) {
				list = dirs.filter(d -> Files.exists(d.resolve("level.dat")) && (!Files.exists(d.resolve(".hearth")) || Files.exists(HearthClient.pendingCreation(d.getFileName().toString()))))
						.map(d -> d.getFileName().toString()).sorted().toList();
			} catch (Exception e) {
				list = List.of();
			}
			folders = list;
		}

		@Override
		protected void init() {
			int x = width / 2 - 150;
			addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), 20, title, font));

			addRenderableWidget(new StringWidget(x, 34, 300, 12, Component.translatable("hearth.paste_invite"), font));
			String typed = invite == null ? "" : invite.getValue();
			invite = addRenderableWidget(new EditBox(font, x, 48, 224, 20, Component.translatable("hearth.paste_invite")));
			invite.setMaxLength(16384);
			invite.setValue(typed);
			invite.setHint(Component.literal("hearth1:…"));
			addRenderableWidget(Button.builder(Component.translatable("hearth.add_button"), b -> HearthClient.addInvite(invite.getValue(), back))
					.bounds(x + 228, 48, 72, 20).build());

			addRenderableWidget(new StringWidget(x, 84, 300, 12, Component.translatable("hearth.share_own"), font));
			Button selector = addRenderableWidget(Button.builder(folderLabel(), b -> {
				if (!folders.isEmpty()) folder = (folder + 1) % folders.size();
				b.setMessage(folderLabel());
			}).bounds(x, 98, 300, 20).build());
			selector.active = !folders.isEmpty();
			Button create = addRenderableWidget(Button.builder(Component.translatable("hearth.github_create"), b -> {
				if (!folders.isEmpty()) minecraft.gui.setScreen(new GitHubWorldScreen(folders.get(folder), back));
			}).bounds(x, 122, 300, 20).build());
			create.active = !folders.isEmpty();
			addRenderableWidget(Button.builder(Component.translatable("selectWorld.create"), b ->
					HearthClient.createNewWorld(back)).bounds(x, 150, 300, 20).build());
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
