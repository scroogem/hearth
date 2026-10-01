package hearth.mc.mixin;

import hearth.mc.HearthClient;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class SharedOwnerMixin {
    @Inject(method = "isSingleplayerOwner", at = @At("HEAD"), cancellable = true)
    private void hearth$localHostOwnsServerLifecycle(NameAndId profile, CallbackInfoReturnable<Boolean> callback) {
        // Minecraft uses this for shutdown and player data, not just permissions.
        // Creator authority is enforced separately by SharedPermissionsMixin.
        var host = HearthClient.hostPlayerUuid((IntegratedServer) (Object) this);
        if (host != null) callback.setReturnValue(host.equals(profile.id()));
    }
}
