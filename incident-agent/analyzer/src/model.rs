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

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Evidence {
    pub logs: Vec<LogLine>,
    pub baseline_logs: Vec<LogLine>,
    pub commits: Vec<Commit>,
    pub metrics: Vec<MetricSeries>,
    pub missing: Vec<Missing>,
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
    pub missing: Vec<Missing>,
    pub analysis: Analysis,
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
        first: ErrorGroup,
        most_frequent: Option<ErrorGroup>,
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

#[derive(Debug, Clone, PartialEq)]
pub struct MetricSeries {
    pub name: String,
    pub baseline: f64,
    pub floor: f64,
    pub points: Vec<(u64, f64)>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ErrorGroup {
    pub fingerprint: String,
    pub logger: String,
    pub first_seen_unix: u64,
    pub count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Missing {
    Evidence,
    Logs,
    Baseline,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Analysis {
    Guess(Guess),
    Unknown,
    Skipped(SkipReason),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Guess {
    cause: String,
    evidence: String,
    confidence: Confidence,
    cited_lines: Vec<usize>,
}

impl Guess {
    pub(crate) fn new(
        cause: String,
        evidence: String,
        confidence: Confidence,
        cited_lines: Vec<usize>,
    ) -> Self {
        Self {
            cause,
            evidence,
            confidence,
            cited_lines,
        }
    }

    pub fn cause(&self) -> &str {
        &self.cause
    }

    pub fn evidence(&self) -> &str {
        &self.evidence
    }

    pub fn confidence(&self) -> Confidence {
        self.confidence
    }

    pub fn cited_lines(&self) -> &[usize] {
        &self.cited_lines
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Confidence {
    High,
    Medium,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkipReason {
    ModelTimeout,
    ModelUnavailable,
    InvalidAnswer,
    UnsupportedCitation,
    NoEvidence,
    KnownIncident,
    CallLimit,
}
