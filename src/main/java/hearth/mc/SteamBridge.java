package hearth.mc;

import net.fabricmc.loader.api.FabricLoader;

import java.util.UUID;

/** The compatibility boundary with e4steam's integrated-world session. */
final class SteamBridge {
	static volatile Object identities;
	private SteamBridge() {}

	static UUID localUuid() {
		if (!installed() || identities == null) return null;
		try {
			var localMethod = Class.forName("link.e4steam.api.identity.IdentityService").getMethod("local");
			Object view = localMethod.invoke(identities);
			var value = (java.util.Optional<?>) localMethod.getReturnType().getMethod("value").invoke(view);
			if (value.isEmpty()) return null;
			Object local = value.get();
			Object profile = local.getClass().getMethod("minecraft").invoke(local);
			return (UUID) profile.getClass().getMethod("uuid").invoke(profile);
		} catch (ReflectiveOperationException | LinkageError e) {
			HearthClient.LOG.warn("Could not read the optional Steam identity", e);
			return null;
		}
	}

	static boolean installed() {
		return FabricLoader.getInstance().isModLoaded("e4steam");
	}

	/** e4steam does not yet expose the active address through its addon API. */
	static String readyAddress() {
		if (!installed()) return null;
		try {
			Class<?> client = Class.forName("link.e4steam.E4steamClient");
			Object session = client.getField("session").get(null);
			if (session == null || !"STARTED".equals(String.valueOf(session.getClass().getField("state").get(session)))) {
				return null;
			}
			Object address = session.getClass().getMethod("address").invoke(session);
			return address == null ? null : (String) address.getClass().getMethod("inviteString").invoke(address);
		} catch (ReflectiveOperationException | LinkageError e) {
			HearthClient.LOG.warn("Cannot read the e4steam session address", e);
			return null;
		}
	}
}
