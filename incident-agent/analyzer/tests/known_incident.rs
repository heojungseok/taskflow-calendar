mod common;

use analyzer::{Evidence, Knowledge, KnownIncident, LogLine, Service, triage};
use common::{FixedClock, FixedEvidence, alert};

fn evidence_with(text: &str) -> Evidence {
    Evidence {
        logs: vec![LogLine {
            service: Service::new("backend"),
            at_unix: 990,
            text: text.to_owned(),
        }],
        ..Evidence::default()
    }
}

fn knowledge() -> Knowledge {
    Knowledge {
        incidents: vec![KnownIncident {
            source: "incidents/db-connection-pool-exhausted.md".to_owned(),
            signature: "Connection is not available".to_owned(),
        }],
    }
}

#[test]
fn matching_signature_marks_the_incident_as_known_with_its_document() {
    let reports = triage(
        &[alert("backend error logs", "backend", 1_000)],
        &knowledge(),
        &FixedEvidence(evidence_with(
            "ERROR o.h.e.j.s.SqlExceptionHelper : HikariPool-1 - Connection is not available, request timed out after 30000ms",
        )),
        &FixedClock(1_100),
    );

    assert_eq!(
        reports[0].known_incident.as_deref(),
        Some("incidents/db-connection-pool-exhausted.md")
    );
}

#[test]
fn no_matching_signature_leaves_the_incident_unknown() {
    let reports = triage(
        &[alert("backend error logs", "backend", 1_000)],
        &knowledge(),
        &FixedEvidence(evidence_with(
            "ERROR c.t.TaskService : NullPointerException",
        )),
        &FixedClock(1_100),
    );

    assert_eq!(reports[0].known_incident, None);
}
