use crate::{Error, Result, WorldManifest, WorldLock, Snapshot, SnapshotMetadata};
use crate::storage::{StorageProvider, LocalConfig, StorageConfig, WorldSummary};
use async_trait::async_trait;
use uuid::Uuid;
use chrono::Utc;
use std::path::PathBuf;
use walkdir::WalkDir;

pub struct LocalStorage {
    base_path: PathBuf,
}

impl LocalStorage {
    pub fn new(config: LocalConfig) -> Result<Self> {
        let path = PathBuf::from(config.path);
        std::fs::create_dir_all(&path)?;
        Ok(Self { base_path: path })
    }

    fn world_dir(&self, world_id: Uuid) -> PathBuf {
        self.base_path.join("worlds").join(world_id.to_string())
    }

    fn manifest_path(&self, world_id: Uuid) -> PathBuf {
        self.world_dir(world_id).join("manifest.json")
    }

    fn lock_path(&self, world_id: Uuid) -> PathBuf {
        self.world_dir(world_id).join("lock.json")
    }

    fn snapshots_dir(&self, world_id: Uuid) -> PathBuf {
        self.world_dir(world_id).join("snapshots")
    }

    fn snapshot_path(&self, world_id: Uuid, version: &str) -> PathBuf {
        self.snapshots_dir(world_id).join(format!("{}.msgpack", version))
    }

    fn snapshot_meta_path(&self, world_id: Uuid, version: &str) -> PathBuf {
        self.snapshots_dir(world_id).join(format!("{}.meta.json", version))
    }
}

#[async_trait]
impl StorageProvider for LocalStorage {
    async fn init(&self, _config: &StorageConfig) -> Result<()> {
        std::fs::create_dir_all(self.base_path.join("worlds"))?;
        Ok(())
    }

    async fn world_exists(&self, world_id: Uuid) -> Result<bool> {
        Ok(self.manifest_path(world_id).exists())
    }

    async fn create_world(&self, manifest: &WorldManifest) -> Result<()> {
        let world_dir = self.world_dir(manifest.world_id);
        std::fs::create_dir_all(&world_dir)?;
        std::fs::create_dir_all(self.snapshots_dir(manifest.world_id))?;

        let manifest_json = serde_json::to_string_pretty(manifest)?;
        std::fs::write(self.manifest_path(manifest.world_id), manifest_json)?;

        let lock = WorldLock::new(manifest.world_id);
        let lock_json = serde_json::to_string_pretty(&lock)?;
        std::fs::write(self.lock_path(manifest.world_id), lock_json)?;

        Ok(())
    }

    async fn get_manifest(&self, world_id: Uuid) -> Result<WorldManifest> {
        let path = self.manifest_path(world_id);
        if !path.exists() {
            return Err(Error::WorldNotFound(world_id.to_string()));
        }
        let content = std::fs::read_to_string(path)?;
        Ok(serde_json::from_str(&content)?)
    }

    async fn update_manifest(&self, manifest: &WorldManifest) -> Result<()> {
        let mut updated = manifest.clone();
        updated.updated_at = Utc::now();
        let manifest_json = serde_json::to_string_pretty(&updated)?;
        std::fs::write(self.manifest_path(manifest.world_id), manifest_json)?;
        Ok(())
    }

    async fn get_lock(&self, world_id: Uuid) -> Result<WorldLock> {
        let path = self.lock_path(world_id);
        if !path.exists() {
            return Ok(WorldLock::new(world_id));
        }
        let content = std::fs::read_to_string(path)?;
        Ok(serde_json::from_str(&content)?)
    }

    async fn update_lock(&self, lock: &WorldLock) -> Result<()> {
        let lock_json = serde_json::to_string_pretty(lock)?;
        std::fs::write(self.lock_path(lock.world_id), lock_json)?;
        Ok(())
    }

    async fn list_snapshots(&self, world_id: Uuid) -> Result<Vec<SnapshotMetadata>> {
        let snapshots_dir = self.snapshots_dir(world_id);
        if !snapshots_dir.exists() {
            return Ok(Vec::new());
        }

        let mut snapshots = Vec::new();
        for entry in WalkDir::new(&snapshots_dir).max_depth(1) {
            let entry = entry?;
            if entry.file_type().is_file() {
                let name = entry.file_name().to_string_lossy();
                if name.ends_with(".meta.json") {
                    let version = name.strip_suffix(".meta.json").unwrap();
                    let content = std::fs::read_to_string(entry.path())?;
                    let meta: SnapshotMetadata = serde_json::from_str(&content)?;
                    snapshots.push(meta);
                }
            }
        }

        snapshots.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        Ok(snapshots)
    }

    async fn get_snapshot(&self, world_id: Uuid, version: &str) -> Result<Snapshot> {
        let path = self.snapshot_path(world_id, version);
        if !path.exists() {
            return Err(Error::WorldNotFound(format!("Snapshot {} not found", version)));
        }
        let data = std::fs::read(path)?;
        Ok(rmp_serde::from_slice(&data)?)
    }

    async fn push_snapshot(&self, world_id: Uuid, snapshot: &Snapshot) -> Result<String> {
        let version = snapshot.metadata.version.clone();
        std::fs::create_dir_all(self.snapshots_dir(world_id))?;

        let data = rmp_serde::to_vec(snapshot)?;
        std::fs::write(self.snapshot_path(world_id, &version), data)?;

        let meta_json = serde_json::to_string_pretty(&snapshot.metadata)?;
        std::fs::write(self.snapshot_meta_path(world_id, &version), meta_json)?;

        Ok(version)
    }

    async fn delete_snapshot(&self, world_id: Uuid, version: &str) -> Result<()> {
        let snapshot_file = self.snapshot_path(world_id, version);
        let meta_file = self.snapshot_meta_path(world_id, version);

        if snapshot_file.exists() {
            std::fs::remove_file(snapshot_file)?;
        }
        if meta_file.exists() {
            std::fs::remove_file(meta_file)?;
        }
        Ok(())
    }

    async fn list_worlds(&self) -> Result<Vec<WorldSummary>> {
        let worlds_dir = self.base_path.join("worlds");
        if !worlds_dir.exists() {
            return Ok(Vec::new());
        }

        let mut worlds = Vec::new();
        for entry in WalkDir::new(&worlds_dir).max_depth(1).min_depth(1) {
            let entry = entry?;
            if entry.file_type().is_dir() {
                let world_id_str = entry.file_name().to_string_lossy();
                if let Ok(world_id) = Uuid::parse_str(&world_id_str) {
                    let manifest_path = entry.path().join("manifest.json");
                    if manifest_path.exists() {
                        let content = std::fs::read_to_string(manifest_path)?;
                        let manifest: WorldManifest = serde_json::from_str(&content)?;
                        
                        let lock_path = entry.path().join("lock.json");
                        let lock = if lock_path.exists() {
                            let content = std::fs::read_to_string(lock_path)?;
                            serde_json::from_str(&content).unwrap_or_else(|_| WorldLock::new(world_id))
                        } else {
                            WorldLock::new(world_id)
                        };
                        
                        worlds.push(WorldSummary {
                            world_id,
                            name: manifest.name,
                            owner_uuid: manifest.owner_uuid,
                            current_version: manifest.current_version,
                            is_locked: lock.is_locked(),
                            host_uuid: lock.current_host(),
                            updated_at: manifest.updated_at,
                        });
                    }
                }
            }
        }

        Ok(worlds)
    }
}