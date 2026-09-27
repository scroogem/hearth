package hearth.mc;

import net.fabricmc.loader.api.FabricLoader;

/** The compatibility boundary with e4steam's integrated-world session. */
final class SteamBridge {
	private SteamBridge() {}

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
