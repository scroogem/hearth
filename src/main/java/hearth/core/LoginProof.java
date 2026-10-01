package hearth.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Domain-separated signatures bind a fresh nonce to this world and both participants. */
public final class LoginProof {
    private LoginProof() {}

    public static byte[] host(String worldId, byte[] nonce, String hostKey) {
        return message("hearth-host-v1", worldId, nonce, hostKey, "");
    }
    public static byte[] guest(String worldId, byte[] nonce, String hostKey, String guestKey) {
        return message("hearth-guest-v1", worldId, nonce, hostKey, guestKey);
    }
    public static byte[] guest(String worldId, byte[] nonce, String hostKey, String guestKey, String manifest) {
        return message("hearth-guest-v2", worldId, nonce, hostKey, guestKey + "\n" + manifest);
    }
    private static byte[] message(String domain, String worldId, byte[] nonce, String hostKey, String guestKey) {
        if (nonce.length != 32) throw new IllegalArgumentException("Invalid login challenge");
        return (domain + "\n" + worldId + "\n" + Base64.getEncoder().encodeToString(nonce) + "\n"
                + hostKey + "\n" + guestKey).getBytes(StandardCharsets.UTF_8);
    }
}
