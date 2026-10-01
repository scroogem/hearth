package hearth.mc;

import com.mojang.authlib.GameProfile;
import hearth.core.LoginProof;
import hearth.core.WorldPolicy;
import hearth.mc.mixin.LoginAccess;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Hearth credentials survive launcher/Steam changes and are required before a shared-world login. */
public final class HearthLogin {
    private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("hearth", "profile_v2");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<ServerLoginPacketListenerImpl, byte[]> CHALLENGES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ServerLoginPacketListenerImpl, UUID> VERIFIED = new ConcurrentHashMap<>();

    static void register() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            var session = HearthClient.session;
            if (session == null || session.policy().get() == null) return;
            try {
                byte[] nonce = new byte[32];
                RANDOM.nextBytes(nonce);
                CHALLENGES.put(handler, nonce);
                String worldId = session.entry().remote().trust().worldId();
                var key = session.entry().remote().ssh();
                var buffer = new FriendlyByteBuf(Unpooled.buffer());
                buffer.writeUtf(worldId, 36).writeByteArray(nonce).writeUtf(key.publicKey(), 2048)
                        .writeUtf(WorldPolicy.signBytes(key, LoginProof.host(worldId, nonce, key.publicKey())), 1024);
                sender.sendPacket(CHANNEL, buffer);
            } catch (Exception e) { HearthClient.LOG.warn("Could not start Hearth profile verification", e); reject(handler); }
        });

        ServerLoginNetworking.registerGlobalReceiver(CHANNEL, (server, handler, understood, buffer, synchronizer, sender) -> {
            var session = HearthClient.session;
            byte[] nonce = CHALLENGES.remove(handler);
            if (session == null || session.policy().get() == null || nonce == null) { reject(handler); return; }
            try {
                if (!understood) throw new IllegalArgumentException();
                String publicKey = buffer.readUtf(2048);
                String manifest = buffer.readUtf(32767);
                String proof = buffer.readUtf(1024);
                if (buffer.isReadable()) throw new IllegalArgumentException();
                WorldPolicy policy = session.policy().get();
                UUID uuid = WorldPolicy.identity(publicKey);
                WorldPolicy.Member member = policy.member(uuid);
                if (member == null || member.banned() || WorldRules.ipBlocked(policy, ((LoginAccess) handler).hearth$connection().getRemoteAddress(), uuid)
                        || !WorldPolicy.verifyBytes(publicKey,
                        LoginProof.guest(policy.worldId(), nonce, session.entry().remote().ssh().publicKey(), publicKey, manifest), proof)) {
                    throw new IllegalArgumentException();
                }
                var raw = com.google.gson.JsonParser.parseString(manifest).getAsJsonObject();
                if (raw.size() > 1000) throw new IllegalArgumentException();
                java.util.Map<String, String> mods = new java.util.TreeMap<>();
                for (var item : raw.entrySet()) {
                    if (!item.getKey().matches("[a-z0-9_.-]{1,100}") || !item.getValue().isJsonPrimitive() || !item.getValue().getAsJsonPrimitive().isString()
                            || item.getValue().getAsString().length() > 200) throw new IllegalArgumentException();
                    mods.put(item.getKey(), item.getValue().getAsString());
                }
                ModManifest.verify(policy, mods);
                VERIFIED.put(handler, uuid);
                LoginAccess access = (LoginAccess) handler;
                GameProfile original = access.hearth$profile();
                access.hearth$profile(new GameProfile(uuid, original.name(), original.properties()));
            } catch (Exception e) { HearthClient.LOG.warn("Hearth profile verification failed: {}", e.getClass().getSimpleName()); reject(handler); }
        });

        ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> { CHALLENGES.remove(handler); VERIFIED.remove(handler); });

        ClientLoginNetworking.registerGlobalReceiver(CHANNEL, (mc, handler, buffer, callbacks) -> {
            try {
                String worldId = buffer.readUtf(36);
                byte[] nonce = buffer.readByteArray(32);
                String hostKey = buffer.readUtf(2048);
                String proof = buffer.readUtf(1024);
                if (buffer.isReadable()) throw new IllegalArgumentException();
                var local = HearthClient.session;
                var entry = local != null && local.entry().remote().trust() != null
                        && local.entry().remote().trust().worldId().equals(worldId) ? local.entry() : HearthClient.joiningEntry;
                WorldPolicy policy = local != null && entry == local.entry() ? local.policy().get() : HearthClient.joiningPolicy;
                if (entry == null || policy == null || !policy.worldId().equals(worldId)
                        || !policy.canHost(WorldPolicy.identity(hostKey))
                        || !WorldPolicy.verifyBytes(hostKey, LoginProof.host(worldId, nonce, hostKey), proof)) {
                    throw new IllegalArgumentException();
                }
                var key = entry.remote().ssh();
                var mods = ModManifest.current();
                ModManifest.verify(policy, mods);
                String manifest = new com.google.gson.Gson().toJson(mods);
                var response = new FriendlyByteBuf(Unpooled.buffer());
                response.writeUtf(key.publicKey(), 2048).writeUtf(manifest, 32767).writeUtf(WorldPolicy.signBytes(key,
                        LoginProof.guest(worldId, nonce, hostKey, key.publicKey(), manifest)), 1024);
                return CompletableFuture.completedFuture(response);
            } catch (Exception e) { HearthClient.LOG.warn("Hearth host verification failed: {}", e.getClass().getSimpleName()); return CompletableFuture.completedFuture(null); }
        });
    }

    public static boolean verified(ServerLoginPacketListenerImpl handler, MinecraftServer server) {
        var session = HearthClient.session;
        return session == null || session.policy().get() == null || VERIFIED.containsKey(handler);
    }

    private static void reject(ServerLoginPacketListenerImpl handler) {
        VERIFIED.remove(handler);
        handler.disconnect(Component.translatable("hearth.profile_rejected"));
    }
}
