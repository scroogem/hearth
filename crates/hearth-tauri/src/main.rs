use hearth_core::{
    storage::{StorageProvider, StorageConfig, GitConfig, GitCredentials, ProviderType},
    manifest::WorldManifest,
    mod_manager::ModManager,
};
use tauri::{State};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;
use uuid::Uuid;
use dirs;

struct AppState {
    storage: Mutex<Option<Arc<dyn StorageProvider + Send + Sync>>>,
    mod_manager: Mutex<Option<ModManager>>,
}

#[tauri::command]
async fn init_storage(
    remote_url: String,
    branch: Option<String>,
    token: Option<String>,
    username: Option<String>,
    state: State<'_, AppState>,
) -> std::result::Result<String, String> {
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

    let storage = hearth_core::storage::create_provider(&config)
        .map_err(|e| e.to_string())?;
    
    storage.init(&config).await.map_err(|e| e.to_string())?;
    
    let base_dir = dirs::data_dir().unwrap_or_else(|| PathBuf::from(".")).join("hearth");
    let manager = ModManager::new(base_dir).map_err(|e| e.to_string())?;
    
    *state.storage.lock().await = Some(Arc::new(storage));
    *state.mod_manager.lock().await = Some(manager);
    
    Ok("Storage initialized".to_string())
}

#[tauri::command]
async fn list_worlds(state: State<'_, AppState>) -> std::result::Result<Vec<WorldSummary>, String> {
    let storage_arc = { state.storage.lock().await.clone() };
    let storage = storage_arc.as_ref().ok_or("Storage not initialized")?;
    
    let worlds = storage.list_worlds().await.map_err(|e| e.to_string())?;
    Ok(worlds.into_iter().map(|w| WorldSummary {
        world_id: w.world_id.to_string(),
        name: w.name,
        current_version: w.current_version,
        is_locked: w.is_locked,
        host_uuid: w.host_uuid.map(|u| u.to_string()),
    }).collect())
}

#[tauri::command]
async fn create_world(
    name: String,
    mc_version: String,
    loader: String,
    loader_version: Option<String>,
    state: State<'_, AppState>,
) -> std::result::Result<String, String> {
    let storage_arc = { state.storage.lock().await.clone() };
    let storage = storage_arc.as_ref().ok_or("Storage not initialized")?;
    
    let world_id = Uuid::new_v4();
    let owner_uuid = Uuid::new_v4();
    
    let mod_loader = hearth_core::manifest::ModLoader {
        loader_type: match loader.as_str() {
            "fabric" => hearth_core::manifest::LoaderType::Fabric,
            "forge" => hearth_core::manifest::LoaderType::Forge,
            "neoforge" => hearth_core::manifest::LoaderType::NeoForge,
            "quilt" => hearth_core::manifest::LoaderType::Quilt,
            _ => hearth_core::manifest::LoaderType::Vanilla,
        },
        version: loader_version.unwrap_or_default(),
    };

    let manifest = WorldManifest::new(world_id, name.clone(), owner_uuid, mc_version, mod_loader);
    storage.create_world(&manifest).await.map_err(|e| e.to_string())?;
    
    Ok(world_id.to_string())
}

#[tauri::command]
async fn get_world(world_id: String, state: State<'_, AppState>) -> std::result::Result<WorldDetail, String> {
    let storage_arc = { state.storage.lock().await.clone() };
    let storage = storage_arc.as_ref().ok_or("Storage not initialized")?;
    
    let uuid = Uuid::parse_str(&world_id).map_err(|e| e.to_string())?;
    let manifest = storage.get_manifest(uuid).await.map_err(|e| e.to_string())?;
    let lock = storage.get_lock(uuid).await.map_err(|e| e.to_string())?;
    
    Ok(WorldDetail {
        world_id: manifest.world_id.to_string(),
        name: manifest.name,
        minecraft_version: manifest.minecraft_version,
        mod_loader: format!("{:?} {}", manifest.mod_loader.loader_type, manifest.mod_loader.version),
        mods: manifest.mods.iter().map(|m| ModInfo {
            id: m.id.clone(),
            version: m.version.clone(),
            source: format!("{:?}", m.source),
        }).collect(),
        members: manifest.members.iter().map(|m| MemberInfo {
            uuid: m.uuid.to_string(),
            role: format!("{:?}", m.role),
            can_host: m.permissions.can_host,
            can_invite: m.permissions.can_invite,
        }).collect(),
        is_locked: lock.is_locked(),
        host_uuid: lock.current_host().map(|u| u.to_string()),
    })
}

#[tauri::command]
async fn resolve_instance(
    world_id: String,
    state: State<'_, AppState>,
) -> std::result::Result<InstanceInfo, String> {
    let storage_arc = { state.storage.lock().await.clone() };
    let storage = storage_arc.as_ref().ok_or("Storage not initialized")?;
    
    let mut manager_guard = state.mod_manager.lock().await;
    let manager = manager_guard.as_mut().ok_or("Mod manager not initialized")?;
    
    let uuid = Uuid::parse_str(&world_id).map_err(|e| e.to_string())?;
    let manifest = storage.get_manifest(uuid).await.map_err(|e| e.to_string())?;
    
    // Clone the data we need before releasing the guard
    let mc_version = manifest.minecraft_version.clone();
    let mod_loader = manifest.mod_loader.clone();
    let mods = manifest.mods.clone();
    
    drop(manager_guard);
    drop(storage_arc);
    
    // Re-acquire manager for the async call
    let mut manager_guard = state.mod_manager.lock().await;
    let manager = manager_guard.as_mut().ok_or("Mod manager not initialized")?;
    
    let config = manager.resolve_instance(
        &world_id,
        &mc_version,
        &mod_loader,
        &mods,
    ).await.map_err(|e| e.to_string())?;
    
    Ok(InstanceInfo {
        instance_id: config.instance_id,
        game_dir: config.game_dir.to_string_lossy().to_string(),
        minecraft_version: config.minecraft_version,
        mod_loader: config.mod_loader.loader_type,
        mod_count: config.mods.len(),
    })
}

#[tauri::command]
async fn launch_instance(
    instance_id: String,
    state: State<'_, AppState>,
) -> std::result::Result<String, String> {
    let mut manager_guard = state.mod_manager.lock().await;
    let manager = manager_guard.as_mut().ok_or("Mod manager not initialized")?;
    
    let instances = manager.list_instances().map_err(|e| e.to_string())?;
    let config = instances.into_iter().find(|i| i.instance_id == instance_id)
        .ok_or_else(|| "Instance not found".to_string())?;
    
    drop(manager_guard);
    
    // Re-acquire for the async call
    let mut manager_guard = state.mod_manager.lock().await;
    let manager = manager_guard.as_mut().ok_or("Mod manager not initialized")?;
    
    let mut child = manager.launch_instance(&config).await.map_err(|e| e.to_string())?;
    child.wait().map_err(|e| e.to_string())?;
    
    Ok("Instance launched".to_string())
}

#[derive(serde::Serialize)]
struct WorldSummary {
    world_id: String,
    name: String,
    current_version: String,
    is_locked: bool,
    host_uuid: Option<String>,
}

#[derive(serde::Serialize)]
struct WorldDetail {
    world_id: String,
    name: String,
    minecraft_version: String,
    mod_loader: String,
    mods: Vec<ModInfo>,
    members: Vec<MemberInfo>,
    is_locked: bool,
    host_uuid: Option<String>,
}

#[derive(serde::Serialize)]
struct ModInfo {
    id: String,
    version: String,
    source: String,
}

#[derive(serde::Serialize)]
struct MemberInfo {
    uuid: String,
    role: String,
    can_host: bool,
    can_invite: bool,
}

#[derive(serde::Serialize)]
struct InstanceInfo {
    instance_id: String,
    game_dir: String,
    minecraft_version: String,
    mod_loader: String,
    mod_count: usize,
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .manage(AppState {
            storage: Mutex::new(None),
            mod_manager: Mutex::new(None),
        })
        .invoke_handler(tauri::generate_handler![
            init_storage,
            list_worlds,
            create_world,
            get_world,
            resolve_instance,
            launch_instance,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}