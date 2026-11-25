use contract::{ActionResult, Outcome};

use crate::draft;
use crate::error::IncidentError;
use crate::proposal_store;
use crate::store::{self, Flag};
use crate::{Approval, Command, Notice, ProposedAction, Settings, Verdict};

pub(crate) fn approval_requested(
    conn: &rusqlite::Connection,
    settings: &Settings,
    approval: &Approval,
    now: u64,
) -> Result<Vec<Command>, IncidentError> {
    let Some((number, pending)) = proposal_store::find_pending(
        conn,
        approval.incident,
        &approval.action,
        approval.button_issued_at_unix,
    )?
    else {
        store::log(conn, now, "approval_ignored", "no matching proposal")?;
        return Ok(Vec::new());
    };
    let approved = match pending.approve(approval, &settings.owner, settings.button_ttl_secs, now) {
        Ok(approved) => approved,
        Err(rejection) => {
            store::log(conn, now, "approval_rejected", &format!("{rejection:?}"))?;
            return Ok(Vec::new());
        }
    };
    let request = approved.into_request(number, now)?;
    proposal_store::mark_approved(conn, number, request.request_id().as_str())?;
    Ok(vec![Command::WriteRequest(request)])
}

pub(crate) fn execution_result(
    conn: &rusqlite::Connection,
    result: &ActionResult,
) -> Result<Vec<Command>, IncidentError> {
    let Some((incident, action)) = proposal_store::by_request(conn, result.request_id().as_str())?
    else {
        return Ok(Vec::new());
    };
    if matches!(
        (&action, result.outcome()),
        (ProposedAction::RestartBackend, Outcome::Executed {})
    ) {
        store::set_flag(conn, incident, Flag::RestartExecuted)?;
    }
    Ok(vec![Command::Post(Notice::ActionFinished {
        incident,
        outcome: result.outcome().clone(),
    })])
}

pub(crate) fn verdict_given(
    conn: &rusqlite::Connection,
    incident: u64,
    verdict: &Verdict,
) -> Result<Vec<Command>, IncidentError> {
    let Some((names, summary)) = store::draft_source(conn, incident)? else {
        return Ok(Vec::new());
    };
    let actual = match verdict {
        Verdict::Wrong { actual_cause } => Some(actual_cause.as_str()),
        Verdict::Correct => None,
    };
    store::set_verdict(conn, incident, draft::verdict_text(verdict), actual)?;
    Ok(vec![Command::SaveDraft {
        incident,
        markdown: draft::markdown(incident, &names, &summary, verdict),
    }])
}
