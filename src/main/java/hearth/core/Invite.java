package hearth.core;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Everything a friend needs to add a shared world, as one string to paste into a chat. */
public record Invite(String name, SharedWorld.Remote remote) {
	private static final String PREFIX = "hearth1:";
	private static final Gson GSON = new Gson();

	public String encode() {
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
	}

	public static Invite decode(String text) {
		String s = text.strip();
		if (!s.startsWith(PREFIX)) throw new IllegalArgumentException("Not a Hearth invite");
		Invite invite = GSON.fromJson(new String(Base64.getUrlDecoder().decode(s.substring(PREFIX.length())), StandardCharsets.UTF_8), Invite.class);
		if (invite == null || invite.name() == null || invite.remote() == null || invite.remote().url() == null) {
			throw new IllegalArgumentException("Broken Hearth invite");
		}
		return invite;
	}
}
