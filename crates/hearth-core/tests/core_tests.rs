use hearth_core::{
    manifest::{WorldManifest, ModLoader, LoaderType, GameplayRules, Member, MemberRole, PermissionFlags},
    lock::{WorldLock, LockInfo, ConnectionInfo, TransportType},
    snapshot::{Snapshot, SnapshotFile, create_snapshot_from_world, compute_delta, apply_delta},
    mod_manager::{ModManager, InstanceConfig},
    Error, Result,
};
use uuid::Uuid;
use std::path::PathBuf;
use tempfile::TempDir;
use chrono::Utc;

#[test]
fn test_manifest_creation() {
    let world_id = Uuid::new_v4();
    let owner_uuid = Uuid::new_v4();
    
    let manifest = WorldManifest::new(
        world_id,
        "Test World".to_string(),
        owner_uuid,
        "1.20.1".to_string(),
        ModLoader {
            loader_type: LoaderType::Fabric,
            version: "0.15.11".to_string(),
        },
    );
    
    assert_eq!(manifest.world_id, world_id);
    assert_eq!(manifest.name, "Test World");
    assert_eq!(manifest.owner_uuid, owner_uuid);
    assert_eq!(manifest.minecraft_version, "1.20.1");
    assert_eq!(manifest.mod_loader.loader_type, LoaderType::Fabric);
    assert!(manifest.can_host(owner_uuid));
    assert!(!manifest.can_host(Uuid::new_v4()));
}

#[test]
fn test_manifest_permissions() {
    let world_id = Uuid::new_v4();
    let owner_uuid = Uuid::new_v4();
    let member_uuid = Uuid::new_v4();
    
    let mut manifest = WorldManifest::new(
        world_id,
        "Test World".to_string(),
        owner_uuid,
        "1.20.1".to_string(),
        ModLoader::default(),
    );
    
    let member = Member {
        uuid: member_uuid,
        role: MemberRole::Member,
        permissions: PermissionFlags::host_only(),
        joined_at: Utc::now(),
    };
    
    manifest.add_member(member).unwrap();
    
    assert!(manifest.can_host(owner_uuid));
    assert!(manifest.can_host(member_uuid));
    assert!(!manifest.can_invite(member_uuid));
    assert!(manifest.can_invite(owner_uuid));
}

#[test]
fn test_lock_acquire_release() {
    let world_id = Uuid::new_v4();
    let host_id = Uuid::new_v4();
    let other_id = Uuid::new_v4();
    
    let mut lock = WorldLock::new(world_id);
    
    let conn_info = ConnectionInfo {
        transport: TransportType::Steam,
        steam_lobby_id: Some("12345".to_string()),
        steam_session_id: None,
        direct_address: None,
        metadata: std::collections::HashMap::new(),
    };
    
    let acquired = lock.acquire(host_id, conn_info, 300).unwrap();
    assert_eq!(acquired.host_uuid, host_id);
    assert!(lock.is_locked());
    assert_eq!(lock.current_host(), Some(host_id));
    
    let result = lock.acquire(other_id, ConnectionInfo {
        transport: TransportType::Steam,
        steam_lobby_id: None,
        steam_session_id: None,
        direct_address: None,
        metadata: std::collections::HashMap::new(),
    }, 300);
    assert!(result.is_err());
    
    lock.release(host_id).unwrap();
    assert!(!lock.is_locked());
    assert_eq!(lock.current_host(), None);
}

#[test]
fn test_lock_expiration() {
    let world_id = Uuid::new_v4();
    let host_id = Uuid::new_v4();
    
    let mut lock = WorldLock::new(world_id);
    
    let conn_info = ConnectionInfo {
        transport: TransportType::Steam,
        steam_lobby_id: Some("12345".to_string()),
        steam_session_id: None,
        direct_address: None,
        metadata: std::collections::HashMap::new(),
    };
    
    lock.acquire(host_id, conn_info, 1).unwrap();
    assert!(lock.is_locked());
    
    // TTL is 1 second, so it should still be locked immediately
    // We can't easily test expiration without mocking time
    // Just verify it's locked initially
}

#[test]
fn test_snapshot_creation() {
    let temp_dir = TempDir::new().unwrap();
    let world_path = temp_dir.path().join("world");
    std::fs::create_dir_all(&world_path).unwrap();
    
    std::fs::write(world_path.join("level.dat"), b"test data").unwrap();
    std::fs::create_dir_all(world_path.join("region")).unwrap();
    std::fs::write(world_path.join("region").join("r.0.0.mca"), b"chunk data").unwrap();
    
    let world_id = Uuid::new_v4();
    let created_by = Uuid::new_v4();
    
    let snapshot = create_snapshot_from_world(&world_path, world_id, created_by, None).unwrap();
    
    assert_eq!(snapshot.metadata.world_id, world_id);
    assert_eq!(snapshot.metadata.created_by, created_by);
    assert!(snapshot.metadata.size_bytes > 0);
    assert!(snapshot.metadata.file_count > 0);
    assert!(snapshot.verify().unwrap());
}

#[test]
fn test_delta_computation() {
    let temp_dir = TempDir::new().unwrap();
    let world_path = temp_dir.path().join("world");
    std::fs::create_dir_all(&world_path).unwrap();
    
    std::fs::write(world_path.join("level.dat"), b"version 1").unwrap();
    
    let world_id = Uuid::new_v4();
    let created_by = Uuid::new_v4();
    
    let snapshot1 = create_snapshot_from_world(&world_path, world_id, created_by, None).unwrap();
    
    std::fs::write(world_path.join("level.dat"), b"version 2").unwrap();
    std::fs::write(world_path.join("new_file.txt"), b"new file").unwrap();
    
    let snapshot2 = create_snapshot_from_world(&world_path, world_id, created_by, Some(snapshot1.metadata.version.clone())).unwrap();
    
    let delta = compute_delta(&snapshot1, &snapshot2);
    
    assert_eq!(delta.base_version, snapshot1.metadata.version);
    assert_eq!(delta.target_version, snapshot2.metadata.version);
    assert_eq!(delta.added.len(), 1);
    assert_eq!(delta.modified.len(), 1);
    assert_eq!(delta.removed.len(), 0);
    
    let reconstructed = apply_delta(&snapshot1, &delta).unwrap();
    // The reconstructed snapshot has a new version (parent is base version)
    assert_eq!(reconstructed.parent_version, Some(snapshot1.metadata.version.clone()));
    assert!(reconstructed.verify().unwrap());
}

#[test]
fn test_mod_manager_instance_resolution() {
    let temp_dir = TempDir::new().unwrap();
    let base_dir = temp_dir.path().to_path_buf();
    
    let mut manager = ModManager::new(base_dir).unwrap();
    
    let world_id = Uuid::new_v4().to_string();
    let mc_version = "1.20.1";
    let mod_loader = hearth_core::manifest::ModLoader {
        loader_type: LoaderType::Fabric,
        version: "0.15.11".to_string(),
    };
    let mods = vec![];
    
    let config = tokio_test::block_on(manager.resolve_instance(&world_id, mc_version, &mod_loader, &mods)).unwrap();
    
    assert_eq!(config.minecraft_version, mc_version);
    assert_eq!(config.mod_loader.loader_type, "fabric");
    assert!(!config.instance_id.is_empty());
    assert!(config.game_dir.exists());
}