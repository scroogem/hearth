package hearth.mc;

import hearth.core.WorldPolicy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Enforces the creator's verified rules on an unmodified participant host. */
public final class WorldRules {
    private static WorldPolicy applied;

    static GameRules available() { return new GameRules(FeatureFlags.DEFAULT_FLAGS); }

    static String peerIp(java.net.SocketAddress address) {
        if (!(address instanceof java.net.InetSocketAddress socket) || socket.getAddress() == null
                || socket.getAddress().isLoopbackAddress() || socket.getAddress().isAnyLocalAddress()) return null;
        return socket.getAddress().getHostAddress();
    }

    static boolean ipBlocked(WorldPolicy policy, java.net.SocketAddress address, java.util.UUID player) {
        String ip = peerIp(address);
        return ip != null && !policy.ownerUuid().equals(player) && policy.bannedIps().contains(ip);
    }

    static WorldPolicy.Settings capture(Path world) throws IOException {
        var data = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.create(16 * 1024 * 1024)).getCompoundOrEmpty("Data");
        var mode = GameType.byId(data.getIntOr("GameType", 0));
        var difficulty = data.read("difficulty_settings", LevelSettings.DifficultySettings.CODEC)
                .orElse(LevelSettings.DifficultySettings.DEFAULT).difficulty();
        Map<String, String> values = new TreeMap<>();
        Path ruleFile = world.resolve("data/minecraft/game_rules.dat");
        if (Files.isRegularFile(ruleFile)) {
            var rules = NbtIo.readCompressed(ruleFile, NbtAccounter.create(1024 * 1024)).getCompoundOrEmpty("data");
            for (var rule : available().availableRules().toList()) {
                var value = rules.get(net.minecraft.core.registries.BuiltInRegistries.GAME_RULE.getKey(rule).toString());
                if (value != null) values.put(rule.id(), rule.valueCodec().parse(net.minecraft.nbt.NbtOps.INSTANCE, value).getOrThrow().toString());
                else values.put(rule.id(), rule.defaultValue().toString());
            }
        } else available().availableRules().forEach(rule -> values.put(rule.id(), rule.defaultValue().toString()));
        return new WorldPolicy.Settings(mode.getName(), difficulty.getSerializedName(), mode == GameType.CREATIVE,
                false, false, data.getBooleanOr("allowCommands", false), values);
    }

    static void validate(WorldPolicy.Settings settings) throws IOException {
        var known = available().availableRules().collect(java.util.stream.Collectors.toMap(GameRule::id, rule -> rule));
        for (var value : settings.gameRules().entrySet()) {
            var rule = known.get(value.getKey());
            if (rule == null || rule.deserialize(value.getValue()).result().isEmpty())
                throw new IOException("Invalid game rule: " + value.getKey());
        }
    }

    public static WorldPolicy policy(MinecraftServer server) {
        var session = HearthClient.session;
        if (session == null || !(server instanceof net.minecraft.client.server.IntegratedServer)) return null;
        var directory = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        return directory.equals(session.world().worldDir().toAbsolutePath().normalize()) ? session.policy().get() : null;
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (policy(server) != null) {
                // Hearth's signed challenge authenticates both official and offline launcher profiles.
                server.setUsesAuthentication(false);
                applied = null;
                apply(server, policy(server));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WorldPolicy policy = policy(server);
            if (policy == null) return;
            if (policy != applied || server.getTickCount() % 20 == 0) apply(server, policy);
            for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
                var member = policy.member(player.getUUID());
                if (member == null || member.banned() || ipBlocked(policy,
                        ((hearth.mc.mixin.PlayConnectionAccess) player.connection).hearth$connection().getRemoteAddress(), player.getUUID())) {
                    player.connection.disconnect(Component.translatable("hearth.profile_rejected"));
                    continue;
                }
                if (player.getUUID().equals(policy.ownerUuid())) continue;
                var mode = player.gameMode.getGameModeForPlayer();
                if (server.isHardcore() && mode == GameType.SPECTATOR) continue;
                if ((!policy.settings().allowModeChanges() && mode != GameType.byName(policy.settings().gameMode()))
                        || (!policy.settings().allowCreative() && mode == GameType.CREATIVE)) {
                    player.setGameMode(GameType.byName(policy.settings().gameMode()));
                }
            }
        });
    }

    private static void apply(MinecraftServer server, WorldPolicy policy) {
        var mode = GameType.byName(policy.settings().gameMode());
        var difficulty = Difficulty.byName(policy.settings().difficulty());
        if (server.getDefaultGameType() != mode) server.setDefaultGameType(mode);
        if (server.getWorldData().getDifficulty() != difficulty) server.setDifficulty(difficulty, true);
        if (!server.getWorldData().isDifficultyLocked()) server.setDifficultyLocked(true);
        server.getGameRules().availableRules().forEach(rule -> {
            String value = policy.settings().gameRules().get(rule.id());
            if (value != null) applyRule(server, rule, value);
        });
        if (policy != applied) for (ServerPlayer player : server.getPlayerList().getPlayers()) server.getPlayerList().sendPlayerPermissionLevel(player);
        applied = policy;
    }

    private static <T> void applyRule(MinecraftServer server, GameRule<T> rule, String value) {
        T parsed = rule.deserialize(value).getOrThrow();
        if (!server.getGameRules().get(rule).equals(parsed)) server.getGameRules().set(rule, parsed, server);
    }

    public static boolean modeAllowed(ServerPlayer player, GameType mode) {
        var policy = policy(player.level().getServer());
        if (policy == null || player.getUUID().equals(policy.ownerUuid())) return true;
        return (mode != GameType.CREATIVE || policy.settings().allowCreative())
                && (policy.settings().allowModeChanges() || mode == GameType.byName(policy.settings().gameMode()));
    }
}
