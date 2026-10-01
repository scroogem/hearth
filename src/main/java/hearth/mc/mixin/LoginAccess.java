package hearth.mc.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerLoginPacketListenerImpl.class)
public interface LoginAccess {
    @Accessor("connection") Connection hearth$connection();
    @Accessor("authenticatedProfile") GameProfile hearth$profile();
    @Accessor("authenticatedProfile") void hearth$profile(GameProfile profile);
}
