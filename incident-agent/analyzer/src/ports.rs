use std::fmt;

use crate::model::{Evidence, Service, Window};

pub trait Clock {
    fn now_unix(&self) -> u64;
}

pub trait EvidenceSource {
    fn collect(&self, window: &Window, services: &[Service]) -> Result<Evidence, EvidenceError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EvidenceError {
    LogsUnavailable,
    Other(String),
}

impl fmt::Display for EvidenceError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::LogsUnavailable => f.write_str("logs unavailable"),
            Self::Other(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for EvidenceError {}
