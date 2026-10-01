package hearth.mc.mixin;

import com.mojang.authlib.GameProfile;
import hearth.mc.HearthLogin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class SharedLoginGuardMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow public abstract void disconnect(Component message);

    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void hearth$requireInviteProfile(GameProfile profile, CallbackInfo callback) {
        if (!HearthLogin.verified((ServerLoginPacketListenerImpl) (Object) this, server)) {
            disconnect(Component.translatable("hearth.profile_rejected"));
            callback.cancel();
        }
    }
}
