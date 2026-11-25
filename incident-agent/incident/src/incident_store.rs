use contract::GitSha;
use rusqlite::types::Type;
use rusqlite::{Connection, OptionalExtension, params};

use crate::db::{col, col_opt, db, up};
use crate::draft::DraftData;
use crate::error::Res;
use crate::{Deploy, DeployScope, alerts_store};

pub(crate) struct Opening<'a> {
    pub(crate) now: u64,
    pub(crate) day: u64,
    pub(crate) cause: Option<&'a str>,
    pub(crate) evidence: Option<&'a str>,
    pub(crate) note: &'a str,
    pub(crate) summary: &'a str,
    pub(crate) deploy: Option<&'a Deploy>,
}

pub(crate) struct Stored {
    pub(crate) recurrence_handled: bool,
    pub(crate) deploy: Option<Deploy>,
}

pub(crate) struct Candidate {
    pub(crate) id: u64,
    pub(crate) first_seen_at: u64,
    pub(crate) earliest_start: u64,
}

fn scope_text(scope: DeployScope) -> &'static str {
    match scope {
        DeployScope::Frontend => "frontend",
        DeployScope::Backend => "backend",
        DeployScope::Full => "full",
        DeployScope::Migration => "migration",
    }
}

fn scope_parse(text: &str) -> Option<DeployScope> {
    [
        DeployScope::Frontend,
        DeployScope::Backend,
        DeployScope::Full,
        DeployScope::Migration,
    ]
    .into_iter()
    .find(|scope| scope_text(*scope) == text)
}

fn bad(index: usize, what: &'static str) -> rusqlite::Error {
    rusqlite::Error::FromSqlConversionFailure(index, Type::Text, what.into())
}

fn deploy_from(row: &rusqlite::Row<'_>) -> rusqlite::Result<Option<Deploy>> {
    let Some(sha) = row.get::<_, Option<String>>(1)? else {
        return Ok(None);
    };
    let sha = GitSha::try_from(sha).map_err(|_| bad(1, "deploy_sha"))?;
    let previous =
        GitSha::try_from(row.get::<_, String>(2)?).map_err(|_| bad(2, "deploy_previous_sha"))?;
    let scope = scope_parse(&row.get::<_, String>(3)?).ok_or(bad(3, "deploy_scope"))?;
    Ok(Some(Deploy {
        sha,
        previous_sha: previous,
        scope,
        at_unix: col(row, 4)?,
    }))
}

pub(crate) fn open(conn: &Connection, opening: &Opening<'_>) -> Res<u64> {
    let deploy = opening.deploy;
    conn.execute(
        "INSERT INTO incidents(opened_at, day, grounded, model_cause, model_evidence, \
         analysis_note, summary, deploy_sha, deploy_previous_sha, deploy_scope, deploy_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
        params![
            db(opening.now)?,
            db(opening.day)?,
            opening.cause.is_some(),
            opening.cause,
            opening.evidence,
            opening.note,
            opening.summary,
            deploy.map(|d| d.sha.as_str()),
            deploy.map(|d| d.previous_sha.as_str()),
            deploy.map(|d| scope_text(d.scope)),
            deploy.map(|d| db(d.at_unix)).transpose()?,
        ],
    )?;
    up(conn.last_insert_rowid())
}

pub(crate) fn load(conn: &Connection, id: u64) -> Res<Option<Stored>> {
    Ok(conn
        .query_row(
            "SELECT recurrence_handled, deploy_sha, deploy_previous_sha, deploy_scope, deploy_at \
             FROM incidents WHERE id = ?1",
            [db(id)?],
            |row| {
                Ok(Stored {
                    recurrence_handled: row.get(0)?,
                    deploy: deploy_from(row)?,
                })
            },
        )
        .optional()?)
}

pub(crate) fn mark_restart_executed(conn: &Connection, id: u64, at: u64) -> Res<()> {
    conn.execute(
        "UPDATE incidents SET restart_executed_at = ?2 WHERE id = ?1",
        params![db(id)?, db(at)?],
    )?;
    Ok(())
}

pub(crate) fn mark_recurrence_handled(conn: &Connection, id: u64) -> Res<()> {
    conn.execute(
        "UPDATE incidents SET recurrence_handled = 1 WHERE id = ?1",
        [db(id)?],
    )?;
    Ok(())
}

pub(crate) fn restart_candidates(conn: &Connection) -> Res<Vec<Candidate>> {
    let mut statement = conn.prepare(
        "SELECT i.id, MIN(a.received_at), MIN(a.starts_at) FROM incidents i \
         JOIN alerts a ON a.incident = i.id WHERE i.grounded = 1 GROUP BY i.id \
         HAVING SUM(a.resolved = 0) > 0 \
         AND SUM(a.service IN ('backend', 'postgres', 'redis', 'nginx')) > 0 ORDER BY i.id",
    )?;
    let rows = statement.query_map([], |row| {
        Ok(Candidate {
            id: col(row, 0)?,
            first_seen_at: col(row, 1)?,
            earliest_start: col_opt(row, 2)?.unwrap_or(0),
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub(crate) fn draft_data(conn: &Connection, id: u64) -> Res<Option<DraftData>> {
    let row = conn
        .query_row(
            "SELECT summary, model_cause, model_evidence, analysis_note FROM incidents \
             WHERE id = ?1",
            [db(id)?],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .optional()?;
    let Some((summary, cause, evidence, note)) = row else {
        return Ok(None);
    };
    Ok(Some(DraftData {
        alerts: alerts_store::labels_of(conn, id)?,
        summary,
        cause,
        evidence,
        note,
    }))
}

pub(crate) fn set_verdict(
    conn: &Connection,
    id: u64,
    verdict: &str,
    actual_cause: Option<&str>,
) -> Res<()> {
    conn.execute(
        "UPDATE incidents SET verdict = ?2, actual_cause = ?3 WHERE id = ?1",
        params![db(id)?, verdict, actual_cause],
    )?;
    Ok(())
}
