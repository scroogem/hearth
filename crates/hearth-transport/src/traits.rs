use crate::{Error, Result};
use async_trait::async_trait;
use bytes::Bytes;
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransportConfig {
    pub transport_type: TransportType,
    pub steam: Option<SteamConfig>,
    pub direct: Option<DirectConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum TransportType {
    Steam,
    Direct,
    Relay,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SteamConfig {
    pub app_id: u32,
    pub enable_relay: bool,
    pub relay_timeout_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DirectConfig {
    pub bind_address: String,
    pub port_range: Option<(u16, u16)>,
}

impl Default for TransportConfig {
    fn default() -> Self {
        Self {
            transport_type: TransportType::Steam,
            steam: Some(SteamConfig {
                app_id: 480,
                enable_relay: true,
                relay_timeout_ms: 10000,
            }),
            direct: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionInfo {
    pub transport: TransportType,
    pub steam_lobby_id: Option<String>,
    pub steam_session_id: Option<String>,
    pub direct_address: Option<String>,
    pub metadata: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: Uuid,
    pub host_id: Uuid,
    pub peer_id: Uuid,
    pub established_at: chrono::DateTime<chrono::Utc>,
    pub transport: TransportType,
    pub bytes_sent: u64,
    pub bytes_received: u64,
}

#[async_trait]
pub trait Transport: Send + Sync {
    async fn initialize(&mut self, config: &TransportConfig) -> Result<()>;

    async fn create_session(&mut self, world_id: Uuid, host_id: Uuid) -> Result<ConnectionInfo>;

    async fn join_session(&mut self, connection_info: &ConnectionInfo, peer_id: Uuid) -> Result<()>;

    async fn send(&mut self, data: Bytes) -> Result<()>;

    async fn receive(&mut self) -> Result<Option<Bytes>>;

    async fn close(&mut self) -> Result<()>;

    fn is_connected(&self) -> bool;

    fn session_info(&self) -> Option<SessionInfo>;

    fn local_addr(&self) -> Option<SocketAddr>;

    fn peer_addr(&self) -> Option<SocketAddr>;
}

pub struct TransportFactory;

impl TransportFactory {
    pub fn create(transport_type: TransportType) -> Box<dyn Transport> {
        match transport_type {
            TransportType::Steam => Box::new(crate::steam::SteamTransport::new()),
            TransportType::Direct => Box::new(crate::direct::DirectTransport::new()),
            TransportType::Relay => Box::new(crate::relay::RelayTransport::new()),
        }
    }
}