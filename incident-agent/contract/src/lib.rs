use serde::{Deserialize, Serialize};

mod values;

pub use values::{DiscordUserId, GitSha, IncidentId, InvalidValue, RequestId, TotpCode};

pub const MAX_REQUEST_TTL_SECS: u64 = 600;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Action {
    RestartBackend {},
    Rollback { target_sha: GitSha },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Outcome {
    Executed {},
    Rejected { reason: String },
    Failed { reason: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "RawActionRequest")]
pub struct ActionRequest {
    request_id: RequestId,
    incident_id: IncidentId,
    action: Action,
    requested_by: DiscordUserId,
    totp_code: TotpCode,
    issued_at_unix: u64,
    expires_at_unix: u64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawActionRequest {
    request_id: RequestId,
    incident_id: IncidentId,
    action: Action,
    requested_by: DiscordUserId,
    totp_code: TotpCode,
    issued_at_unix: u64,
    expires_at_unix: u64,
}

impl TryFrom<RawActionRequest> for ActionRequest {
    type Error = InvalidValue;

    fn try_from(raw: RawActionRequest) -> Result<Self, Self::Error> {
        Self::new(
            raw.request_id,
            raw.incident_id,
            raw.action,
            raw.requested_by,
            raw.totp_code,
            raw.issued_at_unix,
            raw.expires_at_unix,
        )
    }
}

impl ActionRequest {
    pub fn new(
        request_id: RequestId,
        incident_id: IncidentId,
        action: Action,
        requested_by: DiscordUserId,
        totp_code: TotpCode,
        issued_at_unix: u64,
        expires_at_unix: u64,
    ) -> Result<Self, InvalidValue> {
        let ttl_is_valid = expires_at_unix
            .checked_sub(issued_at_unix)
            .is_some_and(|ttl| (1..=MAX_REQUEST_TTL_SECS).contains(&ttl));
        if !ttl_is_valid {
            return Err(InvalidValue::new("expires_at_unix"));
        }
        Ok(Self {
            request_id,
            incident_id,
            action,
            requested_by,
            totp_code,
            issued_at_unix,
            expires_at_unix,
        })
    }

    pub fn request_id(&self) -> &RequestId {
        &self.request_id
    }

    pub fn incident_id(&self) -> &IncidentId {
        &self.incident_id
    }

    pub fn action(&self) -> &Action {
        &self.action
    }

    pub fn requested_by(&self) -> &DiscordUserId {
        &self.requested_by
    }

    pub fn totp_code(&self) -> &TotpCode {
        &self.totp_code
    }

    pub fn issued_at_unix(&self) -> u64 {
        self.issued_at_unix
    }

    pub fn expires_at_unix(&self) -> u64 {
        self.expires_at_unix
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ActionResult {
    request_id: RequestId,
    outcome: Outcome,
    finished_at_unix: u64,
}

impl ActionResult {
    pub fn new(request_id: RequestId, outcome: Outcome, finished_at_unix: u64) -> Self {
        Self {
            request_id,
            outcome,
            finished_at_unix,
        }
    }

    pub fn request_id(&self) -> &RequestId {
        &self.request_id
    }

    pub fn outcome(&self) -> &Outcome {
        &self.outcome
    }

    pub fn finished_at_unix(&self) -> u64 {
        self.finished_at_unix
    }
}
