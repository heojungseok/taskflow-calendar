use analyzer::Alert;
use rusqlite::{Connection, OptionalExtension, params};

use crate::db::{db, up};
use crate::error::Res;

pub(crate) fn tracked_unresolved(conn: &Connection, alert: &Alert) -> Res<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM alerts WHERE name = ?1 AND service = ?2 AND resolved = 0)",
        params![alert.name, alert.service.as_str()],
        |r| r.get(0),
    )?)
}

pub(crate) fn insert(
    conn: &Connection,
    alert: &Alert,
    received_at: u64,
    incident: Option<u64>,
    batch: Option<u64>,
    limited: bool,
) -> Res<()> {
    let incident = incident.map(db).transpose()?;
    let batch = batch.map(db).transpose()?;
    conn.execute(
        "INSERT INTO alerts(name, service, starts_at, received_at, incident, batch, limited) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            alert.name,
            alert.service.as_str(),
            db(alert.starts_at_unix)?,
            db(received_at)?,
            incident,
            batch,
            limited
        ],
    )?;
    Ok(())
}

pub(crate) fn resolve(conn: &Connection, alert: &Alert) -> Res<()> {
    conn.execute(
        "UPDATE alerts SET resolved = 1 WHERE name = ?1 AND service = ?2 AND resolved = 0",
        params![alert.name, alert.service.as_str()],
    )?;
    Ok(())
}

pub(crate) fn in_batch(conn: &Connection, batch: u64, alert: &Alert) -> Res<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM alerts WHERE batch = ?1 AND name = ?2 AND service = ?3 \
         AND incident IS NULL)",
        params![db(batch)?, alert.name, alert.service.as_str()],
        |r| r.get(0),
    )?)
}

pub(crate) fn attach(conn: &Connection, batch: u64, alert: &Alert, incident: u64) -> Res<()> {
    conn.execute(
        "UPDATE alerts SET incident = ?1 WHERE batch = ?2 AND name = ?3 AND service = ?4 \
         AND incident IS NULL",
        params![
            db(incident)?,
            db(batch)?,
            alert.name,
            alert.service.as_str()
        ],
    )?;
    Ok(())
}

pub(crate) fn labels_of(conn: &Connection, incident: u64) -> Res<Vec<String>> {
    let mut statement = conn.prepare(
        "SELECT DISTINCT name || ' (' || service || ')' FROM alerts WHERE incident = ?1 ORDER BY 1",
    )?;
    let rows = statement.query_map([db(incident)?], |r| r.get(0))?;
    Ok(rows.collect::<rusqlite::Result<Vec<String>>>()?)
}

pub(crate) fn recurrence_of(
    conn: &Connection,
    alert: &Alert,
    window_secs: u64,
) -> Res<Option<u64>> {
    let found: Option<i64> = conn
        .query_row(
            "SELECT i.id FROM incidents i JOIN alerts a ON a.incident = i.id \
             WHERE a.name = ?1 AND a.service = ?2 AND i.restart_executed_at IS NOT NULL \
             AND ?3 > i.restart_executed_at AND ?3 - i.restart_executed_at <= ?4 \
             ORDER BY i.id DESC LIMIT 1",
            params![
                alert.name,
                alert.service.as_str(),
                db(alert.starts_at_unix)?,
                db(window_secs)?
            ],
            |r| r.get(0),
        )
        .optional()?;
    found.map(up).transpose()
}

pub(crate) fn new_batch(conn: &Connection, now: u64, day: u64) -> Res<u64> {
    conn.execute(
        "INSERT INTO batches(created_at, day) VALUES (?1, ?2)",
        params![db(now)?, db(day)?],
    )?;
    up(conn.last_insert_rowid())
}

pub(crate) fn consume_batch(conn: &Connection, batch: u64) -> Res<bool> {
    let changed = conn.execute(
        "UPDATE batches SET consumed = 1 WHERE id = ?1 AND consumed = 0",
        [db(batch)?],
    )?;
    Ok(changed == 1)
}

pub(crate) fn opened_on_day(conn: &Connection, day: u64) -> Res<u32> {
    Ok(conn.query_row(
        "SELECT (SELECT COUNT(*) FROM incidents WHERE day = ?1) \
         + (SELECT COUNT(*) FROM batches WHERE day = ?1 AND consumed = 0)",
        [db(day)?],
        |r| r.get(0),
    )?)
}
