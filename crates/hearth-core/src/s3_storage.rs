use crate::{Error, Result, WorldManifest, WorldLock, Snapshot, SnapshotMetadata};
use crate::storage::{StorageProvider, S3Config, StorageConfig, WorldSummary};
use async_trait::async_trait;
use uuid::Uuid;
use chrono::Utc;
use std::collections::HashMap;

pub struct S3Storage {
    config: S3Config,
    client: reqwest::Client,
}

impl S3Storage {
    pub fn new(config: S3Config) -> Result<Self> {
        let client = reqwest::Client::new();
        Ok(Self { config, client })
    }

    fn bucket_url(&self, key: &str) -> String {
        format!("{}/{}/{}", self.config.endpoint.trim_end_matches('/'), self.config.bucket, key)
    }

    fn auth_header(&self) -> String {
        format!("AWS {}:{}", self.config.access_key, self.config.secret_key)
    }
}

#[async_trait]
impl StorageProvider for S3Storage {
    async fn init(&self, _config: &StorageConfig) -> Result<()> {
        Ok(())
    }

    async fn world_exists(&self, world_id: Uuid) -> Result<bool> {
        let url = self.bucket_url(&format!("worlds/{}/manifest.json", world_id));
        let resp = self.client.head(&url).header("Authorization", self.auth_header()).send().await?;
        Ok(resp.status().is_success())
    }

    async fn create_world(&self, manifest: &WorldManifest) -> Result<()> {
        let key = format!("worlds/{}/manifest.json", manifest.world_id);
        let url = self.bucket_url(&key);
        let body = serde_json::to_vec(manifest)?;
        self.client.put(&url).header("Authorization", self.auth_header()).body(body).send().await?;
        
        let lock = WorldLock::new(manifest.world_id);
        let key = format!("worlds/{}/lock.json", manifest.world_id);
        let url = self.bucket_url(&key);
        let body = serde_json::to_vec(&lock)?;
        self.client.put(&url).header("Authorization", self.auth_header()).body(body).send().await?;
        
        Ok(())
    }

    async fn get_manifest(&self, world_id: Uuid) -> Result<WorldManifest> {
        let url = self.bucket_url(&format!("worlds/{}/manifest.json", world_id));
        let resp = self.client.get(&url).header("Authorization", self.auth_header()).send().await?;
        if !resp.status().is_success() {
            return Err(Error::WorldNotFound(world_id.to_string()));
        }
        let body = resp.bytes().await?;
        Ok(serde_json::from_slice(&body)?)
    }

    async fn update_manifest(&self, manifest: &WorldManifest) -> Result<()> {
        let mut updated = manifest.clone();
        updated.updated_at = Utc::now();
        let key = format!("worlds/{}/manifest.json", manifest.world_id);
        let url = self.bucket_url(&key);
        let body = serde_json::to_vec(&updated)?;
        self.client.put(&url).header("Authorization", self.auth_header()).body(body).send().await?;
        Ok(())
    }

    async fn get_lock(&self, world_id: Uuid) -> Result<WorldLock> {
        let url = self.bucket_url(&format!("worlds/{}/lock.json", world_id));
        let resp = self.client.get(&url).header("Authorization", self.auth_header()).send().await?;
        if !resp.status().is_success() {
            return Ok(WorldLock::new(world_id));
        }
        let body = resp.bytes().await?;
        Ok(serde_json::from_slice(&body)?)
    }

    async fn update_lock(&self, lock: &WorldLock) -> Result<()> {
        let key = format!("worlds/{}/lock.json", lock.world_id);
        let url = self.bucket_url(&key);
        let body = serde_json::to_vec(lock)?;
        self.client.put(&url).header("Authorization", self.auth_header()).body(body).send().await?;
        Ok(())
    }

    async fn list_snapshots(&self, world_id: Uuid) -> Result<Vec<SnapshotMetadata>> {
        let prefix = format!("worlds/{}/snapshots/", world_id);
        let url = format!("{}?list-type=2&prefix={}", self.bucket_url(""), prefix);
        let resp = self.client.get(&url).header("Authorization", self.auth_header()).send().await?;
        // Parse S3 list response and fetch each .meta.json
        Ok(Vec::new())
    }

    async fn get_snapshot(&self, world_id: Uuid, version: &str) -> Result<Snapshot> {
        let url = self.bucket_url(&format!("worlds/{}/snapshots/{}.msgpack", world_id, version));
        let resp = self.client.get(&url).header("Authorization", self.auth_header()).send().await?;
        if !resp.status().is_success() {
            return Err(Error::WorldNotFound(format!("Snapshot {} not found", version)));
        }
        let body = resp.bytes().await?;
        Ok(rmp_serde::from_slice(&body)?)
    }

    async fn push_snapshot(&self, world_id: Uuid, snapshot: &Snapshot) -> Result<String> {
        let version = snapshot.metadata.version.clone();
        let data = rmp_serde::to_vec(snapshot)?;
        let key = format!("worlds/{}/snapshots/{}.msgpack", world_id, version);
        let url = self.bucket_url(&key);
        self.client.put(&url).header("Authorization", self.auth_header()).body(data).send().await?;
        
        let meta_json = serde_json::to_vec(&snapshot.metadata)?;
        let key = format!("worlds/{}/snapshots/{}.meta.json", world_id, version);
        let url = self.bucket_url(&key);
        self.client.put(&url).header("Authorization", self.auth_header()).body(meta_json).send().await?;
        
        Ok(version)
    }

    async fn delete_snapshot(&self, world_id: Uuid, version: &str) -> Result<()> {
        let key = format!("worlds/{}/snapshots/{}.msgpack", world_id, version);
        let url = self.bucket_url(&key);
        self.client.delete(&url).header("Authorization", self.auth_header()).send().await?;
        
        let key = format!("worlds/{}/snapshots/{}.meta.json", world_id, version);
        let url = self.bucket_url(&key);
        self.client.delete(&url).header("Authorization", self.auth_header()).send().await?;
        Ok(())
    }

    async fn list_worlds(&self) -> Result<Vec<WorldSummary>> {
        Ok(Vec::new())
    }
}