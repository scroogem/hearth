package hearth.mc;

import link.e4steam.api.ApiVersion;
import link.e4steam.api.ApiVersionRange;
import link.e4steam.api.addon.AddonContext;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.addon.E4steamAddonEntrypoint;
import link.e4steam.api.capability.Capabilities;

import java.util.List;
import java.util.Set;

/** Uses the same authenticated identity e4steam assigns to remote Minecraft players. */
public final class HearthSteamAddon implements E4steamAddonEntrypoint {
	@Override
	public AddonDescriptor descriptor() {
		var capabilities = Set.of(Capabilities.IDENTITY_MINECRAFT_READ);
		return new AddonDescriptor(new AddonId("hearth:worlds"), "Hearth", ApiVersion.parse("0.1.1"),
				new ApiVersionRange(ApiVersion.parse("1.1.0"), ApiVersion.parse("2.0.0")),
				List.of(), capabilities, capabilities);
	}

	@Override
	public void initialize(AddonContext context) {
		SteamBridge.identities = context.api().identities();
	}
}
