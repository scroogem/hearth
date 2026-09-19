use crate::{Error, Result, Transport, TransportConfig, ConnectionInfo, SessionInfo};
use async_trait::async_trait;
use bytes::Bytes;
use std::net::SocketAddr;
use tokio::sync::mpsc;
use uuid::Uuid;
use tracing::{info, warn};

pub struct SteamTransport {
    config: Option<TransportConfig>,
    session: Option<SteamSession>,
    tx: Option<mpsc::UnboundedSender<Bytes>>,
    rx: Option<mpsc::UnboundedReceiver<Bytes>>,
    initialized: bool,
}

struct SteamSession {
    session_id: Uuid,
    host_id: Uuid,
    peer_id: Uuid,
    lobby_id: String,
    steam_id: Option<u64>,
    established_at: chrono::DateTime<chrono::Utc>,
    bytes_sent: u64,
    bytes_received: u64,
    connected: bool,
}

impl SteamTransport {
    pub fn new() -> Self {
        Self {
            config: None,
            session: None,
            tx: None,
            rx: None,
            initialized: false,
        }
    }

    fn check_steam_available(&self) -> Result<()> {
        // Check if Steam is running by trying to initialize steamworks
        // For now, we'll just check if the steam client process exists
        #[cfg(target_os = "linux")]
        {
            use std::process::Command;
            let output = Command::new("pgrep").arg("steam").output();
            if output.is_err() || !output.unwrap().status.success() {
                return Err(Error::NotAvailable(
                    "Steam client not running. Please start Steam and try again.".to_string()
                ));
            }
        }
        #[cfg(target_os = "windows")]
        {
            use std::process::Command;
            let output = Command::new("tasklist").arg("/FI").arg("IMAGENAME eq steam.exe").output();
            if output.is_err() {
                return Err(Error::NotAvailable(
                    "Steam client not running. Please start Steam and try again.".to_string()
                ));
            }
        }
        Ok(())
    }
}

#[async_trait]
impl Transport for SteamTransport {
    async fn initialize(&mut self, config: &TransportConfig) -> Result<()> {
        info!("Initializing Steam transport");
        
        self.check_steam_available()?;
        
        let (tx, rx) = mpsc::unbounded_channel();
        self.tx = Some(tx);
        self.rx = Some(rx);
        self.config = Some(config.clone());
        self.initialized = true;
        
        info!("Steam transport initialized successfully (stub)");
        Ok(())
    }

    async fn create_session(&mut self, world_id: Uuid, host_id: Uuid) -> Result<ConnectionInfo> {
        if !self.initialized {
            return Err(Error::NotAvailable("Transport not initialized".to_string()));
        }

        info!("Creating Steam session for world {} (stub)", world_id);

        // In a real implementation, this would create a Steam lobby
        // For now, we generate a fake lobby ID
        let lobby_id = format!("lobby_{}", Uuid::new_v4().simple());
        let session_id = Uuid::new_v4();
        
        let session = SteamSession {
            session_id,
            host_id,
            peer_id: host_id,
            lobby_id: lobby_id.clone(),
            steam_id: None,
            established_at: chrono::Utc::now(),
            bytes_sent: 0,
            bytes_received: 0,
            connected: true,
        };

        self.session = Some(session);

        warn!("Steam transport is a stub - real Steam Networking Sockets integration needed");
        
        Ok(ConnectionInfo {
            transport: crate::traits::TransportType::Steam,
            steam_lobby_id: Some(lobby_id),
            steam_session_id: Some(session_id.to_string()),
            direct_address: None,
            metadata: std::collections::HashMap::new(),
        })
    }

    async fn join_session(&mut self, connection_info: &ConnectionInfo, peer_id: Uuid) -> Result<()> {
        if !self.initialized {
            return Err(Error::NotAvailable("Transport not initialized".to_string()));
        }

        let lobby_id = connection_info.steam_lobby_id
            .as_ref()
            .ok_or_else(|| Error::Config("No lobby ID in connection info".to_string()))?;

        info!("Joining Steam lobby: {} (stub)", lobby_id);

        let session_id = connection_info.steam_session_id
            .as_ref()
            .and_then(|s| Uuid::parse_str(s).ok())
            .unwrap_or_else(Uuid::new_v4);

        let session = SteamSession {
            session_id,
            host_id: connection_info.metadata.get("host_id")
                .and_then(|s| Uuid::parse_str(s).ok())
                .unwrap_or_default(),
            peer_id,
            lobby_id: lobby_id.clone(),
            steam_id: None,
            established_at: chrono::Utc::now(),
            bytes_sent: 0,
            bytes_received: 0,
            connected: true,
        };

        self.session = Some(session);
        info!("Successfully joined Steam lobby (stub)");
        Ok(())
    }

    async fn send(&mut self, data: Bytes) -> Result<()> {
        let session = self.session.as_mut()
            .ok_or_else(|| Error::Session("No active session".to_string()))?;

        if !session.connected {
            return Err(Error::PeerDisconnected("Session not connected".to_string()));
        }

        if let Some(tx) = &self.tx {
            tx.send(data.clone())
                .map_err(|_| Error::Session("Channel closed".to_string()))?;
            session.bytes_sent += data.len() as u64;
        }

        Ok(())
    }

    async fn receive(&mut self) -> Result<Option<Bytes>> {
        if let Some(rx) = &mut self.rx {
            match rx.recv().await {
                Some(data) => {
                    if let Some(session) = &mut self.session {
                        session.bytes_received += data.len() as u64;
                    }
                    Ok(Some(data))
                }
                None => Ok(None),
            }
        } else {
            Ok(None)
        }
    }

    async fn close(&mut self) -> Result<()> {
        info!("Closing Steam transport session");
        
        if let Some(session) = &mut self.session {
            session.connected = false;
        }
        
        self.tx = None;
        self.rx = None;
        self.session = None;
        
        Ok(())
    }

    fn is_connected(&self) -> bool {
        self.session.as_ref().map(|s| s.connected).unwrap_or(false)
    }

    fn session_info(&self) -> Option<SessionInfo> {
        self.session.as_ref().map(|s| SessionInfo {
            session_id: s.session_id,
            host_id: s.host_id,
            peer_id: s.peer_id,
            established_at: s.established_at,
            transport: crate::traits::TransportType::Steam,
            bytes_sent: s.bytes_sent,
            bytes_received: s.bytes_received,
        })
    }

    fn local_addr(&self) -> Option<SocketAddr> {
        None
    }

    fn peer_addr(&self) -> Option<SocketAddr> {
        None
    }
}

impl Default for SteamTransport {
    fn default() -> Self {
        Self::new()
    }
}