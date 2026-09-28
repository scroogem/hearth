package hearth.mc.mixin;

import com.mojang.authlib.GameProfile;
import hearth.mc.HearthClient;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class SharedHostProfileMixin {
	@Shadow @Final private Connection connection;
	@Shadow @Final private MinecraftServer server;

	@ModifyVariable(method = "startClientVerification", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private GameProfile hearth$useSteamProfileForHost(GameProfile original) {
		if (!connection.isMemoryConnection()) return original;
		var uuid = HearthClient.hostPlayerUuid(server);
		return uuid == null ? original : new GameProfile(uuid, original.name(), original.properties());
	}
}
