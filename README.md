# Hearth

Hearth is a Fabric mod for Minecraft Java 26.2. A shared world is stored as versions in a Git repository. One player hosts it on their own computer while playing; friends connect through e4steam. When the host leaves, Hearth saves the world to the repository so another player can host the next session. Hearth has no coordinator or permanently running Minecraft server.

## Current prototype

- Share an existing singleplayer world through an empty private Git repository.
- Add a shared world with an invite, then host it from the in-game Shared worlds screen.
- Use a Git-backed lock and heartbeat so one player hosts a world at a time.
- Save changed world files and deletions to Git on exit. Recover a late host's divergent save on a separate branch.
- Open the hosted world to LAN; e4steam carries the gameplay connection over Steam. Other participants can use Join when the host's Steam address appears.
- Keep the same Steam player profile when hosting and joining, including inventory, position, advancements, and statistics. An existing local singleplayer profile is copied when that player's Steam profile does not exist yet.

All players need Minecraft 26.2, Fabric Loader, Fabric API, e4steam 0.3.2 or newer, and a signed-in Steam client. e4steam must be installed separately on every player's computer. Hearth does not bundle it. The host and guests also need access to the Git repository used for the world.

## Install and play

1. Install Fabric Loader for Minecraft 26.2 and add Fabric API, the [e4steam 0.3.2 modern JAR](https://github.com/Kamilhik/e4steam/releases/tag/v0.3.2), and `hearth-0.1.1.jar` to the same Minecraft profile's `mods` folder. Hearth and e4steam are client mods; each participant installs both. Keep Steam running and signed in before launching Minecraft.
2. Create an empty private GitHub repository for the world. Use a fine-grained token scoped to that repository with **Contents: Read and write** for HTTPS Git access. Hearth currently puts this same token in the invite, so everyone who receives the invite can read and write that repository.
3. In Minecraft, open **Singleplayer → Shared worlds → Add a world**. Choose an existing local world, enter the empty repository URL and token, then share it. Copy its **Invite** from the shared-world list and send it privately to another participant.
4. Guests open **Shared worlds → Add a world**, paste the invite, and add it. The current host uses **Host**; Steam friends of the host can use **Join** when the Steam address appears. When the host leaves the world, wait for Hearth to finish saving before closing Minecraft. Another participant can then host the next session.

Hearth itself does not run a server outside the host's computer. GitHub stores world versions and the session heartbeat; Steam carries the live multiplayer connection. The same mod JAR is used on macOS and Windows.

The shared-world invite currently contains the repository URL and its access token. The invite is Base64-encoded, **not encrypted**. Use only a fine-grained token scoped to that one world repository, and share invites only with people you trust. Member permissions and per-player tokens are not implemented yet.

## Build and test

The Gradle wrapper uses Java 25:

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew test
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The unit tests exercise Git storage, locking, takeover, recovery, and file synchronization against a local bare repository. The client gametest exercises sharing, hosting, Steam address publication, saving, the lock guard, profile migration, and inventory/position persistence across host sessions with a signed-in Steam client and e4steam installed.

A live two-computer check on 2026-09-28 verified Windows joining a macOS host over Steam, macOS joining the next Windows host, and both hosts saving the world and releasing the lock. The test used a temporary local Git remote for world storage. GitHub Actions also runs the unit tests and packages the mod on macOS and Windows.

## Known gaps

The prototype has no UUID-based member whitelist, permissions UI, automatic checkpoints, or provider abstraction. The e4steam addon API does not currently expose the active integrated-world join address, so `SteamBridge` reads that address through a small compatibility adapter against e4steam 0.3.2. Player identity uses the public e4steam addon API.
