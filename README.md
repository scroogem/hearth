<div align="center">

# 🔥 Hearth

**Persistent P2P Worlds for Minecraft** — a distributed world-save system with
Git-backed storage, transport relays, and a snapshot history.

Rust workspace • Tokio • `git2` • Tauri CLI

[Workspace](#-workspace-crates) •
[Install](#-installation) •
[CLI](#-command-line) •
[Architecture](#-architecture) •
[License](#-license)

---

</div>

Hearth lets Minecraft worlds outlive any single player's machine. Instead of
uploading saves to a server you trust, Hearth stores world snapshots as Git
history and syncs them peer-to-peer through optional relay/Steam transport layers.

The core workspace is **storage-agnostic**: local filesystem, S3-compatible
object storage, or any Git remote can act as the source of truth.

## 🧱 Workspace crates

| Crate | Role |
|---|---|
| `hearth-core` | Domain model: manifests, snapshots, locks, mod managers, storage providers |
| `hearth-cli` | Command-line interface for worlds, instances, snapshots, and sync |
| `hearth-transport` | Transport layer: direct TCP, relay, Steam networking |
| `hearth-tauri` | Desktop (Tauri) shell for the CLI tooling |

### `hearth-core` highlights

- **Storage providers** — pluggable `StorageProvider` trait with local, S3, and
  Git backends (`GitStorage`, `S3Storage`, `LocalStorage`).
- **World manifests** — machine-readable world metadata with mod-loader and
  gameplay-rule declarations (`Vanilla`, `Fabric`, `Forge`, …).
- **Snapshots** — immutable, versioned world states with parent/child history;
  `apply` restores a snapshot to any target path.
- **World locking** — cooperating players take a lock before editing so two
  machines never clobber the same version.
- **Mod manager** — resolves and wires mods to an instance.

## 🔧 Installation

```bash
# Build the whole workspace
cargo build --release

# Run the CLI
cargo run -p hearth-cli -- --help
```

Requires Rust **1.70+** and `build-essential` (for `git2`/libgit2).

## 🖥️ Command line

```
hearth init --remote-url <git> [--branch main] [--token …] [--username …]
hearth world create <name> --mc-version 1.21 [--loader fabric] [--loader-version …]
hearth world list | get <id> | update <id> | delete <id> | sync <world-id>
hearth instance resolve <world-id> | launch <instance-id>
hearth snapshot create <world-id> --world-path <path> [--parent <version>]
hearth snapshot list <world-id> | get <world-id> <version> | apply <world-id> <version> --target-path <path>
hearth lock            # take/release a cooperative edit lock
```

## 🏗️ Architecture

```
                ┌───────────────────────────────┐
                │           hearth-cli           │
                └──────────────┬────────────────┘
                               │
                ┌──────────────▼────────────────┐
                │           hearth-core          │
                │────────────────────────────────│
                │  Manifest · Snapshot · Lock     │
                │  ModManager · StorageProvider   │
                └──────┬───────────────┬─────────┘
                       │               │
               ┌───────▼─────┐   ┌─────▼──────────┐
               │   Storage   │   │   Transport    │
               │ local · S3  │   │ direct·relay    │
               │ git-storage │   │ steam          │
               └─────────────┘   └───────────────┘
```

- **`hearth-core`** defines a world's lifecycle: create → edit (locked) →
  snapshot → sync → apply → replay.
- **`hearth-transport`** decouples "where the bytes live" (Git/S3/local) from
  "how they travel" (direct TCP, relay, Steam), so peers can sync even when one
  machine is behind NAT.
- **`hearth-tauri`** wraps the same core as a desktop app.

## 🔒 Security model

- Locks are cooperative (documented), shared through the storage layer.
- Git credentials (`--token`, `--username`) are accepted per-command and can be
  persisted in a local config file — never hardcoded.
- SSH-style trust: you only sync with peers/repos you explicitly configure.

## 📜 License

MIT — © Hearth Team.