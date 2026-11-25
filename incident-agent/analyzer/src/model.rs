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
pub struct Evidence {
    pub logs: Vec<LogLine>,
    pub baseline_logs: Vec<LogLine>,
    pub commits: Vec<Commit>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Knowledge {
    pub incidents: Vec<KnownIncident>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Report {
    pub alerts: Vec<Alert>,
    pub known_incident: Option<String>,
    pub suspects: Vec<Suspect>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LogLine {
    pub service: Service,
    pub at_unix: u64,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KnownIncident {
    pub source: String,
    pub signature: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Suspect {
    NewError {
        fingerprint: String,
        first_seen_unix: u64,
        count: usize,
    },
    FirstChangedMetric {
        name: String,
        changed_at_unix: u64,
    },
    RelatedCommit {
        sha: String,
    },
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Commit {
    pub sha: String,
    pub files: Vec<String>,
}
