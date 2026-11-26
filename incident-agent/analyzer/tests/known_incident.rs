mod common;

use analyzer::{Analysis, Evidence, Knowledge, LogLine, Service, SkipReason, triage};
use common::{FixedClock, FixedEvidence, ScriptedModel, alert, knowledge_doc, knowledge_from};

fn evidence_with(text: &str) -> Evidence {
    evidence_from("backend", text)
}

fn evidence_from(service: &str, text: &str) -> Evidence {
    Evidence {
        logs: vec![LogLine {
            service: Service::new(service),
            at_unix: 990,
            text: text.to_owned(),
        }],
        ..Evidence::default()
    }
}

fn knowledge(visitor: &str) -> Knowledge {
    knowledge_from(
        &format!("known-{visitor}"),
        &[(
            "db-pool.md",
            knowledge_doc(
                "ERROR o.h.e.j.s.SqlExceptionHelper : HikariPool# - Connection is not available",
                "org.hibernate.engine.jdbc.spi.SqlExceptionHelper",
                visitor,
                "2026-11-26 정석님",
            ),
        )],
    )
}

#[test]
fn matching_signature_marks_the_incident_as_known_with_its_document() {
    let reports = triage(
        &[alert("backend error logs", "backend", 1_000)],
        &knowledge("불가"),
        &FixedEvidence(evidence_with(
            "ERROR o.h.e.j.s.SqlExceptionHelper : HikariPool-1 - Connection is not available, request timed out after 30000ms",
        )),
        &FixedClock(1_100),
        &ScriptedModel::unknown(),
    );

    assert_eq!(
        reports[0]
            .known_incident
            .as_ref()
            .map(|k| k.source.as_str()),
        Some("db-pool.md")
    );
}

#[test]
fn no_matching_signature_leaves_the_incident_unknown() {
    let reports = triage(
        &[alert("backend error logs", "backend", 1_000)],
        &knowledge("불가"),
        &FixedEvidence(evidence_with(
            "ERROR c.t.TaskService : NullPointerException",
        )),
        &FixedClock(1_100),
        &ScriptedModel::unknown(),
    );

    assert_eq!(reports[0].known_incident, None);
}

#[test]
fn signature_inside_visitor_controlled_text_does_not_mark_the_incident_known() {
    for (service, text) in [
        (
            "nginx",
            r#"{"status":200,"ua":"ERROR o.h.e.j.s.SqlExceptionHelper : HikariPool-1 - Connection is not available"}"#,
        ),
        (
            "backend",
            "ERROR c.t.c.GlobalExceptionHandler : Bad title HikariPool-1 - Connection is not available",
        ),
        (
            "backend",
            "WARN  o.h.e.j.s.SqlExceptionHelper : HikariPool-1 - Connection is not available",
        ),
    ] {
        let reports = triage(
            &[alert("backend error logs", "backend", 1_000)],
            &knowledge("불가"),
            &FixedEvidence(evidence_from(service, text)),
            &FixedClock(1_100),
            &ScriptedModel::unknown(),
        );

        assert_eq!(reports[0].known_incident, None, "{text}");
    }
}

#[test]
fn known_incident_that_visitors_can_trigger_is_attached_but_does_not_skip_analysis() {
    let reports = triage(
        &[alert("backend error logs", "backend", 1_000)],
        &knowledge("가능"),
        &FixedEvidence(evidence_with(
            "ERROR o.h.e.j.s.SqlExceptionHelper : HikariPool-1 - Connection is not available, request timed out after 30000ms",
        )),
        &FixedClock(1_100),
        &ScriptedModel::unknown(),
    );

    let known = reports[0].known_incident.as_ref().expect("known");
    assert!(known.visitor_triggerable);
    assert_ne!(
        reports[0].analysis,
        Analysis::Skipped(SkipReason::KnownIncident)
    );
}
