# Hearth

Hearth is a Fabric mod for Minecraft Java 26.2. A shared world is stored as versions in a Git repository. One player hosts it on their own computer while playing; friends connect through e4steam. When the host leaves, Hearth saves the world to the repository so another player can host the next session. Hearth has no coordinator or permanently running Minecraft server.

## Current prototype

- Share an existing singleplayer world through an empty private Git repository.
- Add a shared world with an invite, then host it from the in-game Shared worlds screen.
- Use a Git-backed lock and heartbeat so one player hosts a world at a time.
- Save changed world files and deletions to Git on exit. Recover a late host's divergent save on a separate branch.
- Open the hosted world to LAN; e4steam carries the gameplay connection over Steam. Other participants can use Join when the host's Steam address appears.

All players need Minecraft 26.2, Fabric Loader, Fabric API, e4steam 0.3.2 or newer, and a signed-in Steam client. e4steam must be installed separately on every player's computer. Hearth does not bundle it. The host and guests also need access to the Git repository used for the world.

The shared-world invite currently contains the repository URL and its access token. The invite is Base64-encoded, **not encrypted**. Use only a fine-grained token scoped to that one world repository, and share invites only with people you trust. Member permissions and per-player tokens are not implemented yet.

## Build and test

The Gradle wrapper uses Java 25:

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew test
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The unit tests exercise Git storage, locking, takeover, recovery, and file synchronization against a local bare repository. A client gametest exists under `src/gametest`, but an end-to-end test of Steam Host/Join requires two signed-in Steam clients with e4steam installed. That path has not yet been verified here.

## Known gaps

The prototype has no UUID-based member whitelist, permissions UI, automatic checkpoints, or provider abstraction. The e4steam addon API does not currently expose the active integrated-world join address, so `SteamBridge` reads that address through a small compatibility adapter against e4steam 0.3.2. This needs a real two-player compatibility test before release.
