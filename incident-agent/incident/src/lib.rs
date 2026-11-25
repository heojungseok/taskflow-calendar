mod alerts_store;
mod audit;
mod db;
mod draft;
mod error;
mod flow_alerts;
mod flow_tick;
mod incident_store;
mod proposal;
mod proposal_store;
mod respond;
mod types;

use std::path::Path;

use rusqlite::Connection;

pub use error::{ErrorKind, IncidentError};
pub use types::{
    Approval, Command, Deploy, DeployScope, Event, Notice, ProposedAction, Settings, Verdict,
};

use error::{Fail, Res};

pub struct Incidents {
    conn: Connection,
    settings: Settings,
}

impl Incidents {
    pub fn open(path: impl AsRef<Path>, settings: Settings) -> Result<Self, IncidentError> {
        Self::init(Connection::open(path).map_err(Fail::from)?, settings)
    }

    pub fn open_in_memory(settings: Settings) -> Result<Self, IncidentError> {
        Self::init(Connection::open_in_memory().map_err(Fail::from)?, settings)
    }

    fn init(conn: Connection, settings: Settings) -> Result<Self, IncidentError> {
        db::prepare(&conn)?;
        Ok(Self { conn, settings })
    }

    pub fn handle(&mut self, event: Event, now_unix: u64) -> Result<Vec<Command>, IncidentError> {
        let (kind, summary) = audit::describe(&event);
        if !matches!(event, Event::Tick) {
            audit::record(&self.conn, now_unix, kind, &summary)?;
        }
        match self.run(event, now_unix) {
            Ok(commands) => Ok(commands),
            Err(fail) => {
                let failure = IncidentError::from(fail);
                let _ = audit::record(&self.conn, now_unix, "error", &format!("{kind}: {failure}"));
                Err(failure)
            }
        }
    }

    fn run(&mut self, event: Event, now: u64) -> Res<Vec<Command>> {
        let is_tick = matches!(event, Event::Tick);
        let tx = self.conn.transaction()?;
        let commands = dispatch(&tx, &self.settings, event, now)?;
        if is_tick && !commands.is_empty() {
            let note = format!("{} commands", commands.len());
            audit::record(&tx, now, "tick", &note)?;
        }
        tx.commit()?;
        Ok(commands)
    }
}

fn dispatch(conn: &Connection, settings: &Settings, event: Event, now: u64) -> Res<Vec<Command>> {
    match event {
        Event::AlertsReceived(alerts) => flow_alerts::alerts_received(conn, settings, alerts, now),
        Event::Analyzed {
            batch,
            reports,
            last_deploy,
        } => flow_alerts::analyzed(conn, batch, reports, last_deploy, now),
        Event::AlertsResolved(alerts) => flow_alerts::resolved(conn, &alerts),
        Event::Tick => flow_tick::tick(conn, settings, now),
        Event::VerdictGiven {
            incident,
            by,
            verdict,
        } => respond::verdict_given(conn, settings, incident, &by, &verdict, now),
        Event::ApprovalRequested(approval) => {
            respond::approval_requested(conn, settings, &approval, now)
        }
        Event::ExecutionResult(result) => respond::execution_result(conn, &result, now),
    }
}
