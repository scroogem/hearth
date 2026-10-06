package hearth.mc.mixin;

import hearth.mc.WorldRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameModeCommand.class)
public abstract class SharedGameModeCommandMixin {
    @Inject(method = "setGameMode(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/GameType;)Z", at = @At("HEAD"), cancellable = true)
    private static void hearth$modeChangesApplyToSelf(CommandSourceStack source, ServerPlayer target, GameType mode, CallbackInfoReturnable<Boolean> callback) {
        var policy = WorldRules.policy(source.getServer());
        var actor = source.getPlayer();
        if (policy != null && actor != null && !policy.ownerUuid().equals(actor.getUUID())
                && !policy.canUseCommands(actor.getUUID()) && actor != target) {
            source.sendFailure(net.minecraft.network.chat.Component.translatable("hearth.self_mode_only"));
            callback.setReturnValue(false);
        } else if (!WorldRules.modeAllowed(target, mode)) {
            source.sendFailure(net.minecraft.network.chat.Component.translatable("hearth.mode_disabled"));
            callback.setReturnValue(false);
        }
    }
}
