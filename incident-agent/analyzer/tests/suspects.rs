mod common;

use analyzer::{Evidence, Knowledge, LogLine, MetricSeries, Report, Service, Suspect, triage};
use common::{FixedClock, FixedEvidence, alert};

fn log(at_unix: u64, text: &str) -> LogLine {
    LogLine {
        service: Service::new("backend"),
        at_unix,
        text: text.to_owned(),
    }
}

fn run(evidence: Evidence) -> Report {
    triage(
        &[alert("backend error logs", "backend", 1_000)],
        &Knowledge::default(),
        &FixedEvidence(evidence),
        &FixedClock(1_200),
    )
    .remove(0)
}

fn usual_and_new_errors() -> Evidence {
    let usual = "ERROR c.t.c.GlobalExceptionHandler : Validation error on field title";
    let mut logs: Vec<LogLine> = (0..50).map(|i| log(900 + i, usual)).collect();
    logs.extend((0..5).map(|i| {
        log(1_010 + i, &format!("ERROR c.t.p.TaskSearchService : NullPointerException for task {} traceId=4f2a9c01d3e5b6a{i}", 100 + i))
    }));
    Evidence {
        logs,
        baseline_logs: (0..40).map(|i| log(i, usual)).collect(),
        ..Evidence::default()
    }
}

#[test]
fn error_that_did_not_exist_yesterday_is_the_first_suspect_even_if_rarer() {
    let report = run(usual_and_new_errors());

    match &report.suspects[0] {
        Suspect::NewError {
            fingerprint,
            first_seen_unix,
            count,
        } => {
            assert!(fingerprint.contains("TaskSearchService : NullPointerException"));
            assert_eq!((*first_seen_unix, *count), (1_010, 5));
        }
        other => panic!("expected new error, got {other:?}"),
    }
}

#[test]
fn metric_that_changed_first_is_the_metric_suspect() {
    let evidence = Evidence {
        metrics: vec![
            MetricSeries {
                name: "http 5xx ratio".to_owned(),
                baseline: 0.01,
                points: vec![(1_000, 0.01), (1_070, 0.2)],
            },
            MetricSeries {
                name: "search latency p95".to_owned(),
                baseline: 0.1,
                points: vec![(1_000, 0.1), (1_010, 0.9)],
            },
            MetricSeries {
                name: "jvm heap".to_owned(),
                baseline: 0.5,
                points: vec![(1_000, 0.52), (1_070, 0.55)],
            },
        ],
        ..Evidence::default()
    };

    let report = run(evidence);

    assert!(report.suspects.contains(&Suspect::FirstChangedMetric {
        name: "search latency p95".to_owned(),
        changed_at_unix: 1_010,
    }));
}
