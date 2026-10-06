package hearth.mc.mixin;

import com.mojang.brigadier.ParseResults;
import hearth.mc.WorldRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(Commands.class)
public abstract class SharedCommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void hearth$modePermissionIsNotGeneralCheats(ParseResults<CommandSourceStack> parsed, String command, CallbackInfo callback) {
        var source = parsed.getContext().getSource();
        var player = source.getPlayer();
        var policy = WorldRules.policy(source.getServer());
        if (policy == null || player == null) return;
        var nodes = parsed.getContext().getNodes();
        if (nodes.isEmpty()) return;
        var root = nodes.getFirst().getNode();
        if (!WorldRules.commandAllowed(source, root)) {
            source.sendFailure(Component.translatable(java.util.Set.of("gamerule", "difficulty", "defaultgamemode").contains(root.getName())
                    ? "hearth.use_world_settings" : "hearth.commands_disabled"));
            callback.cancel();
        }
    }

    @Inject(method = "sendCommands", at = @At(value = "NEW", target = "net/minecraft/network/protocol/game/ClientboundCommandsPacket"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void hearth$showOnlyAllowedCommands(net.minecraft.server.level.ServerPlayer player, CallbackInfo callback,
            java.util.Map<?, ?> nodes, com.mojang.brigadier.tree.RootCommandNode<?> root) {
        var source = player.createCommandSourceStack();
        if (WorldRules.policy(source.getServer()) == null) return;
        var original = source.getServer().getCommands().getDispatcher().getRoot();
        root.getChildren().removeIf(node -> !WorldRules.commandAllowed(source, original.getChild(node.getName())));
    }
}
