package hearth.mc.mixin;

import com.mojang.brigadier.ParseResults;
import hearth.mc.WorldRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class SharedCommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void hearth$modePermissionIsNotGeneralCheats(ParseResults<CommandSourceStack> parsed, String command, CallbackInfo callback) {
        var source = parsed.getContext().getSource();
        var player = source.getPlayer();
        var policy = WorldRules.policy(source.getServer());
        if (policy == null || player == null || policy.ownerUuid().equals(player.getUUID()) || policy.settings().cheats()) return;
        var nodes = parsed.getContext().getNodes();
        if (nodes.isEmpty()) return;
        var root = nodes.getFirst().getNode();
        if (policy.settings().allowModeChanges() && root.getName().equals("gamemode")) return;
        if (!root.getRequirement().test(source.withPermission(LevelBasedPermissionSet.ALL))) {
            source.sendFailure(Component.translatable("hearth.commands_disabled"));
            callback.cancel();
        }
    }
}
