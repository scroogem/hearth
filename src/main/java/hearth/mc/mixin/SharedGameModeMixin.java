package hearth.mc.mixin;

import hearth.mc.WorldRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class SharedGameModeMixin {
    @Inject(method = "setGameMode", at = @At("HEAD"), cancellable = true)
    private void hearth$checkGameMode(GameType mode, CallbackInfoReturnable<Boolean> callback) {
        if (!WorldRules.modeAllowed((ServerPlayer) (Object) this, mode)) callback.setReturnValue(false);
    }
}
