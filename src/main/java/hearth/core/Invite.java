package hearth.core;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.net.URI;

/** Everything a friend needs to add a shared world, as one string to paste into a chat. */
public record Invite(String name, SharedWorld.Remote remote) {
	private static final String PREFIX = "hearth1:";
	private static final Gson GSON = new Gson();

	public String encode() {
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
	}

	public static Invite decode(String text) {
		if (text == null || text.length() > 16384) throw new IllegalArgumentException("Broken Hearth invite");
		String s = text.strip();
		if (!s.startsWith(PREFIX)) throw new IllegalArgumentException("Not a Hearth invite");
		Invite invite = GSON.fromJson(new String(Base64.getUrlDecoder().decode(s.substring(PREFIX.length())), StandardCharsets.UTF_8), Invite.class);
		if (invite == null || invite.name() == null || invite.name().isBlank() || invite.name().length() > 255
				|| invite.name().chars().anyMatch(Character::isISOControl)
				|| invite.remote() == null || invite.remote().url() == null) {
			throw new IllegalArgumentException("Broken Hearth invite");
		}
		SharedWorld.Remote remote = invite.remote();
		if (remote.ssh() != null) {
			GitSsh.repositoryPath(remote.url());
			if (remote.token() != null && !remote.token().isEmpty()) throw new IllegalArgumentException("A private invite cannot contain a GitHub account token");
			try { remote.ssh().keyPair(); }
			catch (Exception e) { throw new IllegalArgumentException("Invalid world access key"); }
			if (remote.trust() != null) {
				try {
					java.util.UUID.fromString(remote.trust().worldId());
					GitSsh.repositoryPath(remote.trust().policyUrl());
					remote.trust().policyAccess().keyPair();
				} catch (Exception e) { throw new IllegalArgumentException("Invalid creator rule access"); }
			}
		} else {
			URI uri = URI.create(remote.url());
			boolean github = "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
					&& (uri.getPort() == -1 || uri.getPort() == 443);
			boolean testNetwork = "git".equals(uri.getScheme()) && uri.getHost() != null
					&& (remote.token() == null || remote.token().isEmpty());
			if ((!github && !testNetwork) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
				throw new IllegalArgumentException("Unsupported world storage address");
			}
		}
		return invite;
	}

	@Override public String toString() { return "Invite[name=" + name + ", access=<redacted>]"; }
}
