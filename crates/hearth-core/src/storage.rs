use crate::{Error, Result, WorldManifest, WorldLock, Snapshot, SnapshotMetadata};
use async_trait::async_trait;
use uuid::Uuid;
use serde::{Serialize, Deserialize};

#[async_trait]
pub trait StorageProvider: Send + Sync {
    async fn init(&self, config: &StorageConfig) -> Result<()>;

    async fn world_exists(&self, world_id: Uuid) -> Result<bool>;

    async fn create_world(&self, manifest: &WorldManifest) -> Result<()>;

    async fn get_manifest(&self, world_id: Uuid) -> Result<WorldManifest>;

    async fn update_manifest(&self, manifest: &WorldManifest) -> Result<()>;

    async fn get_lock(&self, world_id: Uuid) -> Result<WorldLock>;

    async fn update_lock(&self, lock: &WorldLock) -> Result<()>;

    async fn list_snapshots(&self, world_id: Uuid) -> Result<Vec<SnapshotMetadata>>;

    async fn get_snapshot(&self, world_id: Uuid, version: &str) -> Result<Snapshot>;

    async fn push_snapshot(&self, world_id: Uuid, snapshot: &Snapshot) -> Result<String>;

    async fn delete_snapshot(&self, world_id: Uuid, version: &str) -> Result<()>;

    async fn list_worlds(&self) -> Result<Vec<WorldSummary>>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    pub provider_type: ProviderType,
    pub git: Option<GitConfig>,
    pub s3: Option<S3Config>,
    pub local: Option<LocalConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProviderType {
    Git,
    S3,
    Local,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitConfig {
    pub remote_url: String,
    pub branch: String,
    pub credentials: Option<GitCredentials>,
    pub local_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitCredentials {
    pub username: Option<String>,
    pub token: Option<String>,
    pub ssh_key_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct S3Config {
    pub endpoint: String,
    pub bucket: String,
    pub region: String,
    pub access_key: String,
    pub secret_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalConfig {
    pub path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorldSummary {
    pub world_id: Uuid,
    pub name: String,
    pub owner_uuid: Uuid,
    pub current_version: String,
    pub is_locked: bool,
    pub host_uuid: Option<Uuid>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

use crate::manifest::ModSpec;
use crate::snapshot::DeltaSnapshot;

pub use crate::git_storage::GitStorage;
pub use crate::s3_storage::S3Storage;
pub use crate::local_storage::LocalStorage;

pub fn create_provider(config: &StorageConfig) -> Result<Box<dyn StorageProvider>> {
    match config.provider_type {
        ProviderType::Git => Ok(Box::new(GitStorage::new(config.git.as_ref().unwrap().clone())?)),
        ProviderType::S3 => Ok(Box::new(S3Storage::new(config.s3.as_ref().unwrap().clone())?)),
        ProviderType::Local => Ok(Box::new(LocalStorage::new(config.local.as_ref().unwrap().clone())?)),
    }
}

#[async_trait]
impl StorageProvider for Box<dyn StorageProvider> {
    async fn init(&self, config: &StorageConfig) -> Result<()> {
        self.as_ref().init(config).await
    }

    async fn world_exists(&self, world_id: Uuid) -> Result<bool> {
        self.as_ref().world_exists(world_id).await
    }

    async fn create_world(&self, manifest: &WorldManifest) -> Result<()> {
        self.as_ref().create_world(manifest).await
    }

    async fn get_manifest(&self, world_id: Uuid) -> Result<WorldManifest> {
        self.as_ref().get_manifest(world_id).await
    }

    async fn update_manifest(&self, manifest: &WorldManifest) -> Result<()> {
        self.as_ref().update_manifest(manifest).await
    }

    async fn get_lock(&self, world_id: Uuid) -> Result<WorldLock> {
        self.as_ref().get_lock(world_id).await
    }

    async fn update_lock(&self, lock: &WorldLock) -> Result<()> {
        self.as_ref().update_lock(lock).await
    }

    async fn list_snapshots(&self, world_id: Uuid) -> Result<Vec<SnapshotMetadata>> {
        self.as_ref().list_snapshots(world_id).await
    }

    async fn get_snapshot(&self, world_id: Uuid, version: &str) -> Result<Snapshot> {
        self.as_ref().get_snapshot(world_id, version).await
    }

    async fn push_snapshot(&self, world_id: Uuid, snapshot: &Snapshot) -> Result<String> {
        self.as_ref().push_snapshot(world_id, snapshot).await
    }

    async fn delete_snapshot(&self, world_id: Uuid, version: &str) -> Result<()> {
        self.as_ref().delete_snapshot(world_id, version).await
    }

    async fn list_worlds(&self) -> Result<Vec<WorldSummary>> {
        self.as_ref().list_worlds().await
    }
}