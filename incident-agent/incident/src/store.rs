use analyzer::Alert;
use contract::GitSha;
use rusqlite::types::Type;
use rusqlite::{Connection, OptionalExtension, params};

use crate::{Deploy, DeployScope};

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS incidents(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    opened_at INTEGER NOT NULL,
    day INTEGER NOT NULL,
    started_at INTEGER NOT NULL,
    alert_names TEXT NOT NULL,
    resolved INTEGER NOT NULL DEFAULT 0,
    limited INTEGER NOT NULL DEFAULT 0,
    analyzed INTEGER NOT NULL DEFAULT 0,
    grounded INTEGER NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT '',
    deploy_sha TEXT,
    deploy_previous_sha TEXT,
    deploy_scope TEXT,
    deploy_at INTEGER,
    restart_executed INTEGER NOT NULL DEFAULT 0,
    escalated INTEGER NOT NULL DEFAULT 0,
    verdict TEXT,
    actual_cause TEXT
);
CREATE TABLE IF NOT EXISTS incident_services(
    incident INTEGER NOT NULL REFERENCES incidents(id),
    service TEXT NOT NULL,
    PRIMARY KEY(incident, service)
);
CREATE TABLE IF NOT EXISTS events(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at_unix INTEGER NOT NULL,
    kind TEXT NOT NULL,
    summary TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS proposals(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    incident INTEGER NOT NULL REFERENCES incidents(id),
    kind TEXT NOT NULL,
    target_sha TEXT,
    issued_at INTEGER NOT NULL,
    approved INTEGER NOT NULL DEFAULT 0,
    request_id TEXT
);";

const COLUMNS: &str = "id, started_at, restart_executed, escalated, deploy_sha, \
                       deploy_previous_sha, deploy_scope, deploy_at";

pub(crate) struct Stored {
    pub(crate) id: u64,
    pub(crate) started_at: u64,
    pub(crate) restart_executed: bool,
    pub(crate) escalated: bool,
    pub(crate) deploy: Option<Deploy>,
}

pub(crate) fn db(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

pub(crate) fn up(value: i64) -> u64 {
    u64::try_from(value).unwrap_or(0)
}

pub(crate) fn init(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(SCHEMA)
}

pub(crate) fn log(conn: &Connection, now: u64, kind: &str, summary: &str) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO events(at_unix, kind, summary) VALUES (?1, ?2, ?3)",
        params![db(now), kind, summary],
    )?;
    Ok(())
}

fn conversion(index: usize, what: &'static str) -> rusqlite::Error {
    rusqlite::Error::FromSqlConversionFailure(index, Type::Text, what.into())
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

fn deploy_from(row: &rusqlite::Row<'_>) -> rusqlite::Result<Option<Deploy>> {
    let Some(sha) = row.get::<_, Option<String>>(4)? else {
        return Ok(None);
    };
    let sha = GitSha::try_from(sha).map_err(|_| conversion(4, "deploy_sha"))?;
    let previous = GitSha::try_from(row.get::<_, String>(5)?)
        .map_err(|_| conversion(5, "deploy_previous_sha"))?;
    let scope = scope_parse(&row.get::<_, String>(6)?).ok_or(conversion(6, "deploy_scope"))?;
    Ok(Some(Deploy {
        sha,
        previous_sha: previous,
        scope,
        at_unix: up(row.get(7)?),
    }))
}

fn map_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Stored> {
    Ok(Stored {
        id: up(row.get(0)?),
        started_at: up(row.get(1)?),
        restart_executed: row.get(2)?,
        escalated: row.get(3)?,
        deploy: deploy_from(row)?,
    })
}

fn latest(
    conn: &Connection,
    services: &[String],
    condition: &'static str,
) -> rusqlite::Result<Option<Stored>> {
    let sql = format!(
        "SELECT {COLUMNS} FROM incidents WHERE {condition} AND id IN \
         (SELECT incident FROM incident_services WHERE service = ?1) ORDER BY id DESC LIMIT 1"
    );
    let mut best: Option<Stored> = None;
    for service in services {
        let found = conn.query_row(&sql, [service], map_row).optional()?;
        if let Some(found) = found
            && best.as_ref().is_none_or(|b| found.id > b.id)
        {
            best = Some(found);
        }
    }
    Ok(best)
}

pub(crate) fn ongoing(conn: &Connection, services: &[String]) -> rusqlite::Result<Option<Stored>> {
    latest(conn, services, "resolved = 0")
}

pub(crate) fn awaiting_analysis(
    conn: &Connection,
    services: &[String],
) -> rusqlite::Result<Option<Stored>> {
    latest(conn, services, "analyzed = 0 AND limited = 0")
}

pub(crate) fn opened_on_day(conn: &Connection, day: u64) -> rusqlite::Result<u32> {
    conn.query_row(
        "SELECT COUNT(*) FROM incidents WHERE day = ?1",
        [db(day)],
        |r| r.get(0),
    )
}

pub(crate) fn open(
    conn: &Connection,
    now: u64,
    started_at: u64,
    alerts: &[Alert],
    limited: bool,
) -> rusqlite::Result<u64> {
    let names: Vec<&str> = alerts.iter().map(|a| a.name.as_str()).collect();
    conn.execute(
        "INSERT INTO incidents(opened_at, day, started_at, alert_names, limited) \
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            db(now),
            db(now / 86_400),
            db(started_at),
            names.join(", "),
            limited
        ],
    )?;
    let id = up(conn.last_insert_rowid());
    for alert in alerts {
        conn.execute(
            "INSERT OR IGNORE INTO incident_services(incident, service) VALUES (?1, ?2)",
            params![db(id), alert.service.as_str()],
        )?;
    }
    Ok(id)
}

pub(crate) fn attach_report(
    conn: &Connection,
    id: u64,
    grounded: bool,
    summary: &str,
    deploy: Option<&Deploy>,
) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE incidents SET analyzed = 1, grounded = ?2, summary = ?3, deploy_sha = ?4, \
         deploy_previous_sha = ?5, deploy_scope = ?6, deploy_at = ?7 WHERE id = ?1",
        params![
            db(id),
            grounded,
            summary,
            deploy.map(|d| d.sha.as_str()),
            deploy.map(|d| d.previous_sha.as_str()),
            deploy.map(|d| scope_text(d.scope)),
            deploy.map(|d| db(d.at_unix)),
        ],
    )?;
    Ok(())
}

pub(crate) fn resolve(conn: &Connection, services: &[String]) -> rusqlite::Result<()> {
    for service in services {
        conn.execute(
            "UPDATE incidents SET resolved = 1 WHERE resolved = 0 AND id IN \
             (SELECT incident FROM incident_services WHERE service = ?1)",
            [service],
        )?;
    }
    Ok(())
}

pub(crate) fn unresolved_grounded(conn: &Connection) -> rusqlite::Result<Vec<Stored>> {
    let sql =
        format!("SELECT {COLUMNS} FROM incidents WHERE resolved = 0 AND grounded = 1 ORDER BY id");
    conn.prepare(&sql)?
        .query_map([], map_row)?
        .collect::<rusqlite::Result<Vec<_>>>()
}

pub(crate) fn set_flag(conn: &Connection, id: u64, flag: Flag) -> rusqlite::Result<()> {
    let sql = match flag {
        Flag::RestartExecuted => "UPDATE incidents SET restart_executed = 1 WHERE id = ?1",
        Flag::Escalated => "UPDATE incidents SET escalated = 1 WHERE id = ?1",
    };
    conn.execute(sql, [db(id)])?;
    Ok(())
}

pub(crate) enum Flag {
    RestartExecuted,
    Escalated,
}

pub(crate) fn draft_source(
    conn: &Connection,
    id: u64,
) -> rusqlite::Result<Option<(String, String)>> {
    conn.query_row(
        "SELECT alert_names, summary FROM incidents WHERE id = ?1",
        [db(id)],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )
    .optional()
}

pub(crate) fn set_verdict(
    conn: &Connection,
    id: u64,
    verdict: &str,
    actual_cause: Option<&str>,
) -> rusqlite::Result<()> {
    conn.execute(
        "UPDATE incidents SET verdict = ?2, actual_cause = ?3 WHERE id = ?1",
        params![db(id), verdict, actual_cause],
    )?;
    Ok(())
}
