use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WorldManifest {
    pub world_id: Uuid,
    pub name: String,
    pub owner_uuid: Uuid,
    pub minecraft_version: String,
    pub mod_loader: ModLoader,
    pub mods: Vec<ModSpec>,
    pub gameplay_rules: GameplayRules,
    pub members: Vec<Member>,
    pub current_version: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub metadata: HashMap<String, serde_json::Value>,
}

impl WorldManifest {
    pub fn new(
        world_id: Uuid,
        name: String,
        owner_uuid: Uuid,
        minecraft_version: String,
        mod_loader: ModLoader,
    ) -> Self {
        let now = Utc::now();
        Self {
            world_id,
            name,
            owner_uuid,
            minecraft_version,
            mod_loader,
            mods: Vec::new(),
            gameplay_rules: GameplayRules::default(),
            members: vec![Member {
                uuid: owner_uuid,
                role: MemberRole::Owner,
                permissions: PermissionFlags::all(),
                joined_at: now,
            }],
            current_version: "initial".to_string(),
            created_at: now,
            updated_at: now,
            metadata: HashMap::new(),
        }
    }

    pub fn can_host(&self, uuid: Uuid) -> bool {
        self.members.iter().any(|m| m.uuid == uuid && m.permissions.can_host)
    }

    pub fn can_invite(&self, uuid: Uuid) -> bool {
        self.members.iter().any(|m| m.uuid == uuid && m.permissions.can_invite)
    }

    pub fn can_manage(&self, uuid: Uuid) -> bool {
        self.members.iter().any(|m| m.uuid == uuid && m.permissions.can_manage)
    }

    pub fn is_member(&self, uuid: Uuid) -> bool {
        self.members.iter().any(|m| m.uuid == uuid)
    }

    pub fn add_member(&mut self, member: Member) -> Result<(), String> {
        if self.members.iter().any(|m| m.uuid == member.uuid) {
            return Err("Member already exists".to_string());
        }
        self.members.push(member);
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn remove_member(&mut self, uuid: Uuid) -> Result<(), String> {
        if uuid == self.owner_uuid {
            return Err("Cannot remove owner".to_string());
        }
        self.members.retain(|m| m.uuid != uuid);
        self.updated_at = Utc::now();
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ModLoader {
    pub loader_type: LoaderType,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LoaderType {
    Vanilla,
    Fabric,
    Forge,
    NeoForge,
    Quilt,
}

impl Default for ModLoader {
    fn default() -> Self {
        Self {
            loader_type: LoaderType::Vanilla,
            version: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ModSpec {
    pub id: String,
    pub source: ModSource,
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    pub version: String,
    pub sha1: Option<String>,
    pub sha256: Option<String>,
    pub url: Option<String>,
    #[serde(default)]
    pub required: bool,
    #[serde(default)]
    pub client_side: bool,
    #[serde(default)]
    pub server_side: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ModSource {
    Modrinth,
    CurseForge,
    Direct,
    Local,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct GameplayRules {
    pub allow_cheats: bool,
    pub allow_creative: bool,
    pub keep_inventory: bool,
    pub difficulty: Difficulty,
    #[serde(default)]
    pub pvp: bool,
    #[serde(default)]
    pub spawn_monsters: bool,
    #[serde(default)]
    pub spawn_animals: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum Difficulty {
    Peaceful,
    Easy,
    #[default]
    Normal,
    Hard,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Member {
    pub uuid: Uuid,
    pub role: MemberRole,
    pub permissions: PermissionFlags,
    pub joined_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum MemberRole {
    Owner,
    Admin,
    Member,
    Viewer,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct PermissionFlags {
    pub can_host: bool,
    pub can_invite: bool,
    pub can_manage: bool,
    pub can_modify_rules: bool,
    pub can_rollback: bool,
}

impl PermissionFlags {
    pub fn all() -> Self {
        Self {
            can_host: true,
            can_invite: true,
            can_manage: true,
            can_modify_rules: true,
            can_rollback: true,
        }
    }

    pub fn none() -> Self {
        Self::default()
    }

    pub fn host_only() -> Self {
        Self {
            can_host: true,
            ..Default::default()
        }
    }
}