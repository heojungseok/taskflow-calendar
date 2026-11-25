use contract::GitSha;
use rusqlite::{Connection, OptionalExtension, params};

use crate::ProposedAction;
use crate::db::{col, db, plus};
use crate::error::{Fail, Res};

const UNRESOLVED: &str =
    "EXISTS(SELECT 1 FROM alerts WHERE alerts.incident = proposals.incident AND resolved = 0)";

pub(crate) struct Expired {
    pub(crate) id: u64,
    pub(crate) incident: u64,
    pub(crate) action: ProposedAction,
    pub(crate) reoffers: u32,
}

pub(crate) struct RequestRow {
    pub(crate) proposal: u64,
    pub(crate) incident: u64,
    pub(crate) action: ProposedAction,
    pub(crate) answered: bool,
}

pub(crate) fn parts(action: &ProposedAction) -> (&'static str, Option<&str>) {
    match action {
        ProposedAction::RestartBackend => ("restart", None),
        ProposedAction::Rollback { target_sha } => ("rollback", Some(target_sha.as_str())),
    }
}

fn action_from(kind: &str, sha: Option<String>) -> Res<ProposedAction> {
    match (kind, sha) {
        ("restart", None) => Ok(ProposedAction::RestartBackend),
        ("rollback", Some(sha)) => Ok(ProposedAction::Rollback {
            target_sha: GitSha::try_from(sha)?,
        }),
        _ => Err(Fail::Range("stored proposal action")),
    }
}

pub(crate) fn insert(
    conn: &Connection,
    incident: u64,
    action: &ProposedAction,
    issued_at: u64,
    ttl_secs: u64,
    reoffers: u32,
) -> Res<()> {
    let (kind, sha) = parts(action);
    conn.execute(
        "INSERT INTO proposals(incident, kind, target_sha, issued_at, button_deadline, reoffers) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            db(incident)?,
            kind,
            sha,
            db(issued_at)?,
            db(plus(issued_at, ttl_secs)?)?,
            reoffers
        ],
    )?;
    Ok(())
}

pub(crate) fn restart_proposed(conn: &Connection, incident: u64) -> Res<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM proposals WHERE incident = ?1 AND kind = 'restart')",
        [db(incident)?],
        |r| r.get(0),
    )?)
}

pub(crate) fn outstanding_restart(conn: &Connection, now: u64) -> Res<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM proposals WHERE kind = 'restart' AND \
         ((state = 'open' AND button_deadline >= ?1) OR \
          (state = 'pending' AND request_deadline >= ?1)))",
        [db(now)?],
        |r| r.get(0),
    )?)
}

pub(crate) fn expired_open(conn: &Connection, now: u64) -> Res<Vec<Expired>> {
    let sql = format!(
        "SELECT id, incident, kind, target_sha, reoffers FROM proposals \
         WHERE state = 'open' AND button_deadline < ?1 AND {UNRESOLVED} ORDER BY id"
    );
    let mut statement = conn.prepare(&sql)?;
    let rows = statement.query_map([db(now)?], |r| {
        Ok((
            col(r, 0)?,
            col(r, 1)?,
            r.get::<_, String>(2)?,
            r.get(3)?,
            r.get(4)?,
        ))
    })?;
    let mut expired = Vec::new();
    for row in rows {
        let (id, incident, kind, sha, reoffers) = row?;
        expired.push(Expired {
            id,
            incident,
            action: action_from(&kind, sha)?,
            reoffers,
        });
    }
    Ok(expired)
}

pub(crate) fn close(conn: &Connection, id: u64, outcome: &str) -> Res<()> {
    conn.execute(
        "UPDATE proposals SET state = 'closed', outcome = ?2 WHERE id = ?1",
        params![db(id)?, outcome],
    )?;
    Ok(())
}

pub(crate) fn reopen(conn: &Connection, id: u64, outcome: &str) -> Res<()> {
    conn.execute(
        "UPDATE proposals SET state = 'open', request_deadline = NULL, outcome = ?2 WHERE id = ?1",
        params![db(id)?, outcome],
    )?;
    Ok(())
}

pub(crate) fn mark_pending(
    conn: &Connection,
    id: u64,
    attempt: u32,
    request_id: &str,
    request_deadline: u64,
) -> Res<()> {
    conn.execute(
        "UPDATE proposals SET state = 'pending', attempts = ?2, request_deadline = ?3 \
         WHERE id = ?1",
        params![db(id)?, attempt, db(request_deadline)?],
    )?;
    conn.execute(
        "INSERT INTO requests(request_id, proposal) VALUES (?1, ?2)",
        params![request_id, db(id)?],
    )?;
    Ok(())
}

pub(crate) fn request(conn: &Connection, request_id: &str) -> Res<Option<RequestRow>> {
    let row = conn
        .query_row(
            "SELECT r.proposal, p.incident, p.kind, p.target_sha, r.outcome IS NOT NULL \
             FROM requests r JOIN proposals p ON p.id = r.proposal WHERE r.request_id = ?1",
            [request_id],
            |r| {
                Ok((
                    col(r, 0)?,
                    col(r, 1)?,
                    r.get::<_, String>(2)?,
                    r.get(3)?,
                    r.get::<_, bool>(4)?,
                ))
            },
        )
        .optional()?;
    row.map(|(proposal, incident, kind, sha, answered)| {
        Ok(RequestRow {
            proposal,
            incident,
            action: action_from(&kind, sha)?,
            answered,
        })
    })
    .transpose()
}

pub(crate) fn answer_request(conn: &Connection, request_id: &str, outcome: &str) -> Res<()> {
    conn.execute(
        "UPDATE requests SET outcome = ?2 WHERE request_id = ?1",
        params![request_id, outcome],
    )?;
    Ok(())
}
