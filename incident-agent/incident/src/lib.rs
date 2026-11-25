mod draft;
mod error;
mod flow;
mod proposal;
mod proposal_store;
mod respond;
mod store;
mod types;

use std::path::Path;

use rusqlite::Connection;

pub use error::IncidentError;
pub use proposal::{Approved, Pending, Proposal};
pub use types::{
    Approval, Command, Deploy, DeployScope, Event, Notice, ProposedAction, Settings, Verdict,
};

pub struct Incidents {
    conn: Connection,
    settings: Settings,
}

impl Incidents {
    pub fn open(path: impl AsRef<Path>, settings: Settings) -> Result<Self, IncidentError> {
        Self::init(Connection::open(path)?, settings)
    }

    pub fn open_in_memory(settings: Settings) -> Result<Self, IncidentError> {
        Self::init(Connection::open_in_memory()?, settings)
    }

    fn init(conn: Connection, settings: Settings) -> Result<Self, IncidentError> {
        store::init(&conn)?;
        Ok(Self { conn, settings })
    }

    pub fn handle(&mut self, event: Event, now_unix: u64) -> Result<Vec<Command>, IncidentError> {
        let tx = self.conn.transaction()?;
        store::log(&tx, now_unix, event_kind(&event), "")?;
        let commands = dispatch(&tx, &self.settings, event, now_unix)?;
        tx.commit()?;
        Ok(commands)
    }
}

fn event_kind(event: &Event) -> &'static str {
    match event {
        Event::AlertsReceived(_) => "alerts_received",
        Event::Analyzed { .. } => "analyzed",
        Event::AlertsResolved(_) => "alerts_resolved",
        Event::Tick => "tick",
        Event::VerdictGiven { .. } => "verdict_given",
        Event::ApprovalRequested(_) => "approval_requested",
        Event::ExecutionResult(_) => "execution_result",
    }
}

fn dispatch(
    conn: &Connection,
    settings: &Settings,
    event: Event,
    now: u64,
) -> Result<Vec<Command>, IncidentError> {
    match event {
        Event::AlertsReceived(alerts) => flow::alerts_received(conn, settings, alerts, now),
        Event::Analyzed {
            report,
            last_deploy,
        } => flow::analyzed(conn, report, last_deploy),
        Event::AlertsResolved(services) => {
            store::resolve(conn, &flow::service_names(services.iter()))?;
            Ok(Vec::new())
        }
        Event::Tick => flow::tick(conn, settings, now),
        Event::VerdictGiven {
            incident,
            by,
            verdict,
        } if by == settings.owner => respond::verdict_given(conn, incident, &verdict),
        Event::VerdictGiven { .. } => Ok(Vec::new()),
        Event::ApprovalRequested(approval) => {
            respond::approval_requested(conn, settings, &approval, now)
        }
        Event::ExecutionResult(result) => respond::execution_result(conn, &result),
    }
}
