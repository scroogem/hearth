package hearth.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Identifies exactly which world bytes a permitted participant published. It does not prove honest gameplay. */
public record WorldSnapshot(String worldId, String publicKey, String digest, long atMillis, String signature) {
    private static byte[] message(String worldId, String publicKey, String digest, long atMillis) {
        return ("hearth-save-v1\n" + worldId + "\n" + publicKey + "\n" + digest + "\n" + atMillis)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static WorldSnapshot sign(WorldPolicy policy, GitSsh.Access author, String digest, long now) throws IOException {
        if (policy.member(WorldPolicy.identity(author.publicKey())) == null) throw new IOException("Unknown world author");
        try { return new WorldSnapshot(policy.worldId(), author.publicKey(), digest, now,
                WorldPolicy.signBytes(author, message(policy.worldId(), author.publicKey(), digest, now))); }
        catch (Exception e) { throw new IOException("Could not sign the world save", e); }
    }

    public void verify(WorldPolicy policy, String actualDigest) throws IOException {
        try {
            if (!policy.worldId().equals(worldId) || publicKey == null || digest == null
                    || !digest.matches("[a-f0-9]{64}") || !digest.equals(actualDigest)
                    || policy.member(WorldPolicy.identity(publicKey)) == null
                    || !WorldPolicy.verifyBytes(publicKey, message(worldId, publicKey, digest, atMillis), signature)) {
                throw new IOException("The world save was changed outside its signed checkpoint");
            }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid world-save signature"); }
    }
}
