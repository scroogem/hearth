package hearth.steam;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import hearth.core.LocalSecrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Loads only the pinned Steam API library; JNA already ships with Minecraft. */
final class SteamNative implements AutoCloseable {
    interface Api extends Library {
        int SteamAPI_InitFlat(Pointer error);
        void SteamAPI_RunCallbacks();
        void SteamAPI_Shutdown();
        byte SteamAPI_IsSteamRunning();
        Pointer SteamAPI_SteamUser_v023();
        Pointer SteamAPI_SteamUtils_v010();
        long SteamAPI_ISteamUser_GetSteamID(Pointer user);
        int SteamAPI_ISteamUtils_GetAppID(Pointer utils);
    }

    final Api api;
    final Path library;
    final long steamId;

    SteamNative(Path gameDir) throws IOException {
        gameDir = gameDir.toRealPath();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64"), x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64 && !(os.contains("mac") && arm)) throw new IOException("Steam relay requires Windows/Linux x64 or macOS x64/arm64");
        String name, hash;
        if (os.contains("win")) { name = "steam_api64.dll"; hash = "e082bf5c9f881c822b1540a76b74f9d15e18019a73ebd206a559595badcb7f65"; }
        else if (os.contains("mac")) { name = "libsteam_api.dylib"; hash = "d828cbd6365daa2b16d3bd7dc2969c60e97f497c0996a6def0b7b174a7e19953"; }
        else if (os.contains("linux")) { name = "libsteam_api.so"; hash = "0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc"; }
        else throw new IOException("Unsupported Steam platform");
        Path cache = gameDir.resolve("hearth/native/" + hash).toAbsolutePath().normalize();
        Files.createDirectories(cache);
        if (!cache.toRealPath().equals(cache) || !Files.getOwner(cache).equals(Files.getOwner(gameDir))) throw new IOException("Unsafe Steam native cache");
        var posix = Files.getFileAttributeView(cache, java.nio.file.attribute.PosixFileAttributeView.class);
        if (posix != null) posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        library = cache.resolve(name);
        if (!Files.exists(library, LinkOption.NOFOLLOW_LINKS)) {
            try (var source = SteamNative.class.getResourceAsStream("/hearth/steam/" + name)) {
                if (source == null) throw new IOException("Missing bundled Steam library");
                byte[] content = source.readAllBytes();
                if (!digest(content).equals(hash)) throw new IOException("Bundled Steam library checksum mismatch");
                LocalSecrets.write(library, content);
            }
        }
        if (!Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(library).equals(Files.getOwner(cache))
                || !digest(Files.readAllBytes(library)).equals(hash)) throw new IOException("Steam native cache checksum mismatch");
        Path appId = Path.of(System.getProperty("user.dir")).resolve("steam_appid.txt");
        if (Files.exists(appId) && !Files.readString(appId).strip().equals("480")) throw new IOException("Another Steam App ID is configured in this Minecraft directory");
        if (!Files.exists(appId)) Files.writeString(appId, "480\n");
        boolean initialized = false;
        Api loaded = null;
        try {
            loaded = Native.load(library.toString(), Api.class);
            api = loaded;
            try (var error = new Memory(1024)) {
                error.clear();
                if (api.SteamAPI_InitFlat(error) != 0) throw new IOException("Sign in to the desktop Steam client to use the relay");
            }
            initialized = true;
            Pointer user = api.SteamAPI_SteamUser_v023(), utils = api.SteamAPI_SteamUtils_v010();
            if (user == null || utils == null || api.SteamAPI_ISteamUtils_GetAppID(utils) != 480) {
                throw new IOException("Steam did not initialize the Hearth development relay");
            }
            steamId = api.SteamAPI_ISteamUser_GetSteamID(user);
            if (steamId == 0) { throw new IOException("Steam has no signed-in profile"); }
        } catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
            if (initialized && loaded != null) loaded.SteamAPI_Shutdown();
            if (e instanceof IOException io) throw io;
            throw new IOException("Steam native initialization failed", e);
        }
    }

    private static String digest(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.GeneralSecurityException e) { throw new IOException(e); }
    }
    @Override public void close() { api.SteamAPI_Shutdown(); }
}
