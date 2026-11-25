#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Service(String);

impl Service {
    pub fn new(name: &str) -> Self {
        Self(name.to_owned())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Alert {
    pub name: String,
    pub service: Service,
    pub starts_at_unix: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Window {
    pub from_unix: u64,
    pub to_unix: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Evidence {}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Knowledge {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Report {
    pub alerts: Vec<Alert>,
}
