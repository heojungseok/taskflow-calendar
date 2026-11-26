use contract::{ActionResult, DiscordUserId, Outcome};
use rusqlite::Connection;

use crate::audit::{outcome_text, record};
use crate::error::Res;
use crate::proposal::Proposal;
use crate::{Approval, Command, Notice, ProposedAction, Settings, Verdict};
use crate::{draft, incident_store, proposal_store};

pub(crate) fn approval_requested(
    conn: &Connection,
    settings: &Settings,
    approval: &Approval,
    now: u64,
) -> Res<Vec<Command>> {
    let Some(pending) = Proposal::find_open(conn, approval)? else {
        record(
            conn,
            now,
            "approval_ignored",
            "no open proposal for this incident, action and button time",
        )?;
        return Ok(Vec::new());
    };
    let approved = match pending.approve(approval, &settings.owner, now) {
        Ok(approved) => approved,
        Err(rejection) => {
            record(conn, now, "approval_ignored", rejection.reason())?;
            return Ok(Vec::new());
        }
    };
    let (id, attempt) = (approved.id(), approved.attempt());
    let request = approved.into_request(now)?;
    proposal_store::mark_pending(
        conn,
        id,
        attempt,
        request.request_id().as_str(),
        request.expires_at_unix(),
    )?;
    Ok(vec![Command::WriteRequest(request)])
}

pub(crate) fn execution_result(
    conn: &Connection,
    result: &ActionResult,
    now: u64,
) -> Res<Vec<Command>> {
    let request_id = result.request_id().as_str();
    let Some(request) = proposal_store::request(conn, request_id)? else {
        record(conn, now, "result_ignored", "unknown request id")?;
        return Ok(Vec::new());
    };
    if request.answered {
        record(conn, now, "result_ignored", "duplicate result")?;
        return Ok(Vec::new());
    }
    let text = outcome_text(result.outcome());
    proposal_store::answer_request(conn, request_id, &text)?;
    let finished = Command::Post(Notice::ActionFinished {
        incident: request.incident,
        outcome: result.outcome().clone(),
    });
    match result.outcome() {
        Outcome::Executed {} => {
            proposal_store::close(conn, request.proposal, &text)?;
            if request.action == ProposedAction::RestartBackend {
                incident_store::mark_restart_executed(
                    conn,
                    request.incident,
                    result.finished_at_unix(),
                )?;
            }
            Ok(vec![finished])
        }
        Outcome::Rejected { .. } => {
            proposal_store::reopen(conn, request.proposal, &text)?;
            Ok(vec![finished])
        }
        Outcome::Failed { .. } => {
            proposal_store::close(conn, request.proposal, &text)?;
            let manual = Command::Post(Notice::ManualOnly {
                incident: request.incident,
                reason: "실행이 실패해 사람이 직접 처리해야 한다".to_owned(),
            });
            Ok(vec![finished, manual])
        }
    }
}

pub(crate) fn verdict_given(
    conn: &Connection,
    settings: &Settings,
    incident: u64,
    by: &DiscordUserId,
    verdict: &Verdict,
    now: u64,
) -> Res<Vec<Command>> {
    if by != &settings.owner {
        record(conn, now, "verdict_ignored", "presser is not the owner")?;
        return Ok(Vec::new());
    }
    let Some(data) = incident_store::draft_data(conn, incident)? else {
        record(conn, now, "verdict_ignored", "unknown incident")?;
        return Ok(Vec::new());
    };
    let actual = match verdict {
        Verdict::Wrong { actual_cause } => Some(actual_cause.as_str()),
        Verdict::Correct => None,
    };
    incident_store::set_verdict(conn, incident, draft::verdict_text(verdict), actual)?;
    Ok(vec![Command::SaveDraft {
        incident,
        markdown: draft::markdown(incident, &data, verdict),
    }])
}
