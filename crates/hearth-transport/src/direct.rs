use crate::{Error, Result, Transport, TransportConfig, ConnectionInfo, SessionInfo};
use async_trait::async_trait;
use bytes::Bytes;
use std::net::SocketAddr;
use uuid::Uuid;
use tracing::{info, warn};

pub struct DirectTransport {
    config: Option<TransportConfig>,
    session: Option<DirectSession>,
}

struct DirectSession {
    session_id: Uuid,
    host_id: Uuid,
    peer_id: Uuid,
    local_addr: SocketAddr,
    peer_addr: SocketAddr,
    established_at: chrono::DateTime<chrono::Utc>,
    bytes_sent: u64,
    bytes_received: u64,
    connected: bool,
}

impl DirectTransport {
    pub fn new() -> Self {
        Self {
            config: None,
            session: None,
        }
    }
}

#[async_trait]
impl Transport for DirectTransport {
    async fn initialize(&mut self, config: &TransportConfig) -> Result<()> {
        info!("Initializing Direct transport (stub)");
        self.config = Some(config.clone());
        Ok(())
    }

    async fn create_session(&mut self, world_id: Uuid, host_id: Uuid) -> Result<ConnectionInfo> {
        warn!("Direct transport create_session not implemented");
        Err(Error::NotAvailable("Direct transport not implemented".to_string()))
    }

    async fn join_session(&mut self, connection_info: &ConnectionInfo, peer_id: Uuid) -> Result<()> {
        warn!("Direct transport join_session not implemented");
        Err(Error::NotAvailable("Direct transport not implemented".to_string()))
    }

    async fn send(&mut self, data: Bytes) -> Result<()> {
        Err(Error::NotAvailable("Direct transport not implemented".to_string()))
    }

    async fn receive(&mut self) -> Result<Option<Bytes>> {
        Err(Error::NotAvailable("Direct transport not implemented".to_string()))
    }

    async fn close(&mut self) -> Result<()> {
        self.session = None;
        Ok(())
    }

    fn is_connected(&self) -> bool {
        false
    }

    fn session_info(&self) -> Option<SessionInfo> {
        None
    }

    fn local_addr(&self) -> Option<SocketAddr> {
        None
    }

    fn peer_addr(&self) -> Option<SocketAddr> {
        None
    }
}