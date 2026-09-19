use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use sha2::{Sha256, Digest};
use hex;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Snapshot {
    pub metadata: SnapshotMetadata,
    pub files: Vec<SnapshotFile>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent_version: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SnapshotMetadata {
    pub version: String,
    pub world_id: Uuid,
    pub created_at: DateTime<Utc>,
    pub created_by: Uuid,
    pub size_bytes: u64,
    pub file_count: usize,
    pub checksum: String,
    pub description: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SnapshotFile {
    pub path: String,
    pub size: u64,
    pub checksum: String,
    pub compressed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<Vec<u8>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DeltaSnapshot {
    pub base_version: String,
    pub target_version: String,
    pub added: Vec<SnapshotFile>,
    pub modified: Vec<SnapshotFile>,
    pub removed: Vec<String>,
}

impl Snapshot {
    pub fn new(
        world_id: Uuid,
        created_by: Uuid,
        files: Vec<SnapshotFile>,
        parent_version: Option<String>,
        description: Option<String>,
    ) -> Self {
        let size_bytes: u64 = files.iter().map(|f| f.size).sum();
        let file_count = files.len();
        let checksum = Self::compute_checksum(&files);
        let version = Uuid::new_v4().to_string();

        let metadata = SnapshotMetadata {
            version: version.clone(),
            world_id,
            created_at: Utc::now(),
            created_by,
            size_bytes,
            file_count,
            checksum,
            description,
            tags: Vec::new(),
        };

        Self {
            metadata,
            files,
            parent_version,
        }
    }

    fn compute_checksum(files: &[SnapshotFile]) -> String {
        let mut hasher = Sha256::new();
        for file in files {
            hasher.update(file.path.as_bytes());
            hasher.update(file.checksum.as_bytes());
        }
        hex::encode(hasher.finalize())
    }

    pub fn verify(&self) -> Result<bool> {
        let computed = Self::compute_checksum(&self.files);
        Ok(computed == self.metadata.checksum)
    }

    pub fn get_file_data(&self, path: &str) -> Option<&[u8]> {
        self.files.iter()
            .find(|f| f.path == path)
            .and_then(|f| f.data.as_deref())
    }
}

impl SnapshotFile {
    pub fn from_path(path: &str, relative_to: &std::path::Path) -> Result<Self> {
        let full_path = relative_to.join(path);
        let metadata = std::fs::metadata(&full_path)?;
        let data = std::fs::read(&full_path)?;
        let checksum = Self::compute_checksum(&data);

        Ok(Self {
            path: path.to_string(),
            size: metadata.len(),
            checksum,
            compressed: false,
            data: Some(data),
        })
    }

    pub fn from_data(path: String, data: Vec<u8>) -> Self {
        let checksum = Self::compute_checksum(&data);
        Self {
            path,
            size: data.len() as u64,
            checksum,
            compressed: false,
            data: Some(data),
        }
    }

    fn compute_checksum(data: &[u8]) -> String {
        let mut hasher = Sha256::new();
        hasher.update(data);
        hex::encode(hasher.finalize())
    }

    pub fn without_data(mut self) -> Self {
        self.data = None;
        self
    }
}

pub fn create_snapshot_from_world(
    world_path: &std::path::Path,
    world_id: Uuid,
    created_by: Uuid,
    parent_version: Option<String>,
) -> Result<Snapshot> {
    let mut files = Vec::new();

    for entry in walkdir::WalkDir::new(world_path) {
        let entry = entry?;
        if entry.file_type().is_file() {
            let path = entry.path();
            let relative = path.strip_prefix(world_path)?
                .to_string_lossy()
                .to_string();
            
            if should_include_file(&relative) {
                let file = SnapshotFile::from_path(&relative, world_path)?;
                files.push(file);
            }
        }
    }

    Ok(Snapshot::new(world_id, created_by, files, parent_version, None))
}

fn should_include_file(path: &str) -> bool {
    let excluded = [
        "session.lock",
        "level.dat_old",
        "level.dat_mcr",
        ".git/",
        ".DS_Store",
        "Thumbs.db",
    ];
    
    !excluded.iter().any(|e| path.contains(e)) && !path.ends_with(".tmp")
}

pub fn apply_snapshot(snapshot: &Snapshot, target_path: &std::path::Path) -> Result<()> {
    std::fs::create_dir_all(target_path)?;
    
    for file in &snapshot.files {
        let file_path = target_path.join(&file.path);
        if let Some(parent) = file_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        
        if let Some(data) = &file.data {
            std::fs::write(&file_path, data)?;
        }
    }
    
    Ok(())
}

pub fn compute_delta(base: &Snapshot, target: &Snapshot) -> DeltaSnapshot {
    let base_files: HashMap<_, _> = base.files.iter().map(|f| (&f.path, f)).collect();
    let target_files: HashMap<_, _> = target.files.iter().map(|f| (&f.path, f)).collect();

    let mut added = Vec::new();
    let mut modified = Vec::new();
    let mut removed = Vec::new();

    for (path, file) in &target_files {
        match base_files.get(path) {
            Some(base_file) if base_file.checksum != file.checksum => {
                modified.push((*file).clone());
            }
            None => {
                added.push((*file).clone());
            }
            _ => {}
        }
    }

    for (path, _) in &base_files {
        if !target_files.contains_key(path) {
            removed.push((*path).clone());
        }
    }

    DeltaSnapshot {
        base_version: base.metadata.version.clone(),
        target_version: target.metadata.version.clone(),
        added,
        modified,
        removed,
    }
}

pub fn apply_delta(base: &Snapshot, delta: &DeltaSnapshot) -> Result<Snapshot> {
    let mut files = base.files.clone();

    for path in &delta.removed {
        files.retain(|f| f.path != *path);
    }

    for file in &delta.modified {
        if let Some(idx) = files.iter().position(|f| f.path == file.path) {
            files[idx] = file.clone();
        }
    }

    for file in &delta.added {
        files.push(file.clone());
    }

    Ok(Snapshot::new(
        base.metadata.world_id,
        base.metadata.created_by,
        files,
        Some(base.metadata.version.clone()),
        None,
    ))
}