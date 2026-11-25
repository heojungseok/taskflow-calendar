use std::fmt;

#[derive(Debug)]
pub enum IncidentError {
    Storage(rusqlite::Error),
    Contract(contract::InvalidValue),
}

impl fmt::Display for IncidentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Storage(e) => write!(f, "incident storage error: {e}"),
            Self::Contract(e) => write!(f, "invalid contract value: {e}"),
        }
    }
}

impl std::error::Error for IncidentError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Storage(e) => Some(e),
            Self::Contract(e) => Some(e),
        }
    }
}

impl From<rusqlite::Error> for IncidentError {
    fn from(e: rusqlite::Error) -> Self {
        Self::Storage(e)
    }
}

impl From<contract::InvalidValue> for IncidentError {
    fn from(e: contract::InvalidValue) -> Self {
        Self::Contract(e)
    }
}
