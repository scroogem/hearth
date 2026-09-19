use hearth_core::{
    storage::{StorageProvider, StorageConfig, GitConfig, GitCredentials, ProviderType},
    git_storage::GitStorage,
    manifest::{WorldManifest, ModLoader, LoaderType, GameplayRules},
    lock::WorldLock,
    snapshot::create_snapshot_from_world,
    mod_manager::ModManager,
    Error, Result,
};
use clap::{Parser, Subcommand};
use std::path::PathBuf;
use uuid::Uuid;
use tracing_subscriber::{fmt, EnvFilter};
use dirs;

#[derive(Parser)]
#[command(name = "hearth", version, about = "Hearth - Persistent P2P Worlds for Minecraft")]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    #[arg(long, global = true)]
    config: Option<PathBuf>,

    #[arg(long, global = true, default_value = "info")]
    log_level: String,
}

#[derive(Subcommand)]
enum Commands {
    Init {
        #[arg(long)]
        remote_url: String,

        #[arg(long)]
        branch: Option<String>,

        #[arg(long)]
        token: Option<String>,

        #[arg(long)]
        username: Option<String>,
    },

    World {
        #[command(subcommand)]
        command: WorldCommands,
    },

    Instance {
        #[command(subcommand)]
        command: InstanceCommands,
    },

    Snapshot {
        #[command(subcommand)]
        command: SnapshotCommands,
    },

    Lock {
        #[command(subcommand)]
        command: LockCommands,
    },

    Sync {
        world_id: Uuid,
    },

    List,
}

#[derive(Subcommand)]
enum WorldCommands {
    Create {
        name: String,
        #[arg(long)]
        mc_version: String,
        #[arg(long, default_value = "vanilla")]
        loader: String,
        #[arg(long)]
        loader_version: Option<String>,
    },
    Get {
        world_id: Uuid,
    },
    Update {
        world_id: Uuid,
        #[arg(long)]
        name: Option<String>,
    },
    Delete {
        world_id: Uuid,
    },
    List,
}

#[derive(Subcommand)]
enum InstanceCommands {
    Resolve {
        world_id: Uuid,
    },
    List,
    Launch {
        instance_id: String,
    },
}

#[derive(Subcommand)]
enum SnapshotCommands {
    Create {
        world_id: Uuid,
        #[arg(long)]
        world_path: PathBuf,
        #[arg(long)]
        parent: Option<String>,
    },
    List {
        world_id: Uuid,
    },
    Get {
        world_id: Uuid,
        version: String,
    },
    Apply {
        world_id: Uuid,
        version: String,
        #[arg(long)]
        target_path: PathBuf,
    },
}

#[derive(Subcommand)]
enum LockCommands {
    Acquire {
        world_id: Uuid,
        #[arg(long)]
        host_id: Uuid,
    },
    Release {
        world_id: Uuid,
        #[arg(long)]
        host_id: Uuid,
    },
    Heartbeat {
        world_id: Uuid,
        #[arg(long)]
        host_id: Uuid,
    },
    Status {
        world_id: Uuid,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    fmt()
        .with_env_filter(EnvFilter::new(&cli.log_level))
        .init();

    let config_path = cli.config.unwrap_or_else(|| {
        dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("hearth")
            .join("config.json")
    });

    let storage = create_storage(&config_path).await?;

    match cli.command {
        Commands::Init { remote_url, branch, token, username } => {
            cmd_init(&storage, remote_url, branch, token, username).await
        }
        Commands::World { command } => cmd_world(&storage, command).await,
        Commands::Instance { command } => cmd_instance(&storage, command).await,
        Commands::Snapshot { command } => cmd_snapshot(&storage, command).await,
        Commands::Lock { command } => cmd_lock(&storage, command).await,
        Commands::Sync { world_id } => cmd_sync(&storage, world_id).await,
        Commands::List => cmd_list(&storage).await,
    }
}

async fn create_storage(config_path: &PathBuf) -> Result<Box<dyn StorageProvider>> {
    if config_path.exists() {
        let content = tokio::fs::read_to_string(config_path).await?;
        let config: StorageConfig = serde_json::from_str(&content)?;
        hearth_core::storage::create_provider(&config)
    } else {
        let config = StorageConfig {
            provider_type: ProviderType::Git,
            git: Some(GitConfig {
                remote_url: String::new(),
                branch: "main".to_string(),
                credentials: None,
                local_path: None,
            }),
            s3: None,
            local: None,
        };
        hearth_core::storage::create_provider(&config)
    }
}

async fn cmd_init(
    storage: &dyn StorageProvider,
    remote_url: String,
    branch: Option<String>,
    token: Option<String>,
    username: Option<String>,
) -> Result<()> {
    let config = StorageConfig {
        provider_type: ProviderType::Git,
        git: Some(GitConfig {
            remote_url: remote_url.clone(),
            branch: branch.unwrap_or_else(|| "main".to_string()),
            credentials: if token.is_some() || username.is_some() {
                Some(GitCredentials { username, token, ssh_key_path: None })
            } else {
                None
            },
            local_path: None,
        }),
        s3: None,
        local: None,
    };

    storage.init(&config).await?;
    
    let config_path = config_path();
    let config_dir = config_path.parent().unwrap();
    tokio::fs::create_dir_all(config_dir).await?;
    tokio::fs::write(config_dir.join("config.json"), serde_json::to_string_pretty(&config)?).await?;

    println!("Initialized Hearth storage at {}", remote_url);
    Ok(())
}

async fn cmd_world(storage: &dyn StorageProvider, command: WorldCommands) -> Result<()> {
    match command {
        WorldCommands::Create { name, mc_version, loader, loader_version } => {
            let world_id = Uuid::new_v4();
            let owner_uuid = Uuid::new_v4();
            
            let mod_loader = ModLoader {
                loader_type: match loader.as_str() {
                    "fabric" => LoaderType::Fabric,
                    "forge" => LoaderType::Forge,
                    "neoforge" => LoaderType::NeoForge,
                    "quilt" => LoaderType::Quilt,
                    _ => LoaderType::Vanilla,
                },
                version: loader_version.unwrap_or_default(),
            };

            let manifest = WorldManifest::new(world_id, name.clone(), owner_uuid, mc_version, mod_loader);
            storage.create_world(&manifest).await?;
            println!("Created world: {} ({})", name, world_id);
        }
        WorldCommands::Get { world_id } => {
            let manifest = storage.get_manifest(world_id).await?;
            println!("{}", serde_json::to_string_pretty(&manifest)?);
        }
        WorldCommands::Update { world_id, name } => {
            let mut manifest = storage.get_manifest(world_id).await?;
            if let Some(name) = name {
                manifest.name = name;
            }
            storage.update_manifest(&manifest).await?;
            println!("Updated world: {}", world_id);
        }
        WorldCommands::Delete { world_id } => {
            println!("Delete not implemented yet");
        }
        WorldCommands::List => {
            let worlds = storage.list_worlds().await?;
            for w in worlds {
                println!("{} - {} (v{}) {}", w.world_id, w.name, w.current_version, 
                    if w.is_locked { "[LOCKED]" } else { "" });
            }
        }
    }
    Ok(())
}

async fn cmd_instance(storage: &dyn StorageProvider, command: InstanceCommands) -> Result<()> {
    let base_dir = dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("hearth");
    let mut manager = ModManager::new(base_dir)?;

    match command {
        InstanceCommands::Resolve { world_id } => {
            let manifest = storage.get_manifest(world_id).await?;
            let config = manager.resolve_instance(
                &world_id.to_string(),
                &manifest.minecraft_version,
                &manifest.mod_loader,
                &manifest.mods,
            ).await?;
            println!("Resolved instance: {}", config.instance_id);
            println!("Game dir: {}", config.game_dir.display());
        }
        InstanceCommands::List => {
            let instances = manager.list_instances()?;
            for inst in instances {
                println!("{} - MC {} ({})", inst.instance_id, inst.minecraft_version, inst.mod_loader.loader_type);
            }
        }
        InstanceCommands::Launch { instance_id } => {
            let instances = manager.list_instances()?;
            let config = instances.into_iter().find(|i| i.instance_id == instance_id)
                .ok_or_else(|| Error::Config(format!("Instance not found: {}", instance_id)))?;
            
            println!("Launching instance: {}", instance_id);
            let mut child = manager.launch_instance(&config).await?;
            child.wait()?;
        }
    }
    Ok(())
}

async fn cmd_snapshot(storage: &dyn StorageProvider, command: SnapshotCommands) -> Result<()> {
    match command {
        SnapshotCommands::Create { world_id, world_path, parent } => {
            let manifest = storage.get_manifest(world_id).await?;
            let snapshot = create_snapshot_from_world(&world_path, world_id, manifest.owner_uuid, parent)?;
            let version = storage.push_snapshot(world_id, &snapshot).await?;
            println!("Created snapshot: {}", version);
        }
        SnapshotCommands::List { world_id } => {
            let snapshots = storage.list_snapshots(world_id).await?;
            for s in snapshots {
                println!("{} - {} bytes, {} files", s.version, s.size_bytes, s.file_count);
            }
        }
        SnapshotCommands::Get { world_id, version } => {
            let snapshot = storage.get_snapshot(world_id, &version).await?;
            println!("{}", serde_json::to_string_pretty(&snapshot.metadata)?);
        }
        SnapshotCommands::Apply { world_id, version, target_path } => {
            let snapshot = storage.get_snapshot(world_id, &version).await?;
            hearth_core::snapshot::apply_snapshot(&snapshot, &target_path)?;
            println!("Applied snapshot {} to {}", version, target_path.display());
        }
    }
    Ok(())
}

async fn cmd_lock(storage: &dyn StorageProvider, command: LockCommands) -> Result<()> {
    match command {
        LockCommands::Acquire { world_id, host_id } => {
            let mut lock = storage.get_lock(world_id).await?;
            let conn_info = hearth_core::lock::ConnectionInfo {
                transport: hearth_core::lock::TransportType::Steam,
                steam_lobby_id: None,
                steam_session_id: None,
                direct_address: None,
                metadata: std::collections::HashMap::new(),
            };
            let acquired = lock.acquire(host_id, conn_info, 300).map_err(|e| Error::Lock(e))?;
            storage.update_lock(&lock).await?;
            println!("Acquired lock: session {}", acquired.session_id);
        }
        LockCommands::Release { world_id, host_id } => {
            let mut lock = storage.get_lock(world_id).await?;
            lock.release(host_id).map_err(|e| Error::Lock(e))?;
            storage.update_lock(&lock).await?;
            println!("Released lock for world: {}", world_id);
        }
        LockCommands::Heartbeat { world_id, host_id } => {
            let mut lock = storage.get_lock(world_id).await?;
            lock.heartbeat(host_id).map_err(|e| Error::Lock(e))?;
            storage.update_lock(&lock).await?;
            println!("Heartbeat sent");
        }
        LockCommands::Status { world_id } => {
            let lock = storage.get_lock(world_id).await?;
            if let Some(info) = lock.get_lock() {
                println!("Locked by: {} (session: {})", info.host_uuid, info.session_id);
                println!("Expires in: {:?}", info.time_remaining());
            } else {
                println!("World is not locked");
            }
        }
    }
    Ok(())
}

async fn cmd_sync(storage: &dyn StorageProvider, world_id: Uuid) -> Result<()> {
    println!("Sync not fully implemented yet");
    Ok(())
}

async fn cmd_list(storage: &dyn StorageProvider) -> Result<()> {
    let worlds = storage.list_worlds().await?;
    for w in worlds {
        println!("{} - {} (v{}) {}", w.world_id, w.name, w.current_version, 
            if w.is_locked { "[LOCKED]" } else { "" });
    }
    Ok(())
}

fn config_path() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("hearth")
        .join("config.json")
}