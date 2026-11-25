mod common;

use analyzer::{
    Evidence, EvidenceError, EvidenceSource, Knowledge, LogLine, MetricSeries, Missing, Report,
    Service, Suspect, Window, triage,
};
use common::{FixedClock, FixedEvidence, alert};

struct FailingEvidence;

impl EvidenceSource for FailingEvidence {
    fn collect(&self, _window: &Window, _services: &[Service]) -> Result<Evidence, EvidenceError> {
        Err(EvidenceError::Other("prometheus timeout".to_owned()))
    }
}

fn run(source: &impl EvidenceSource) -> Report {
    triage(
        &[alert("backend error logs", "backend", 1_000)],
        &Knowledge::default(),
        source,
        &FixedClock(1_200),
    )
    .remove(0)
}

fn error_line() -> LogLine {
    LogLine {
        service: Service::new("backend"),
        at_unix: 1_010,
        text: "ERROR c.t.p.TaskService : boom".to_owned(),
    }
}

#[test]
fn failed_collection_is_reported_instead_of_looking_clean() {
    let report = run(&FailingEvidence);

    assert_eq!(report.missing, vec![Missing::Evidence]);
    assert!(report.suspects.is_empty());
}

#[test]
fn unreadable_logs_are_reported_and_metrics_are_still_used() {
    let evidence = Evidence {
        metrics: vec![MetricSeries {
            name: "jvm heap".to_owned(),
            baseline: 0.4,
            floor: 0.0,
            points: vec![(1_010, 0.9)],
        }],
        missing: vec![Missing::Logs],
        ..Evidence::default()
    };

    let report = run(&FixedEvidence(evidence));

    assert_eq!(report.missing, vec![Missing::Logs]);
    assert!(matches!(
        report.suspects.as_slice(),
        [Suspect::FirstChangedMetric { .. }]
    ));
}

#[test]
fn without_yesterday_baseline_no_error_is_called_new() {
    let evidence = Evidence {
        logs: vec![error_line()],
        missing: vec![Missing::Baseline],
        ..Evidence::default()
    };

    let report = run(&FixedEvidence(evidence));

    assert_eq!(report.missing, vec![Missing::Baseline]);
    assert!(
        !report
            .suspects
            .iter()
            .any(|s| matches!(s, Suspect::NewError { .. }))
    );
}
