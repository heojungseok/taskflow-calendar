use contract::{
    Action, ActionRequest, DiscordUserId, IncidentId, MAX_REQUEST_TTL_SECS, RequestId, TotpCode,
};
use rusqlite::{Connection, OptionalExtension, params};

use crate::db::{col, db, plus};
use crate::error::Res;
use crate::proposal_store::parts;
use crate::{Approval, ProposedAction};

pub(crate) struct Pending;

pub(crate) struct Approved {
    by: DiscordUserId,
    code: TotpCode,
}

pub(crate) enum Rejection {
    NotOwner,
    Expired,
}

impl Rejection {
    pub(crate) fn reason(&self) -> &'static str {
        match self {
            Self::NotOwner => "presser is not the owner",
            Self::Expired => "button expired",
        }
    }
}

pub(crate) struct Proposal<S> {
    id: u64,
    incident: u64,
    action: ProposedAction,
    issued_at: u64,
    button_deadline: u64,
    attempt: u32,
    state: S,
}

impl Proposal<Pending> {
    fn from_row(
        incident: u64,
        action: ProposedAction,
        row: &rusqlite::Row<'_>,
    ) -> rusqlite::Result<Self> {
        Ok(Self {
            id: col(row, 0)?,
            incident,
            action,
            issued_at: col(row, 1)?,
            button_deadline: col(row, 2)?,
            attempt: row.get::<_, u32>(3)? + 1,
            state: Pending,
        })
    }

    pub(crate) fn find_open(conn: &Connection, approval: &Approval) -> Res<Option<Self>> {
        let (kind, sha) = parts(&approval.action);
        let incident = db(approval.incident)?;
        let found = conn
            .query_row(
                "SELECT id, issued_at, button_deadline, attempts FROM proposals \
                 WHERE incident = ?1 AND kind = ?2 AND target_sha IS ?3 AND issued_at = ?4 \
                 AND state = 'open' \
                 AND EXISTS(SELECT 1 FROM alerts WHERE incident = ?1 AND resolved = 0) \
                 ORDER BY id LIMIT 1",
                params![incident, kind, sha, db(approval.button_issued_at_unix)?],
                |row| Self::from_row(approval.incident, approval.action.clone(), row),
            )
            .optional()?;
        Ok(found)
    }

    pub(crate) fn approve(
        self,
        approval: &Approval,
        owner: &DiscordUserId,
        now_unix: u64,
    ) -> Result<Proposal<Approved>, Rejection> {
        if &approval.by != owner {
            return Err(Rejection::NotOwner);
        }
        if now_unix > self.button_deadline {
            return Err(Rejection::Expired);
        }
        Ok(Proposal {
            id: self.id,
            incident: self.incident,
            action: self.action,
            issued_at: self.issued_at,
            button_deadline: self.button_deadline,
            attempt: self.attempt,
            state: Approved {
                by: approval.by.clone(),
                code: approval.code.clone(),
            },
        })
    }
}

impl Proposal<Approved> {
    pub(crate) fn id(&self) -> u64 {
        self.id
    }

    pub(crate) fn attempt(&self) -> u32 {
        self.attempt
    }

    pub(crate) fn into_request(self, now_unix: u64) -> Res<ActionRequest> {
        let request_id = RequestId::try_from(format!(
            "req-{}-{}-{}",
            self.incident, self.issued_at, self.attempt
        ))?;
        let incident_id = IncidentId::try_from(format!("inc-{}", self.incident))?;
        let action = match self.action {
            ProposedAction::RestartBackend => Action::RestartBackend {},
            ProposedAction::Rollback { target_sha } => Action::Rollback { target_sha },
        };
        Ok(ActionRequest::new(
            request_id,
            incident_id,
            action,
            self.state.by,
            self.state.code,
            now_unix,
            plus(now_unix, MAX_REQUEST_TTL_SECS)?,
        )?)
    }
}
