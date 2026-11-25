use analyzer::{Alert, Analysis, Report, Service};

use crate::draft;
use crate::error::IncidentError;
use crate::proposal_store;
use crate::store::{self, Flag, Stored};
use crate::{Command, Deploy, DeployScope, Notice, ProposedAction, Settings};

const SECS_PER_DAY: u64 = 86_400;

pub(crate) fn service_names<'a>(services: impl Iterator<Item = &'a Service>) -> Vec<String> {
    let mut names: Vec<String> = services.map(|s| s.as_str().to_owned()).collect();
    names.sort();
    names.dedup();
    names
}

pub(crate) fn alerts_received(
    conn: &rusqlite::Connection,
    settings: &Settings,
    alerts: Vec<Alert>,
    now: u64,
) -> Result<Vec<Command>, IncidentError> {
    if alerts.is_empty() {
        return Ok(Vec::new());
    }
    let services = service_names(alerts.iter().map(|a| &a.service));
    if let Some(incident) = store::ongoing(conn, &services)? {
        if incident.restart_executed && !incident.escalated {
            return recurrence(conn, settings, &incident, now);
        }
        return Ok(Vec::new());
    }
    let started_at = alerts.iter().map(|a| a.starts_at_unix).min().unwrap_or(now);
    let limited = store::opened_on_day(conn, now / SECS_PER_DAY)? >= settings.daily_limit;
    store::open(conn, now, started_at, &alerts, limited)?;
    Ok(vec![if limited {
        Command::Post(Notice::LimitExceeded(alerts))
    } else {
        Command::Analyze(alerts)
    }])
}

fn recurrence(
    conn: &rusqlite::Connection,
    settings: &Settings,
    incident: &Stored,
    now: u64,
) -> Result<Vec<Command>, IncidentError> {
    let Some(deploy) = &incident.deploy else {
        return Ok(Vec::new());
    };
    if now.saturating_sub(deploy.at_unix) > settings.rollback_window_secs {
        return Ok(Vec::new());
    }
    store::set_flag(conn, incident.id, Flag::Escalated)?;
    Ok(vec![Command::Post(deploy_notice(
        conn,
        incident.id,
        deploy,
        now,
    )?)])
}

fn deploy_notice(
    conn: &rusqlite::Connection,
    incident: u64,
    deploy: &Deploy,
    now: u64,
) -> Result<Notice, IncidentError> {
    match deploy.scope {
        DeployScope::Backend | DeployScope::Frontend => {
            let action = ProposedAction::Rollback {
                target_sha: deploy.previous_sha.clone(),
            };
            proposal_store::insert(conn, incident, &action, now)?;
            Ok(Notice::Proposal {
                incident,
                action,
                issued_at_unix: now,
            })
        }
        DeployScope::Full | DeployScope::Migration => Ok(Notice::ManualOnly {
            incident,
            reason: "스키마가 바뀐 배포라 자동 되돌리기를 제안하지 않는다".to_owned(),
        }),
    }
}

pub(crate) fn analyzed(
    conn: &rusqlite::Connection,
    report: Report,
    last_deploy: Option<Deploy>,
) -> Result<Vec<Command>, IncidentError> {
    let services = service_names(report.alerts.iter().map(|a| &a.service));
    let Some(incident) = store::awaiting_analysis(conn, &services)? else {
        return Ok(Vec::new());
    };
    let grounded = matches!(report.analysis, Analysis::Guess(_));
    store::attach_report(
        conn,
        incident.id,
        grounded,
        &draft::summarize(&report),
        last_deploy.as_ref(),
    )?;
    Ok(vec![Command::Post(Notice::Incident {
        incident: incident.id,
        report,
    })])
}

pub(crate) fn tick(
    conn: &rusqlite::Connection,
    settings: &Settings,
    now: u64,
) -> Result<Vec<Command>, IncidentError> {
    let mut commands = Vec::new();
    for incident in store::unresolved_grounded(conn)? {
        let waited = now.saturating_sub(incident.started_at);
        if waited < settings.restart_after_secs
            || proposal_store::restart_proposed(conn, incident.id)?
        {
            continue;
        }
        let action = ProposedAction::RestartBackend;
        proposal_store::insert(conn, incident.id, &action, now)?;
        commands.push(Command::Post(Notice::Proposal {
            incident: incident.id,
            action,
            issued_at_unix: now,
        }));
    }
    Ok(commands)
}
