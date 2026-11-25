use contract::GitSha;
use rusqlite::{Connection, OptionalExtension, params};

use crate::ProposedAction;
use crate::proposal::{Pending, Proposal};
use crate::store::{db, up};

fn parts(action: &ProposedAction) -> (&'static str, Option<&str>) {
    match action {
        ProposedAction::RestartBackend => ("restart", None),
        ProposedAction::Rollback { target_sha } => ("rollback", Some(target_sha.as_str())),
    }
}

fn action_from(kind: &str, sha: Option<String>) -> Option<ProposedAction> {
    match (kind, sha) {
        ("restart", None) => Some(ProposedAction::RestartBackend),
        ("rollback", Some(sha)) => Some(ProposedAction::Rollback {
            target_sha: GitSha::try_from(sha).ok()?,
        }),
        _ => None,
    }
}

pub(crate) fn insert(
    conn: &Connection,
    incident: u64,
    action: &ProposedAction,
    issued_at: u64,
) -> rusqlite::Result<()> {
    let (kind, sha) = parts(action);
    conn.execute(
        "INSERT INTO proposals(incident, kind, target_sha, issued_at) VALUES (?1, ?2, ?3, ?4)",
        params![db(incident), kind, sha, db(issued_at)],
    )?;
    Ok(())
}

pub(crate) fn restart_proposed(conn: &Connection, incident: u64) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM proposals WHERE incident = ?1 AND kind = 'restart')",
        [db(incident)],
        |r| r.get(0),
    )
}

pub(crate) fn find_pending(
    conn: &Connection,
    incident: u64,
    action: &ProposedAction,
    issued_at: u64,
) -> rusqlite::Result<Option<(u64, Proposal<Pending>)>> {
    let (kind, sha) = parts(action);
    let id = conn
        .query_row(
            "SELECT id FROM proposals WHERE incident = ?1 AND kind = ?2 AND target_sha IS ?3 \
             AND issued_at = ?4 AND approved = 0 ORDER BY id LIMIT 1",
            params![db(incident), kind, sha, db(issued_at)],
            |r| r.get::<_, i64>(0),
        )
        .optional()?;
    Ok(id.map(|id| (up(id), Proposal::pending(incident, action.clone()))))
}

pub(crate) fn mark_approved(conn: &Connection, id: u64, request_id: &str) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE proposals SET approved = 1, request_id = ?2 WHERE id = ?1",
        params![db(id), request_id],
    )?;
    Ok(())
}

pub(crate) fn by_request(
    conn: &Connection,
    request_id: &str,
) -> rusqlite::Result<Option<(u64, ProposedAction)>> {
    let row = conn
        .query_row(
            "SELECT incident, kind, target_sha FROM proposals WHERE request_id = ?1",
            [request_id],
            |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get(2)?)),
        )
        .optional()?;
    Ok(row.and_then(|(incident, kind, sha)| Some((up(incident), action_from(&kind, sha)?))))
}
