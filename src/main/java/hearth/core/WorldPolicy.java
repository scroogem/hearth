package hearth.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Owner-signed rules. A participant holding a save or a repository key cannot issue new rules. */
public record WorldPolicy(String worldId, String ownerKey, long revision, Settings settings,
                          List<Member> members, List<String> bannedIps, Map<String, String> mods) {
    private static final Gson JSON = new Gson();

    public record Trust(String worldId, String ownerKey, String policyUrl, GitSsh.Access policyAccess) {
        public Trust(String worldId, String ownerKey) { this(worldId, ownerKey, null, null); }
    }
    public record Member(String publicKey, String name, boolean canHost, boolean banned, boolean operator) {
        public Member(String publicKey, String name, boolean canHost, boolean banned) { this(publicKey, name, canHost, banned, false); }
        public UUID uuid() { return identity(publicKey); }
    }
    public record Settings(String gameMode, String difficulty, boolean allowCreative, boolean allowModeChanges, boolean allowClientMods,
                           boolean cheats, Map<String, String> gameRules) {
        public static Settings defaults() {
            return new Settings("survival", "normal", false, false, false, false, Map.of());
        }
    }
    public record Signed(String payload, String signature) {}

    public static UUID identity(String publicKey) {
        return UUID.nameUUIDFromBytes(("hearth-profile:" + publicKey).getBytes(StandardCharsets.UTF_8));
    }

    public UUID ownerUuid() { return identity(ownerKey); }
    public Trust trust() { return new Trust(worldId, ownerKey); }

    public Member member(UUID uuid) {
        return members.stream().filter(m -> m.uuid().equals(uuid)).findFirst().orElse(null);
    }

    public boolean canHost(UUID uuid) {
        Member member = member(uuid);
        return member != null && !member.banned() && member.canHost();
    }

    public boolean canUseCommands(UUID uuid) {
        Member member = member(uuid);
        return member != null && !member.banned() && (ownerUuid().equals(uuid) || settings.cheats() && member.operator());
    }

    public static WorldPolicy initial(GitSsh.Access owner, String name, Map<String, String> mods) {
        return new WorldPolicy(UUID.randomUUID().toString(), owner.publicKey(), 1, Settings.defaults(),
                List.of(new Member(owner.publicKey(), name, true, false)), List.of(), new TreeMap<>(mods));
    }

    public WorldPolicy addMember(GitSsh.Access key, String name) {
        List<Member> updated = new ArrayList<>(members);
        if (updated.stream().anyMatch(m -> m.publicKey().equals(key.publicKey()))) throw new IllegalArgumentException("Member already invited");
        updated.add(new Member(key.publicKey(), name, true, false));
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings, updated, bannedIps, mods);
    }

    public WorldPolicy ban(String publicKey, boolean banned) {
        if (ownerKey.equals(publicKey)) throw new IllegalArgumentException("The creator cannot be banned");
        if (members.stream().noneMatch(m -> m.publicKey().equals(publicKey))) throw new IllegalArgumentException("Unknown member");
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings,
                members.stream().map(m -> m.publicKey().equals(publicKey)
                        ? new Member(m.publicKey(), m.name(), m.canHost(), banned, m.operator()) : m).toList(), bannedIps, mods);
    }

    public WorldPolicy withMember(String publicKey, String name, boolean operator) {
        if (ownerKey.equals(publicKey)) throw new IllegalArgumentException("The creator's role cannot be changed");
        if (name == null || name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a member name (1–64 characters)");
        if (members.stream().noneMatch(m -> m.publicKey().equals(publicKey))) throw new IllegalArgumentException("Unknown member");
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings,
                members.stream().map(m -> m.publicKey().equals(publicKey)
                        ? new Member(m.publicKey(), name.strip(), m.canHost(), m.banned(), operator) : m).toList(), bannedIps, mods);
    }

    public WorldPolicy withSettings(Settings settings) {
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings, members, bannedIps, mods);
    }

    public WorldPolicy withMods(Map<String, String> mods) {
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings, members, bannedIps, new TreeMap<>(mods));
    }

    public WorldPolicy banIp(String ip, boolean banned) {
        ip = normalizeIp(ip);
        var updated = new TreeSet<>(bannedIps);
        if (banned) updated.add(ip); else updated.remove(ip);
        return new WorldPolicy(worldId, ownerKey, revision + 1, settings, members, List.copyOf(updated), mods);
    }

    public static String normalizeIp(String ip) {
        if (ip == null || ip.length() > 45) throw new IllegalArgumentException("Invalid IP address");
        return java.net.InetAddress.ofLiteral(ip).getHostAddress();
    }

    public Signed sign(GitSsh.Access owner) throws Exception {
        validate();
        if (!ownerKey.equals(owner.publicKey())) throw new IOException("Only the creator can change world rules");
        String payload = JSON.toJson(this);
        return new Signed(payload, signBytes(owner, payload.getBytes(StandardCharsets.UTF_8)));
    }

    public static WorldPolicy verify(Signed signed, Trust trust) throws IOException {
        if (trust == null || signed == null || signed.payload() == null || signed.payload().length() > 1_000_000) {
            throw new IOException("Missing signed world rules");
        }
        try {
            if (!verifyBytes(trust.ownerKey(), signed.payload().getBytes(StandardCharsets.UTF_8), signed.signature())) {
                throw new IOException("The world rules were changed without the creator's signature");
            }
            WorldPolicy policy = JSON.fromJson(signed.payload(), WorldPolicy.class);
            if (policy == null || !Objects.equals(policy.worldId(), trust.worldId())
                    || !Objects.equals(policy.ownerKey(), trust.ownerKey())) throw new IOException("These rules belong to another world");
            policy.validate();
            return policy;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid signed world rules"); }
    }

    public static String signBytes(GitSsh.Access key, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.keyPair().getPrivate());
        signature.update(data);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    public static boolean verifyBytes(String publicKey, byte[] data, String proof) throws Exception {
        if (publicKey == null || publicKey.length() > 2048 || proof == null || proof.length() > 1024) return false;
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(Base64.getDecoder().decode(proof));
    }

    private void validate() throws IOException {
        try { UUID.fromString(worldId); } catch (Exception e) { throw new IOException("Invalid world identifier"); }
        if (ownerKey == null || ownerKey.length() > 2048 || revision < 1 || settings == null
                || members == null || members.isEmpty() || members.size() > 1000 || bannedIps == null
                || bannedIps.size() > 1000 || mods == null || mods.size() > 1000) throw new IOException("Invalid world rules");
        if (!Set.of("survival", "creative", "adventure", "spectator").contains(settings.gameMode())
                || !Set.of("peaceful", "easy", "normal", "hard").contains(settings.difficulty())
                || settings.gameRules() == null || settings.gameRules().size() > 1000) throw new IOException("Invalid world settings");
        if ("creative".equals(settings.gameMode()) && !settings.allowCreative()) throw new IOException("Creative mode is disabled by these rules");
        for (var rule : settings.gameRules().entrySet()) {
            if (rule.getKey() == null || !rule.getKey().matches("[A-Za-z0-9_:.-]{1,100}")
                    || rule.getValue() == null || !rule.getValue().matches("(?:true|false|-?[0-9]{1,10})")) throw new IOException("Invalid game rule");
            if (!rule.getValue().equals("true") && !rule.getValue().equals("false")) {
                try { Integer.parseInt(rule.getValue()); } catch (NumberFormatException e) { throw new IOException("Game rule is outside the integer range"); }
            }
        }
        Set<String> keys = new HashSet<>();
        for (Member member : members) {
            if (member == null || member.publicKey() == null || member.publicKey().length() > 2048
                    || member.name() == null || member.name().isBlank() || member.name().length() > 255
                    || !keys.add(member.publicKey())) throw new IOException("Invalid world member");
        }
        Member owner = member(ownerUuid());
        if (owner == null || owner.banned() || !owner.canHost()) throw new IOException("Missing world creator");
        for (String ip : bannedIps) {
            try { if (!normalizeIp(ip).equals(ip)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid IP ban"); }
        }
        for (var mod : mods.entrySet()) if (!mod.getKey().matches("[a-z0-9_.-]{1,100}")
                || mod.getValue() == null || mod.getValue().length() > 200) throw new IOException("Invalid mod manifest");
    }
}
