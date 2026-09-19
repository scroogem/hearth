use crate::{Error, Result, ModSpec, ModSource};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::collections::HashMap;
use sha2::{Sha256, Digest};
use hex;
use tokio::fs;
use reqwest::blocking::Client;
use semver::Version;
use chrono::Utc;
use tracing::{info, warn, debug};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstanceConfig {
    pub instance_id: String,
    pub world_id: String,
    pub minecraft_version: String,
    pub mod_loader: ModLoaderConfig,
    pub mods: Vec<ResolvedMod>,
    pub jvm_args: Vec<String>,
    pub game_dir: PathBuf,
    pub java_path: Option<PathBuf>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub last_used: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModLoaderConfig {
    pub loader_type: String,
    pub version: String,
    pub installer_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResolvedMod {
    pub spec: ModSpec,
    pub local_path: PathBuf,
    pub download_url: String,
    pub verified: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModCacheEntry {
    pub sha256: String,
    pub path: PathBuf,
    pub size: u64,
    pub added_at: chrono::DateTime<chrono::Utc>,
    pub last_used: chrono::DateTime<chrono::Utc>,
    pub references: u32,
}

pub struct ModCache {
    cache_dir: PathBuf,
    index: HashMap<String, ModCacheEntry>,
    client: Client,
}

impl ModCache {
    pub fn new(cache_dir: PathBuf) -> Result<Self> {
        std::fs::create_dir_all(&cache_dir)?;
        let index_path = cache_dir.join("index.json");
        let index = if index_path.exists() {
            let content = std::fs::read_to_string(&index_path)?;
            serde_json::from_str(&content).unwrap_or_default()
        } else {
            HashMap::new()
        };
        
        Ok(Self {
            cache_dir,
            index,
            client: Client::new(),
        })
    }

    pub fn get(&self, sha256: &str) -> Option<PathBuf> {
        self.index.get(sha256).map(|e| e.path.clone())
    }

    pub fn add(&mut self, sha256: String, path: PathBuf, size: u64) {
        let entry = ModCacheEntry {
            sha256: sha256.clone(),
            path,
            size,
            added_at: Utc::now(),
            last_used: Utc::now(),
            references: 1,
        };
        self.index.insert(sha256, entry);
        self.save_index().ok();
    }

    pub fn increment_ref(&mut self, sha256: &str) {
        if let Some(entry) = self.index.get_mut(sha256) {
            entry.references += 1;
            entry.last_used = Utc::now();
            self.save_index().ok();
        }
    }

    pub fn decrement_ref(&mut self, sha256: &str) {
        if let Some(entry) = self.index.get_mut(sha256) {
            entry.references = entry.references.saturating_sub(1);
            self.save_index().ok();
        }
    }

    fn save_index(&self) -> Result<()> {
        let index_path = self.cache_dir.join("index.json");
        let content = serde_json::to_string_pretty(&self.index)?;
        std::fs::write(index_path, content)?;
        Ok(())
    }

    pub fn cleanup_unreferenced(&mut self) -> Result<usize> {
        let mut removed = 0;
        self.index.retain(|_sha, entry| {
            if entry.references == 0 {
                if entry.path.exists() {
                    std::fs::remove_file(&entry.path).ok();
                }
                removed += 1;
                false
            } else {
                true
            }
        });
        self.save_index()?;
        Ok(removed)
    }
}

pub struct ModManager {
    instances_dir: PathBuf,
    cache: ModCache,
    client: Client,
}

impl ModManager {
    pub fn new(base_dir: PathBuf) -> Result<Self> {
        let instances_dir = base_dir.join("instances");
        let cache_dir = base_dir.join("cache").join("mods");
        std::fs::create_dir_all(&instances_dir)?;
        
        Ok(Self {
            instances_dir,
            cache: ModCache::new(cache_dir)?,
            client: Client::new(),
        })
    }

    pub async fn resolve_instance(
        &mut self,
        world_id: &str,
        minecraft_version: &str,
        mod_loader: &crate::ModLoader,
        mods: &[ModSpec],
    ) -> Result<InstanceConfig> {
        let instance_id = format!("{}-{}", world_id, Self::hash_config(minecraft_version, mod_loader, mods));
        let instance_dir = self.instances_dir.join(&instance_id);
        
        if instance_dir.join("config.json").exists() {
            let content = fs::read_to_string(instance_dir.join("config.json")).await?;
            let mut config: InstanceConfig = serde_json::from_str(&content)?;
            config.last_used = Utc::now();
            self.save_config(&config).await?;
            return Ok(config);
        }

        info!("Resolving instance for world {}: MC {} + {:?}", world_id, minecraft_version, mod_loader.loader_type);
        
        let resolved_mods = self.resolve_mods(mods).await?;
        
        let config = InstanceConfig {
            instance_id: instance_id.clone(),
            world_id: world_id.to_string(),
            minecraft_version: minecraft_version.to_string(),
            mod_loader: ModLoaderConfig {
                loader_type: format!("{:?}", mod_loader.loader_type).to_lowercase(),
                version: mod_loader.version.clone(),
                installer_url: None,
            },
            mods: resolved_mods,
            jvm_args: Self::default_jvm_args(),
            game_dir: instance_dir.join("game"),
            java_path: Self::find_java(),
            created_at: Utc::now(),
            last_used: Utc::now(),
        };

        self.prepare_instance(&config).await?;
        self.save_config(&config).await?;
        
        Ok(config)
    }

    fn hash_config(mc_version: &str, mod_loader: &crate::ModLoader, mods: &[ModSpec]) -> String {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        mc_version.hash(&mut hasher);
        format!("{:?}", mod_loader.loader_type).hash(&mut hasher);
        mod_loader.version.hash(&mut hasher);
        for m in mods {
            m.id.hash(&mut hasher);
            m.version.hash(&mut hasher);
        }
        format!("{:x}", hasher.finish())
    }

    async fn resolve_mods(&mut self, mods: &[ModSpec]) -> Result<Vec<ResolvedMod>> {
        let mut resolved = Vec::new();
        
        for spec in mods {
            let download_url = self.get_download_url(spec).await?;
            let sha256 = spec.sha256.clone().unwrap_or_else(|| {
                if let Some(sha1) = &spec.sha1 {
                    format!("sha1:{}", sha1)
                } else {
                    String::new()
                }
            });
            
            let local_path = if !sha256.is_empty() {
                if let Some(cached) = self.cache.get(&sha256) {
                    self.cache.increment_ref(&sha256);
                    cached
                } else {
                    let dest = self.download_mod(&download_url, &sha256).await?;
                    self.cache.add(sha256.clone(), dest.clone(), dest.metadata()?.len());
                    dest
                }
            } else {
                let dest = self.download_mod(&download_url, "").await?;
                dest
            };

            resolved.push(ResolvedMod {
                spec: spec.clone(),
                local_path,
                download_url,
                verified: !sha256.is_empty(),
            });
        }
        
        Ok(resolved)
    }

    async fn get_download_url(&self, spec: &ModSpec) -> Result<String> {
        match spec.source {
            ModSource::Modrinth => {
                if let (Some(project_id), Some(version_id)) = (&spec.project_id, &spec.version_id) {
                    let url = format!("https://api.modrinth.com/v2/project/{}/version/{}", project_id, version_id);
                    let resp: serde_json::Value = self.client.get(&url).send()?.json()?;
                    if let Some(files) = resp["files"].as_array() {
                        for file in files {
                            if file["primary"].as_bool().unwrap_or(false) {
                                if let Some(url) = file["url"].as_str() {
                                    return Ok(url.to_string());
                                }
                            }
                        }
                    }
                }
                if let Some(url) = &spec.url {
                    return Ok(url.clone());
                }
                Err(Error::ModResolution(format!("No download URL for mod {}", spec.id)))
            }
            ModSource::CurseForge => {
                if let Some(url) = &spec.url {
                    Ok(url.clone())
                } else {
                    Err(Error::ModResolution(format!("CurseForge mods require direct URL for {}", spec.id)))
                }
            }
            ModSource::Direct => {
                spec.url.clone().ok_or_else(|| Error::ModResolution(format!("Direct mod {} missing URL", spec.id)))
            }
            ModSource::Local => {
                spec.url.clone().ok_or_else(|| Error::ModResolution(format!("Local mod {} missing path", spec.id)))
            }
        }
    }

    async fn download_mod(&self, url: &str, expected_sha256: &str) -> Result<PathBuf> {
        let response = self.client.get(url).send()?;
        let bytes = response.bytes()?;
        
        if !expected_sha256.is_empty() && !expected_sha256.starts_with("sha1:") {
            let mut hasher = Sha256::new();
            hasher.update(&bytes);
            let actual = hex::encode(hasher.finalize());
            if actual != expected_sha256 {
                return Err(Error::ModResolution(format!(
                    "Checksum mismatch for {}: expected {}, got {}", url, expected_sha256, actual
                )));
            }
        }

        let filename = url.split('/').last().unwrap_or("mod.jar");
        let dest = self.cache.cache_dir.join(filename);
        fs::write(&dest, &bytes).await?;
        Ok(dest)
    }

    async fn prepare_instance(&self, config: &InstanceConfig) -> Result<()> {
        std::fs::create_dir_all(&config.game_dir)?;
        std::fs::create_dir_all(config.game_dir.join("mods"))?;
        
        for mod_entry in &config.mods {
            let dest = config.game_dir.join("mods").join(
                mod_entry.local_path.file_name().unwrap_or_default()
            );
            if !dest.exists() {
                if cfg!(target_family = "unix") {
                    std::os::unix::fs::symlink(&mod_entry.local_path, &dest)?;
                } else {
                    std::fs::copy(&mod_entry.local_path, &dest)?;
                }
            }
        }

        let version_json = self.generate_version_json(config).await?;
        let versions_dir = config.game_dir.join("versions").join(&config.instance_id);
        std::fs::create_dir_all(&versions_dir)?;
        fs::write(versions_dir.join(format!("{}.json", config.instance_id)), version_json).await?;
        
        Ok(())
    }

    async fn generate_version_json(&self, config: &InstanceConfig) -> Result<String> {
        let mut libraries = Vec::new();
        
        libraries.push(serde_json::json!({
            "name": format!("com.mojang:minecraft:{}:client", config.minecraft_version),
            "url": "https://libraries.minecraft.net/"
        }));

        let version_json = serde_json::json!({
            "id": config.instance_id,
            "time": config.created_at.to_rfc3339(),
            "releaseTime": config.created_at.to_rfc3339(),
            "type": "custom",
            "mainClass": "net.minecraft.client.main.Main",
            "minimumLauncherVersion": 21,
            "libraries": libraries,
            "arguments": {
                "game": [
                    "--username", "${auth_player_name}",
                    "--version", "${version_name}",
                    "--gameDir", "${game_directory}",
                    "--assetsDir", "${assets_root}",
                    "--assetIndex", "${assets_index_name}",
                    "--uuid", "${auth_uuid}",
                    "--accessToken", "${auth_access_token}",
                    "--userType", "legacy",
                    "--versionType", "Hearth"
                ],
                "jvm": config.jvm_args
            }
        });

        Ok(serde_json::to_string_pretty(&version_json)?)
    }

    fn default_jvm_args() -> Vec<String> {
        vec![
            "-Xmx4G".to_string(),
            "-Xms1G".to_string(),
            "-XX:+UseG1GC".to_string(),
            "-XX:+ParallelRefProcEnabled".to_string(),
            "-XX:MaxGCPauseMillis=200".to_string(),
            "-XX:+UnlockExperimentalVMOptions".to_string(),
            "-XX:+DisableExplicitGC".to_string(),
            "-XX:+AlwaysPreTouch".to_string(),
        ]
    }

    fn find_java() -> Option<PathBuf> {
        if let Ok(java_home) = std::env::var("JAVA_HOME") {
            let java = PathBuf::from(java_home).join("bin").join("java");
            if java.exists() {
                return Some(java);
            }
        }
        
        which::which("java").ok()
    }

    async fn save_config(&self, config: &InstanceConfig) -> Result<()> {
        let config_path = self.instances_dir.join(&config.instance_id).join("config.json");
        let content = serde_json::to_string_pretty(config)?;
        fs::write(config_path, content).await?;
        Ok(())
    }

    pub async fn launch_instance(&self, config: &InstanceConfig) -> Result<std::process::Child> {
        let java = config.java_path.as_ref()
            .ok_or_else(|| Error::Config("Java not found. Set JAVA_HOME or install Java.".to_string()))?;
        
        let mut cmd = std::process::Command::new(java);
        cmd.args(&config.jvm_args);
        cmd.arg("-cp");
        cmd.arg(self.build_classpath(config).await?);
        cmd.arg("net.minecraft.client.main.Main");
        cmd.args(&[
            "--username", "HearthPlayer",
            "--version", &config.instance_id,
            "--gameDir", config.game_dir.to_str().unwrap(),
            "--assetsDir", config.game_dir.join("assets").to_str().unwrap(),
            "--assetIndex", &config.minecraft_version,
            "--uuid", &uuid::Uuid::new_v4().to_string(),
            "--accessToken", "0",
            "--userType", "legacy",
            "--versionType", "Hearth",
        ]);
        cmd.current_dir(&config.game_dir);
        
        Ok(cmd.spawn()?)
    }

    async fn build_classpath(&self, config: &InstanceConfig) -> Result<String> {
        let mut paths = Vec::new();
        
        let version_dir = config.game_dir.join("versions").join(&config.instance_id);
        for entry in walkdir::WalkDir::new(&version_dir) {
            let entry = entry?;
            if entry.path().extension().map(|e| e == "jar").unwrap_or(false) {
                paths.push(entry.path().to_string_lossy().to_string());
            }
        }
        
        for entry in walkdir::WalkDir::new(config.game_dir.join("libraries")) {
            let entry = entry?;
            if entry.path().extension().map(|e| e == "jar").unwrap_or(false) {
                paths.push(entry.path().to_string_lossy().to_string());
            }
        }
        
        Ok(paths.join(if cfg!(target_family = "windows") { ";" } else { ":" }))
    }

    pub fn list_instances(&self) -> Result<Vec<InstanceConfig>> {
        let mut instances = Vec::new();
        for entry in walkdir::WalkDir::new(&self.instances_dir).max_depth(1).min_depth(1) {
            let entry = entry?;
            if entry.file_type().is_dir() {
                let config_path = entry.path().join("config.json");
                if config_path.exists() {
                    let content = std::fs::read_to_string(config_path)?;
                    if let Ok(config) = serde_json::from_str::<InstanceConfig>(&content) {
                        instances.push(config);
                    }
                }
            }
        }
        Ok(instances)
    }
}

pub trait ModResolver: Send + Sync {
    fn resolve(&self, spec: &ModSpec) -> Result<String>;
}