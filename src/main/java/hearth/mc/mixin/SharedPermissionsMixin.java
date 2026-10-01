package hearth.mc.mixin;

import hearth.mc.WorldRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class SharedPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void hearth$creatorPermissions(NameAndId profile, CallbackInfoReturnable<LevelBasedPermissionSet> callback) {
        var policy = WorldRules.policy((MinecraftServer) (Object) this);
        if (policy == null) return;
        var member = policy.member(profile.id());
        callback.setReturnValue(member == null || member.banned() ? LevelBasedPermissionSet.ALL
                : profile.id().equals(policy.ownerUuid()) ? LevelBasedPermissionSet.OWNER
                : policy.settings().cheats() || policy.settings().allowModeChanges() ? LevelBasedPermissionSet.GAMEMASTER
                : LevelBasedPermissionSet.ALL);
    }
}
