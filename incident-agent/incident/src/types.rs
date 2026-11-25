use analyzer::{Alert, Report, Service};
use contract::{ActionRequest, ActionResult, DiscordUserId, GitSha, Outcome, TotpCode};

#[derive(Debug, Clone)]
pub struct Settings {
    pub owner: DiscordUserId,
    pub daily_limit: u32,
    pub restart_after_secs: u64,
    pub button_ttl_secs: u64,
    pub rollback_window_secs: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProposedAction {
    RestartBackend,
    Rollback { target_sha: GitSha },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeployScope {
    Frontend,
    Backend,
    Full,
    Migration,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Deploy {
    pub sha: GitSha,
    pub previous_sha: GitSha,
    pub scope: DeployScope,
    pub at_unix: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Correct,
    Wrong { actual_cause: String },
}

#[derive(Debug, Clone)]
pub struct Approval {
    pub incident: u64,
    pub action: ProposedAction,
    pub by: DiscordUserId,
    pub code: TotpCode,
    pub button_issued_at_unix: u64,
}

#[derive(Debug, Clone)]
pub enum Event {
    AlertsReceived(Vec<Alert>),
    Analyzed {
        report: Report,
        last_deploy: Option<Deploy>,
    },
    AlertsResolved(Vec<Service>),
    Tick,
    VerdictGiven {
        incident: u64,
        by: DiscordUserId,
        verdict: Verdict,
    },
    ApprovalRequested(Approval),
    ExecutionResult(ActionResult),
}

#[derive(Debug, Clone)]
pub enum Notice {
    Incident {
        incident: u64,
        report: Report,
    },
    LimitExceeded(Vec<Alert>),
    Proposal {
        incident: u64,
        action: ProposedAction,
        issued_at_unix: u64,
    },
    ManualOnly {
        incident: u64,
        reason: String,
    },
    ActionFinished {
        incident: u64,
        outcome: Outcome,
    },
}

#[derive(Debug, Clone)]
pub enum Command {
    Analyze(Vec<Alert>),
    Post(Notice),
    WriteRequest(ActionRequest),
    SaveDraft { incident: u64, markdown: String },
}
