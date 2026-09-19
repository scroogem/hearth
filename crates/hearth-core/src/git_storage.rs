use crate::{Error, Result, WorldManifest, WorldLock, Snapshot, SnapshotMetadata, StorageConfig};
use crate::storage::GitConfig;
use git2::{Repository, Cred, RemoteCallbacks, PushOptions, FetchOptions, Signature};
use uuid::Uuid;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::Mutex;
use tempfile::TempDir;
use walkdir::WalkDir;
use sha2::Sha256;
use hex;
use chrono::Utc;
use tracing::{info, warn, debug};

pub struct GitStorage {
    config: GitConfig,
    repo_path: Arc<Mutex<Option<PathBuf>>>,
    temp_dir: Arc<Mutex<Option<TempDir>>>,
}

impl GitStorage {
    pub fn new(config: GitConfig) -> Result<Self> {
        Ok(Self {
            config,
            repo_path: Arc::new(Mutex::new(None)),
            temp_dir: Arc::new(Mutex::new(None)),
        })
    }

    async fn ensure_repo(&self) -> Result<PathBuf> {
        let mut path_guard = self.repo_path.lock().await;
        if let Some(path) = path_guard.as_ref() {
            return Ok(path.clone());
        }

        let path = if let Some(local_path) = &self.config.local_path {
            PathBuf::from(local_path)
        } else {
            let temp = TempDir::new()?;
            let path = temp.path().to_path_buf();
            *self.temp_dir.lock().await = Some(temp);
            path
        };

        let remote_url = self.config.remote_url.clone();
        let branch = self.config.branch.clone();
        let credentials = self.config.credentials.clone();

        let path_clone = path.clone();
        tokio::task::spawn_blocking(move || {
            if path_clone.join(".git").exists() {
                Repository::open(&path_clone)?;
            } else {
                info!("Cloning repository from {}", remote_url);
                let repo = Repository::clone(&remote_url, &path_clone)?;
                
                // Configure remote credentials after clone
                if let Some(creds) = &credentials {
                    let mut remote = repo.find_remote("origin")?;
                    let mut callbacks = RemoteCallbacks::new();
                    callbacks.credentials(|_url, username, _allowed| {
                        if let Some(token) = &creds.token {
                            Cred::userpass_plaintext(username.unwrap_or("git"), token)
                        } else if let Some(user) = &creds.username {
                            Cred::userpass_plaintext(user, "")
                        } else {
                            Cred::ssh_key_from_agent(username.unwrap_or("git"))
                        }
                    });
                    // Note: git2 doesn't allow setting callbacks on existing remote easily
                    // For now we just clone without auth, auth is used in fetch/push
                }
            }
            Ok::<_, Error>(())
        }).await??;

        *path_guard = Some(path.clone());
        Ok(path)
    }

    fn repo_path(&self) -> PathBuf {
        self.repo_path.blocking_lock().as_ref().unwrap().clone()
    }

    fn world_dir(&self, world_id: Uuid) -> PathBuf {
        self.repo_path().join("worlds").join(world_id.to_string())
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

    async fn commit_and_push(&self, repo_path: &Path, message: &str) -> Result<()> {
        let config = self.config.clone();
        let message = message.to_string();
        let path = repo_path.to_path_buf();
        
        tokio::task::spawn_blocking(move || {
            let repo = Repository::open(&path)?;
            let mut index = repo.index()?;
            index.add_all(["*"].iter(), git2::IndexAddOption::DEFAULT, None)?;
            index.write()?;

            let tree_id = index.write_tree()?;
            let tree = repo.find_tree(tree_id)?;

            let sig = Signature::now("Hearth Bot", "hearth@local")?;
            let parent_commit = repo.head().ok().and_then(|h| h.peel_to_commit().ok());
            let parents: Vec<&git2::Commit> = parent_commit.as_ref().map(|c| vec![c]).unwrap_or_default();

            repo.commit(
                Some("HEAD"),
                &sig,
                &sig,
                &message,
                &tree,
                &parents,
            )?;

            let mut callbacks = RemoteCallbacks::new();
            if let Some(creds) = &config.credentials {
                callbacks.credentials(|_url, username, _allowed| {
                    if let Some(token) = &creds.token {
                        Cred::userpass_plaintext(username.unwrap_or("git"), token)
                    } else if let Some(user) = &creds.username {
                        Cred::userpass_plaintext(user, "")
                    } else {
                        Cred::ssh_key_from_agent(username.unwrap_or("git"))
                    }
                });
            }

            let mut push_options = PushOptions::new();
            push_options.remote_callbacks(callbacks);

            let mut remote = repo.find_remote("origin")?;
            remote.push(&[format!("refs/heads/{}:refs/heads/{}", config.branch, config.branch)], Some(&mut push_options))?;

            Ok::<_, Error>(())
        }).await??;

        Ok(())
    }

    async fn pull_latest(&self, repo_path: &Path) -> Result<()> {
        let config = self.config.clone();
        let path = repo_path.to_path_buf();
        
        tokio::task::spawn_blocking(move || {
            let repo = Repository::open(&path)?;
            let mut callbacks = RemoteCallbacks::new();
            if let Some(creds) = &config.credentials {
                callbacks.credentials(|_url, username, _allowed| {
                    if let Some(token) = &creds.token {
                        Cred::userpass_plaintext(username.unwrap_or("git"), token)
                    } else if let Some(user) = &creds.username {
                        Cred::userpass_plaintext(user, "")
                    } else {
                        Cred::ssh_key_from_agent(username.unwrap_or("git"))
                    }
                });
            }

            let mut fetch_options = FetchOptions::new();
            fetch_options.remote_callbacks(callbacks);

            let mut remote = repo.find_remote("origin")?;
            remote.fetch(&[&config.branch], Some(&mut fetch_options), None)?;

            let fetch_head = repo.find_reference("FETCH_HEAD")?;
            let fetch_commit = repo.reference_to_annotated_commit(&fetch_head)?;
            let (analysis, _) = repo.merge_analysis(&[&fetch_commit])?;

            if analysis.is_up_to_date() {
                return Ok(());
            }

            if analysis.is_fast_forward() {
                let mut reference = repo.find_reference(&format!("refs/heads/{}", config.branch))?;
                reference.set_target(fetch_commit.id(), "Fast-forward")?;
                repo.set_head(&format!("refs/heads/{}", config.branch))?;
                repo.checkout_head(Some(git2::build::CheckoutBuilder::new().force()))?;
            } else {
                warn!("Non-fast-forward merge needed, manual intervention may be required");
            }

            Ok::<_, Error>(())
        }).await??;

        Ok(())
    }

    async fn with_repo<F, T>(&self, f: F) -> Result<T>
    where
        F: FnOnce(&Path) -> Result<T> + Send + 'static,
        T: Send + 'static,
    {
        let repo_path = self.ensure_repo().await?;
        let path = repo_path.clone();
        tokio::task::spawn_blocking(move || f(&path)).await?
    }

    async fn with_repo_pull<F, T>(&self, f: F) -> Result<T>
    where
        F: FnOnce(&Path) -> Result<T> + Send + 'static,
        T: Send + 'static,
    {
        let repo_path = self.ensure_repo().await?;
        self.pull_latest(&repo_path).await?;
        let path = repo_path.clone();
        tokio::task::spawn_blocking(move || f(&path)).await?
    }
}

#[async_trait::async_trait]
impl crate::storage::StorageProvider for GitStorage {
    async fn init(&self, _config: &StorageConfig) -> Result<()> {
        let _ = self.ensure_repo().await?;
        let repo_path = self.repo_path();
        let worlds_dir = repo_path.join("worlds");
        std::fs::create_dir_all(&worlds_dir)?;
        
        self.pull_latest(&repo_path).await?;
        Ok(())
    }

    async fn world_exists(&self, world_id: Uuid) -> Result<bool> {
        Ok(self.manifest_path(world_id).exists())
    }

    async fn create_world(&self, manifest: &WorldManifest) -> Result<()> {
        let world_id = manifest.world_id;
        let manifest_json = serde_json::to_string_pretty(manifest)?;
        let lock = WorldLock::new(world_id);
        let lock_json = serde_json::to_string_pretty(&lock)?;
        
        self.with_repo_pull(move |repo_path| {
            let world_dir = repo_path.join("worlds").join(world_id.to_string());
            std::fs::create_dir_all(&world_dir)?;
            std::fs::create_dir_all(world_dir.join("snapshots"))?;

            std::fs::write(world_dir.join("manifest.json"), manifest_json)?;
            std::fs::write(world_dir.join("lock.json"), lock_json)?;

            Ok(())
        }).await?;

        self.commit_and_push(&self.repo_path(), &format!("Create world: {}", manifest.name)).await?;
        Ok(())
    }

    async fn get_manifest(&self, world_id: Uuid) -> Result<WorldManifest> {
        self.with_repo_pull(move |repo_path| {
            let path = repo_path.join("worlds").join(world_id.to_string()).join("manifest.json");
            if !path.exists() {
                return Err(Error::WorldNotFound(world_id.to_string()));
            }

            let content = std::fs::read_to_string(path)?;
            let manifest: WorldManifest = serde_json::from_str(&content)?;
            Ok(manifest)
        }).await
    }

    async fn update_manifest(&self, manifest: &WorldManifest) -> Result<()> {
        let world_id = manifest.world_id;
        let mut updated = manifest.clone();
        updated.updated_at = Utc::now();
        let manifest_json = serde_json::to_string_pretty(&updated)?;
        
        self.with_repo_pull(move |repo_path| {
            std::fs::write(repo_path.join("worlds").join(world_id.to_string()).join("manifest.json"), manifest_json)?;
            Ok(())
        }).await?;

        self.commit_and_push(&self.repo_path(), &format!("Update manifest: {}", updated.name)).await?;
        Ok(())
    }

    async fn get_lock(&self, world_id: Uuid) -> Result<WorldLock> {
        self.with_repo_pull(move |repo_path| {
            let path = repo_path.join("worlds").join(world_id.to_string()).join("lock.json");
            if !path.exists() {
                return Ok(WorldLock::new(world_id));
            }

            let content = std::fs::read_to_string(path)?;
            let lock: WorldLock = serde_json::from_str(&content)?;
            Ok(lock)
        }).await
    }

    async fn update_lock(&self, lock: &WorldLock) -> Result<()> {
        let world_id = lock.world_id;
        let lock_json = serde_json::to_string_pretty(lock)?;
        
        self.with_repo_pull(move |repo_path| {
            std::fs::write(repo_path.join("worlds").join(world_id.to_string()).join("lock.json"), lock_json)?;
            Ok(())
        }).await?;

        self.commit_and_push(&self.repo_path(), &format!("Update lock for world {}", world_id)).await?;
        Ok(())
    }

    async fn list_snapshots(&self, world_id: Uuid) -> Result<Vec<SnapshotMetadata>> {
        self.with_repo_pull(move |repo_path| {
            let snapshots_dir = repo_path.join("worlds").join(world_id.to_string()).join("snapshots");
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
        }).await
    }

    async fn get_snapshot(&self, world_id: Uuid, version: &str) -> Result<Snapshot> {
        let version = version.to_string();
        self.with_repo_pull(move |repo_path| {
            let path = repo_path.join("worlds").join(world_id.to_string()).join("snapshots").join(format!("{}.msgpack", version));
            if !path.exists() {
                return Err(Error::WorldNotFound(format!("Snapshot {} not found", version)));
            }

            let data = std::fs::read(path)?;
            let snapshot: Snapshot = rmp_serde::from_slice(&data)?;
            Ok(snapshot)
        }).await
    }

    async fn push_snapshot(&self, world_id: Uuid, snapshot: &Snapshot) -> Result<String> {
        let version = snapshot.metadata.version.clone();
        let version_for_closure = version.clone();
        let data = rmp_serde::to_vec(snapshot)?;
        let meta_json = serde_json::to_string_pretty(&snapshot.metadata)?;
        
        self.with_repo_pull(move |repo_path| {
            let snapshots_dir = repo_path.join("worlds").join(world_id.to_string()).join("snapshots");
            std::fs::create_dir_all(&snapshots_dir)?;

            std::fs::write(snapshots_dir.join(format!("{}.msgpack", version_for_closure)), data)?;

            std::fs::write(snapshots_dir.join(format!("{}.meta.json", version_for_closure)), meta_json)?;

            Ok(())
        }).await?;

        self.commit_and_push(&self.repo_path(), &format!("Push snapshot {} for world {}", version, world_id)).await?;
        Ok(version)
    }

    async fn delete_snapshot(&self, world_id: Uuid, version: &str) -> Result<()> {
        let version_owned = version.to_string();
        self.with_repo_pull(move |repo_path| {
            let snapshots_dir = repo_path.join("worlds").join(world_id.to_string()).join("snapshots");
            let snapshot_file = snapshots_dir.join(format!("{}.msgpack", version_owned));
            let meta_file = snapshots_dir.join(format!("{}.meta.json", version_owned));

            if snapshot_file.exists() {
                std::fs::remove_file(snapshot_file)?;
            }
            if meta_file.exists() {
                std::fs::remove_file(meta_file)?;
            }
            Ok(())
        }).await?;

        self.commit_and_push(&self.repo_path(), &format!("Delete snapshot {} for world {}", version, world_id)).await?;
        Ok(())
    }

    async fn list_worlds(&self) -> Result<Vec<crate::storage::WorldSummary>> {
        self.with_repo_pull(move |repo_path| {
            let worlds_dir = repo_path.join("worlds");
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
                            
                            worlds.push(crate::storage::WorldSummary {
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
        }).await
    }
}