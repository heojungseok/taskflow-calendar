use std::collections::HashSet;

use analyzer::{Alert, Analysis, Report};
use rusqlite::Connection;

use crate::db::{kst_day, plus};
use crate::error::Res;
use crate::incident_store::{self, Opening};
use crate::{Command, Deploy, DeployScope, Notice, ProposedAction, Settings};
use crate::{alerts_store, draft, proposal_store};

pub(crate) fn alerts_received(
    conn: &Connection,
    settings: &Settings,
    alerts: Vec<Alert>,
    now: u64,
) -> Res<Vec<Command>> {
    let mut commands = Vec::new();
    let mut fresh = Vec::new();
    let mut seen = HashSet::new();
    for alert in alerts {
        let identity = (alert.name.clone(), alert.service.as_str().to_owned());
        if !seen.insert(identity) {
            continue;
        }
        let tracked = alerts_store::tracked_unresolved(conn, &alert)?;
        match alerts_store::recurrence_of(conn, &alert, settings.recurrence_window_secs)? {
            Some(incident) => {
                if !tracked {
                    alerts_store::insert(conn, &alert, now, Some(incident), None, false)?;
                }
                commands.extend(recurrence(conn, settings, incident, now)?);
            }
            None if !tracked => fresh.push(alert),
            None => {}
        }
    }
    if !fresh.is_empty() {
        commands.push(admit(conn, settings, fresh, now)?);
    }
    Ok(commands)
}

fn admit(conn: &Connection, settings: &Settings, fresh: Vec<Alert>, now: u64) -> Res<Command> {
    let day = kst_day(now)?;
    if alerts_store::opened_on_day(conn, day)? >= settings.daily_limit {
        for alert in &fresh {
            alerts_store::insert(conn, alert, now, None, None, true)?;
        }
        return Ok(Command::Post(Notice::LimitExceeded(fresh)));
    }
    let batch = alerts_store::new_batch(conn, now, day)?;
    for alert in &fresh {
        alerts_store::insert(conn, alert, now, None, Some(batch), false)?;
    }
    Ok(Command::Analyze {
        batch,
        alerts: fresh,
    })
}

fn recurrence(
    conn: &Connection,
    settings: &Settings,
    incident: u64,
    now: u64,
) -> Res<Vec<Command>> {
    let Some(stored) = incident_store::load(conn, incident)? else {
        return Ok(Vec::new());
    };
    let Some(deploy) = stored.deploy else {
        return Ok(Vec::new());
    };
    let recent = now.saturating_sub(deploy.at_unix) <= settings.rollback_window_secs;
    if stored.recurrence_handled || !recent {
        return Ok(Vec::new());
    }
    incident_store::mark_recurrence_handled(conn, incident)?;
    Ok(vec![Command::Post(deploy_notice(
        conn, settings, incident, &deploy, now,
    )?)])
}

fn deploy_notice(
    conn: &Connection,
    settings: &Settings,
    incident: u64,
    deploy: &Deploy,
    now: u64,
) -> Res<Notice> {
    if deploy.scope != DeployScope::Backend {
        return Ok(Notice::ManualOnly {
            incident,
            reason: "실행기가 되돌릴 수 없는 배포라 사람이 직접 처리해야 한다".to_owned(),
        });
    }
    let action = ProposedAction::Rollback {
        target_sha: deploy.previous_sha.clone(),
    };
    proposal_store::insert(conn, incident, &action, now, settings.button_ttl_secs, 0)?;
    Ok(Notice::Proposal {
        incident,
        action,
        issued_at_unix: now,
    })
}

pub(crate) fn analyzed(
    conn: &Connection,
    batch: u64,
    reports: Vec<Report>,
    last_deploy: Option<Deploy>,
    now: u64,
) -> Res<Vec<Command>> {
    if !alerts_store::consume_batch(conn, batch)? {
        return Ok(Vec::new());
    }
    let day = kst_day(now)?;
    let mut commands = Vec::new();
    for report in reports {
        let mut members = Vec::new();
        for alert in &report.alerts {
            if alerts_store::in_batch(conn, batch, alert)? {
                members.push(alert);
            }
        }
        if members.is_empty() {
            continue;
        }
        let guess = match &report.analysis {
            Analysis::Guess(guess) => Some(guess),
            _ => None,
        };
        let incident = incident_store::open(
            conn,
            &Opening {
                now,
                day,
                cause: guess.map(|g| g.cause()),
                evidence: guess.map(|g| g.evidence()),
                note: &draft::analysis_note(&report.analysis),
                summary: &draft::summarize(&report),
                deploy: last_deploy.as_ref(),
            },
        )?;
        for alert in members {
            alerts_store::attach(conn, batch, alert, incident)?;
        }
        commands.push(Command::Post(Notice::Incident { incident, report }));
    }
    Ok(commands)
}

pub(crate) fn resolved(conn: &Connection, alerts: &[Alert]) -> Res<Vec<Command>> {
    for alert in alerts {
        alerts_store::resolve(conn, alert)?;
    }
    Ok(Vec::new())
}

pub(crate) fn restart_wait_end(opened_at: u64, earliest_start: u64, wait_secs: u64) -> Res<u64> {
    plus(opened_at.max(earliest_start), wait_secs)
}
