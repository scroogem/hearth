package hearth.mc.mixin;

import hearth.mc.HearthClient;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public abstract class SharedWorldOpenMixin {
    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void hearth$requireSharedWorldFlow(String folder, Runnable onCancel, CallbackInfo callback) {
        if (!HearthClient.allowWorldOpen(folder)) callback.cancel();
    }
}
