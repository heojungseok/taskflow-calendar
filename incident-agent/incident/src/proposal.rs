use contract::{
    Action, ActionRequest, DiscordUserId, IncidentId, InvalidValue, MAX_REQUEST_TTL_SECS,
    RequestId, TotpCode,
};

use crate::{Approval, ProposedAction};

#[derive(Debug)]
pub struct Pending;

#[derive(Debug)]
pub struct Approved {
    by: DiscordUserId,
    code: TotpCode,
}

#[derive(Debug)]
pub(crate) enum Rejection {
    NotOwner,
    Expired,
}

/// A pending proposal cannot produce a request; only an approved one can.
///
/// ```compile_fail,E0599
/// use incident::{Proposal, ProposedAction};
///
/// let pending = Proposal::pending(1, ProposedAction::RestartBackend);
/// let _ = pending.into_request(1, 0);
/// ```
#[derive(Debug)]
pub struct Proposal<S> {
    incident: u64,
    action: ProposedAction,
    state: S,
}

impl Proposal<Pending> {
    pub fn pending(incident: u64, action: ProposedAction) -> Self {
        Self {
            incident,
            action,
            state: Pending,
        }
    }

    pub(crate) fn approve(
        self,
        approval: &Approval,
        owner: &DiscordUserId,
        ttl_secs: u64,
        now_unix: u64,
    ) -> Result<Proposal<Approved>, Rejection> {
        if &approval.by != owner {
            return Err(Rejection::NotOwner);
        }
        if now_unix.saturating_sub(approval.button_issued_at_unix) > ttl_secs {
            return Err(Rejection::Expired);
        }
        Ok(Proposal {
            incident: self.incident,
            action: self.action,
            state: Approved {
                by: approval.by.clone(),
                code: approval.code.clone(),
            },
        })
    }
}

impl Proposal<Approved> {
    pub fn into_request(self, number: u64, now_unix: u64) -> Result<ActionRequest, InvalidValue> {
        let request_id = RequestId::try_from(format!("req-{}-{number}", self.incident))?;
        let incident_id = IncidentId::try_from(format!("inc-{}", self.incident))?;
        let action = match self.action {
            ProposedAction::RestartBackend => Action::RestartBackend {},
            ProposedAction::Rollback { target_sha } => Action::Rollback { target_sha },
        };
        ActionRequest::new(
            request_id,
            incident_id,
            action,
            self.state.by,
            self.state.code,
            now_unix,
            now_unix + MAX_REQUEST_TTL_SECS,
        )
    }
}
