use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WorldLock {
    pub world_id: Uuid,
    pub lock: Option<LockInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LockInfo {
    pub host_uuid: Uuid,
    pub session_id: Uuid,
    pub created_at: DateTime<Utc>,
    pub heartbeat_at: DateTime<Utc>,
    pub ttl_seconds: u64,
    pub connection_info: ConnectionInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConnectionInfo {
    pub transport: TransportType,
    pub steam_lobby_id: Option<String>,
    pub steam_session_id: Option<String>,
    pub direct_address: Option<String>,
    #[serde(default)]
    pub metadata: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum TransportType {
    Steam,
    Direct,
    Relay,
}

impl WorldLock {
    pub fn new(world_id: Uuid) -> Self {
        Self {
            world_id,
            lock: None,
        }
    }

    pub fn acquire(
        &mut self,
        host_uuid: Uuid,
        connection_info: ConnectionInfo,
        ttl_seconds: u64,
    ) -> Result<LockInfo, String> {
        if let Some(existing) = &self.lock {
            if !existing.is_expired() {
                return Err(format!(
                    "World already locked by {} until {}",
                    existing.host_uuid,
                    existing.expires_at()
                ));
            }
        }

        let now = Utc::now();
        let lock_info = LockInfo {
            host_uuid,
            session_id: Uuid::new_v4(),
            created_at: now,
            heartbeat_at: now,
            ttl_seconds,
            connection_info,
        };
        self.lock = Some(lock_info.clone());
        Ok(lock_info)
    }

    pub fn release(&mut self, host_uuid: Uuid) -> Result<(), String> {
        match &self.lock {
            Some(lock) if lock.host_uuid == host_uuid => {
                self.lock = None;
                Ok(())
            }
            Some(_) => Err("Lock held by different host".to_string()),
            None => Err("No active lock".to_string()),
        }
    }

    pub fn heartbeat(&mut self, host_uuid: Uuid) -> Result<(), String> {
        match &mut self.lock {
            Some(lock) if lock.host_uuid == host_uuid => {
                lock.heartbeat_at = Utc::now();
                Ok(())
            }
            Some(_) => Err("Lock held by different host".to_string()),
            None => Err("No active lock".to_string()),
        }
    }

    pub fn is_locked(&self) -> bool {
        self.lock.as_ref().map(|l| !l.is_expired()).unwrap_or(false)
    }

    pub fn current_host(&self) -> Option<Uuid> {
        self.lock.as_ref().filter(|l| !l.is_expired()).map(|l| l.host_uuid)
    }

    pub fn get_lock(&self) -> Option<&LockInfo> {
        self.lock.as_ref().filter(|l| !l.is_expired())
    }
}

impl LockInfo {
    pub fn is_expired(&self) -> bool {
        let elapsed = Utc::now().signed_duration_since(self.heartbeat_at);
        elapsed.num_seconds() > self.ttl_seconds as i64
    }

    pub fn expires_at(&self) -> DateTime<Utc> {
        self.heartbeat_at + chrono::Duration::seconds(self.ttl_seconds as i64)
    }

    pub fn time_remaining(&self) -> chrono::Duration {
        self.expires_at() - Utc::now()
    }
}