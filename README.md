# Hearth

A Fabric client mod for Minecraft Java 26.2. The person playing hosts the shared world on their computer. GitHub retains saves while every participant is offline; the next player downloads the latest save and becomes host. Hearth runs no permanent game server or coordination backend.

## Current development build

`0.2.0-alpha.3` includes the registered public GitHub OAuth client ID. Creator device-code sign-in, token refresh without an application secret, native world creation/upload and UI invitation creation have passed against real GitHub storage.

- Native Minecraft screens: create a world, share an existing world, paste an invite, then use **Play** to host or join.
- Creator signs in to GitHub once. Friends receive separate world SSH deploy keys and do not need GitHub accounts. The creator's account token stays on their PC and is excluded from invites.
- Private world storage and a separate private repository for creator-signed rules. Friends can read rules, but their GitHub keys cannot write them.
- Signed participant login, stable per-world profiles, inventories and statistics across host changes. Both official and offline launcher identities use the same Hearth invitation profile.
- Creator settings for game mode, difficulty, creative, participant mode changes, operator commands, client mods, game rules and the approved mod versions.
- Separate creator, operator and player roles. Operators receive level 2 game commands only when enabled; they cannot manage access, creator rules or rollback. Hosting never grants the creator role. Players can inspect rules and use Hearth from the pause menu while connected to a friend.
- Per-invite profile bans, access revocation and restoration, plus direct-connection IP bans. Steam relay connections hide the original IP, so use profile bans there.
- Signed saves, two-minute checkpoints, session history and creator rollback as a new save. Current membership and rules survive a world rollback.
- A Git lock and heartbeat prevent simultaneous hosting. Interrupted initial uploads can be retried; late divergent progress goes to a recovery branch.
- Bundled Steam API libraries and the necessary e4steam message transport/TCP bridge. A separate e4steam install is optional. Local-network hosting works without Steam.

### Remaining release gates

This is a development build, not a completed public release:

1. Test the bundled Steam bridge between macOS and Windows with separate signed-in Steam accounts. The old external e4steam bridge passed this two-computer cycle. The new bundled bridge has passed native macOS initialization, protocol tests and a real Minecraft host publishing its Steam address and saving back to GitHub; a second Steam account has not connected to it yet.
2. Steam App ID 480 is for development testing. A public release needs the applicable Valve agreement and a production App ID.

## Installation and intended flow

Use Minecraft 26.2, Java 25 or newer, Fabric Loader 0.19.5 or newer, Fabric API and the Hearth JAR in the same profile. Use the same approved world mod versions on every computer. The JAR supports macOS x64/arm64 and Windows x64. Windows ARM and older Minecraft versions are not supported by this build.

1. Open **Singleplayer → Shared worlds → Add a world**. Choose an existing world or **Create new world**, using Minecraft's native creation screen.
2. The creator signs in to GitHub using the displayed device code, then creates the shared world. Hearth prepares private repositories and starts the world.
3. Open the world's details, name the friend and copy an invite, and send it privately to a friend. Friends paste it under **Add a world**, then press **Play**. Issue a separate invite to each participant.
4. Keep the desktop Steam app signed in for internet connections, or select local network on the host for computers on the same network.
5. Leave the world and wait for the upload to finish before shutting down. Any invited participant can start the next session with the latest uploaded save.
6. The creator uses **Settings**, **Access** and **History → Save details → Restore** in world details. World details are available from every participant’s pause menu. Assign operators and rename members under **Members and permissions**; enable **Operator commands** in world settings to let them use commands. The creator always retains full authority.

Invites contain private, revocable access keys for that world and its read-only rules repository. They are encoded, not encrypted. Anyone with a copied invite can use that invitation profile until its access is revoked.

### Upgrading an existing world

Install the new Hearth JAR on every participating computer. The creator opens **World settings → Approve this mod set** before playing on the upgraded version. Hearth’s version always stays pinned, including worlds that allow extra client mods. Old invitations remain valid. Existing members start without operator permission; the creator assigns operators explicitly. Vanilla `/op`, `ops.json` and physical hosting do not grant Hearth roles. Change canonical game rules, difficulty and default mode through Hearth settings.

## Trust and storage limits

Creator signatures and GitHub's separate write permissions protect the canonical rules. They do not make a participant's computer trustworthy: someone who modifies the host mod, native libraries, NBT or gameplay can fabricate a save and sign it with their own invitation key. Signatures identify a key; they cannot prove honest gameplay. A world writer can also damage the world repository/history. Keep owner backups for recovery. Full protection against a malicious host requires an independent trusted authority, which this architecture does not have.

Profiles identify individual invitation keys, not a globally verified Steam or Microsoft account. Ban and unban operate on those profiles. IP bans apply only when a direct connection exposes the guest's IP.

GitHub storage is finite. Large region files can exceed GitHub's per-file limit, and history grows as the world changes. Automated retention and large-world storage are not implemented. GitHub remains necessary for obtaining the latest world when every participant is offline.

The OAuth device flow requests the GitHub `repo` scope for the creator's private repositories. The local token is protected by current-user file permissions/Windows ACLs, not an OS credential vault. Each guest receives only the keys scoped to their world.

## Build and checks

```sh
JAVA_HOME=/path/to/jdk-25 ./gradlew test build
```

GitHub Actions runs unit tests and builds on macOS and Windows. Tests cover binary Git synchronization, locks, simultaneous starts, interrupted uploads, checkpoints, crash recovery, signed rules/saves, rollback, key validation, invite boundaries and the bundled Steam wire/native-buffer handling.

The preceding `0.2.0-alpha.2` build passed both operating-system jobs in [GitHub Actions run 37152335821](https://github.com/scroogem/hearth/actions/runs/37152335821). The live macOS two-client checks passed participant hosting, creator logout while a friend kept playing, signed game-rule enforcement, persisted world changes, recorded participants and creator-only rollback against private GitHub storage.

Client drivers in `src/gametest` launch ordinary Minecraft instances to verify native creation/settings capture, GitHub device-code UI, shared-world creation and separate invitation keys, signed peer login, host handoff, shutdown ownership, persisted world changes, live operator grants/demotion, command suggestions and GUI layouts at two scales. They are not packaged in the normal Hearth JAR. The existing client gametest additionally checks compatibility with external e4steam and legacy saves.

See [native UI review](docs/native-ui-review.md) and [third party notices](THIRD_PARTY_NOTICES.md).
