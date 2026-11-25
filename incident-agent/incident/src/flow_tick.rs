use rusqlite::Connection;

use crate::error::Res;
use crate::flow_alerts::restart_wait_end;
use crate::{Command, Notice, ProposedAction, Settings};
use crate::{incident_store, proposal_store};

pub(crate) fn tick(conn: &Connection, settings: &Settings, now: u64) -> Res<Vec<Command>> {
    let mut commands = expire(conn, settings, now)?;
    commands.extend(propose_restarts(conn, settings, now)?);
    Ok(commands)
}

fn expire(conn: &Connection, settings: &Settings, now: u64) -> Res<Vec<Command>> {
    let mut commands = Vec::new();
    for row in proposal_store::expired_open(conn, now)? {
        proposal_store::close(conn, row.id, "expired")?;
        if row.reoffers > 0 {
            commands.push(Command::Post(Notice::ManualOnly {
                incident: row.incident,
                reason: "승인 버튼이 두 번 만료돼 사람이 직접 처리해야 한다".to_owned(),
            }));
            continue;
        }
        proposal_store::insert(
            conn,
            row.incident,
            &row.action,
            now,
            settings.button_ttl_secs,
            1,
        )?;
        commands.push(Command::Post(Notice::Proposal {
            incident: row.incident,
            action: row.action,
            issued_at_unix: now,
        }));
    }
    Ok(commands)
}

fn propose_restarts(conn: &Connection, settings: &Settings, now: u64) -> Res<Vec<Command>> {
    let mut commands = Vec::new();
    for candidate in incident_store::restart_candidates(conn)? {
        let due = restart_wait_end(
            candidate.first_seen_at,
            candidate.earliest_start,
            settings.restart_after_secs,
        )?;
        if now < due
            || proposal_store::restart_proposed(conn, candidate.id)?
            || proposal_store::outstanding_restart(conn, now)?
        {
            continue;
        }
        let action = ProposedAction::RestartBackend;
        proposal_store::insert(
            conn,
            candidate.id,
            &action,
            now,
            settings.button_ttl_secs,
            0,
        )?;
        commands.push(Command::Post(Notice::Proposal {
            incident: candidate.id,
            action,
            issued_at_unix: now,
        }));
    }
    Ok(commands)
}
