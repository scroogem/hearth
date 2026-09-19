use thiserror::Error;

#[derive(Error, Debug)]
pub enum Error {
    #[error("Storage error: {0}")]
    Storage(#[from] git2::Error),

    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Walkdir error: {0}")]
    Walkdir(#[from] walkdir::Error),

    #[error("Path strip prefix error: {0}")]
    StripPrefix(#[from] std::path::StripPrefixError),

    #[error("Join error: {0}")]
    Join(#[from] tokio::task::JoinError),

    #[error("Reqwest error: {0}")]
    Reqwest(#[from] reqwest::Error),

    #[error("RMP Serde decode error: {0}")]
    RmpSerdeDecode(#[from] rmp_serde::decode::Error),

    #[error("RMP Serde encode error: {0}")]
    RmpSerdeEncode(#[from] rmp_serde::encode::Error),

    #[error("Lock error: {0}")]
    Lock(String),

    #[error("World not found: {0}")]
    WorldNotFound(String),

    #[error("World already locked by {holder} until {expires}")]
    WorldLocked { holder: String, expires: String },

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("Invalid manifest: {0}")]
    InvalidManifest(String),

    #[error("Mod resolution error: {0}")]
    ModResolution(String),

    #[error("Network error: {0}")]
    Network(String),

    #[error("Transport error: {0}")]
    Transport(String),

    #[error("Invalid UUID: {0}")]
    InvalidUuid(#[from] uuid::Error),

    #[error("Git operation failed: {0}")]
    Git(String),

    #[error("Configuration error: {0}")]
    Config(String),
}

pub type Result<T> = std::result::Result<T, Error>;