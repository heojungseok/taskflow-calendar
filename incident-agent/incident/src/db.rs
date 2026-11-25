use std::time::Duration;

use rusqlite::Connection;

use crate::error::{Fail, Res};

const SCHEMA_VERSION: i64 = 1;
const KST_OFFSET_SECS: u64 = 32_400;
const SECS_PER_DAY: u64 = 86_400;

const SCHEMA: &str = "
BEGIN;
CREATE TABLE incidents(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    opened_at INTEGER NOT NULL,
    day INTEGER NOT NULL,
    grounded INTEGER NOT NULL,
    model_cause TEXT,
    model_evidence TEXT,
    analysis_note TEXT NOT NULL,
    summary TEXT NOT NULL,
    deploy_sha TEXT,
    deploy_previous_sha TEXT,
    deploy_scope TEXT,
    deploy_at INTEGER,
    restart_executed_at INTEGER,
    recurrence_handled INTEGER NOT NULL DEFAULT 0,
    verdict TEXT,
    actual_cause TEXT
);
CREATE TABLE batches(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    day INTEGER NOT NULL,
    consumed INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE alerts(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    service TEXT NOT NULL,
    starts_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    incident INTEGER REFERENCES incidents(id),
    batch INTEGER REFERENCES batches(id),
    resolved INTEGER NOT NULL DEFAULT 0,
    limited INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX alerts_identity ON alerts(name, service, resolved);
CREATE INDEX alerts_incident ON alerts(incident);
CREATE TABLE proposals(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    incident INTEGER NOT NULL REFERENCES incidents(id),
    kind TEXT NOT NULL,
    target_sha TEXT,
    issued_at INTEGER NOT NULL,
    button_deadline INTEGER NOT NULL,
    request_deadline INTEGER,
    state TEXT NOT NULL DEFAULT 'open',
    reoffers INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    outcome TEXT
);
CREATE TABLE requests(
    request_id TEXT PRIMARY KEY,
    proposal INTEGER NOT NULL REFERENCES proposals(id),
    outcome TEXT
);
CREATE TABLE events(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at_unix INTEGER NOT NULL,
    kind TEXT NOT NULL,
    summary TEXT NOT NULL
);
PRAGMA user_version = 1;
COMMIT;";

pub(crate) fn prepare(conn: &Connection) -> Res<()> {
    conn.busy_timeout(Duration::from_secs(5))?;
    conn.pragma_update(None, "foreign_keys", true)?;
    let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    match version {
        0 => Ok(conn.execute_batch(SCHEMA)?),
        SCHEMA_VERSION => Ok(()),
        other => Err(Fail::Schema(other)),
    }
}

pub(crate) fn db(value: u64) -> Res<i64> {
    i64::try_from(value).map_err(|_| Fail::Range("unsigned value"))
}

pub(crate) fn up(value: i64) -> Res<u64> {
    u64::try_from(value).map_err(|_| Fail::Range("stored value"))
}

pub(crate) fn kst_day(now_unix: u64) -> Res<u64> {
    let shifted = now_unix
        .checked_add(KST_OFFSET_SECS)
        .ok_or(Fail::Range("timestamp"))?;
    Ok(shifted / SECS_PER_DAY)
}

pub(crate) fn plus(base: u64, secs: u64) -> Res<u64> {
    base.checked_add(secs).ok_or(Fail::Range("timestamp"))
}

pub(crate) fn col(row: &rusqlite::Row<'_>, index: usize) -> rusqlite::Result<u64> {
    let value: i64 = row.get(index)?;
    u64::try_from(value).map_err(|_| rusqlite::Error::IntegralValueOutOfRange(index, value))
}

pub(crate) fn col_opt(row: &rusqlite::Row<'_>, index: usize) -> rusqlite::Result<Option<u64>> {
    let value: Option<i64> = row.get(index)?;
    value
        .map(|v| u64::try_from(v).map_err(|_| rusqlite::Error::IntegralValueOutOfRange(index, v)))
        .transpose()
}
