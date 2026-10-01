package hearth.mc.mixin;

import hearth.mc.WorldRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class SharedRulesMixin {
    @Inject(method = "setDefaultGameType", at = @At("HEAD"), cancellable = true)
    private void hearth$signedDefault(GameType mode, CallbackInfo callback) {
        var policy = WorldRules.policy((MinecraftServer) (Object) this);
        if (policy != null && mode != GameType.byName(policy.settings().gameMode())) callback.cancel();
    }

    @Inject(method = "setDifficulty", at = @At("HEAD"), cancellable = true)
    private void hearth$signedDifficulty(Difficulty difficulty, boolean force, CallbackInfo callback) {
        var policy = WorldRules.policy((MinecraftServer) (Object) this);
        if (policy != null && difficulty != Difficulty.byName(policy.settings().difficulty())) callback.cancel();
    }

    @Inject(method = "setDifficultyLocked", at = @At("HEAD"), cancellable = true)
    private void hearth$keepDifficultyLocked(boolean locked, CallbackInfo callback) {
        if (!locked && WorldRules.policy((MinecraftServer) (Object) this) != null) callback.cancel();
    }
}
