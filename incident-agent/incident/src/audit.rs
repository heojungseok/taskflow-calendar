use contract::Outcome;
use rusqlite::{Connection, params};

use crate::db::db;
use crate::error::Res;
use crate::{Event, ProposedAction};

pub(crate) fn action_text(action: &ProposedAction) -> String {
    match action {
        ProposedAction::RestartBackend => "restart_backend".to_owned(),
        ProposedAction::Rollback { target_sha } => format!("rollback:{}", target_sha.as_str()),
    }
}

pub(crate) fn outcome_text(outcome: &Outcome) -> String {
    match outcome {
        Outcome::Executed {} => "executed".to_owned(),
        Outcome::Rejected { reason } => format!("rejected: {reason}"),
        Outcome::Failed { reason } => format!("failed: {reason}"),
    }
}

pub(crate) fn describe(event: &Event) -> (&'static str, String) {
    match event {
        Event::AlertsReceived(alerts) => {
            let list: Vec<String> = alerts
                .iter()
                .map(|a| format!("{}/{}@{}", a.name, a.service.as_str(), a.starts_at_unix))
                .collect();
            ("alerts_received", list.join(", "))
        }
        Event::Analyzed { batch, reports, .. } => (
            "analyzed",
            format!("batch={batch} reports={}", reports.len()),
        ),
        Event::AlertsResolved(alerts) => {
            let list: Vec<String> = alerts
                .iter()
                .map(|a| format!("{}/{}", a.name, a.service.as_str()))
                .collect();
            ("alerts_resolved", list.join(", "))
        }
        Event::Tick => ("tick", String::new()),
        Event::VerdictGiven {
            incident,
            by,
            verdict,
        } => (
            "verdict_given",
            format!(
                "incident={incident} by={} verdict={}",
                by.as_str(),
                crate::draft::verdict_text(verdict)
            ),
        ),
        Event::ApprovalRequested(approval) => (
            "approval_requested",
            format!(
                "incident={} action={} by={} button_issued_at={}",
                approval.incident,
                action_text(&approval.action),
                approval.by.as_str(),
                approval.button_issued_at_unix
            ),
        ),
        Event::ExecutionResult(result) => (
            "execution_result",
            format!(
                "request={} outcome={}",
                result.request_id().as_str(),
                outcome_text(result.outcome())
            ),
        ),
    }
}

pub(crate) fn record(conn: &Connection, now: u64, kind: &str, summary: &str) -> Res<()> {
    conn.execute(
        "INSERT INTO events(at_unix, kind, summary) VALUES (?1, ?2, ?3)",
        params![db(now)?, kind, summary],
    )?;
    Ok(())
}
