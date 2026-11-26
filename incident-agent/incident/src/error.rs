use std::fmt;

#[derive(Debug)]
pub(crate) enum Fail {
    Db(rusqlite::Error),
    Contract(contract::InvalidValue),
    Range(&'static str),
    Schema(i64),
}

pub(crate) type Res<T> = Result<T, Fail>;

impl From<rusqlite::Error> for Fail {
    fn from(e: rusqlite::Error) -> Self {
        Self::Db(e)
    }
}

impl From<contract::InvalidValue> for Fail {
    fn from(e: contract::InvalidValue) -> Self {
        Self::Contract(e)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Storage,
    InvalidValue,
    OutOfRange,
    Schema,
}

#[derive(Debug)]
pub struct IncidentError {
    kind: ErrorKind,
    message: String,
}

impl IncidentError {
    pub fn kind(&self) -> ErrorKind {
        self.kind
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl From<Fail> for IncidentError {
    fn from(fail: Fail) -> Self {
        let (kind, message) = match fail {
            Fail::Db(e) => (ErrorKind::Storage, e.to_string()),
            Fail::Contract(e) => (ErrorKind::InvalidValue, e.to_string()),
            Fail::Range(what) => (ErrorKind::OutOfRange, format!("{what} is out of range")),
            Fail::Schema(v) => (ErrorKind::Schema, format!("unsupported schema version {v}")),
        };
        Self { kind, message }
    }
}

impl fmt::Display for IncidentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "incident error ({:?}): {}", self.kind, self.message)
    }
}

impl std::error::Error for IncidentError {}
