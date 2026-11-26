mod common;

use analyzer::{
    Commit, ErrorGroup, Evidence, Knowledge, LogLine, MetricSeries, Report, Service, Suspect,
    triage,
};
use common::{FixedClock, FixedEvidence, ScriptedModel, alert};

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
        &ScriptedModel::unknown(),
    )
    .remove(0)
}

fn metric(name: &str, baseline: f64, floor: f64, points: Vec<(u64, f64)>) -> MetricSeries {
    MetricSeries {
        name: name.to_owned(),
        baseline,
        floor,
        points,
    }
}

const USUAL: &str = "ERROR c.t.c.GlobalExceptionHandler : Validation error on field title";

fn usual_and_new_errors() -> Evidence {
    let mut logs: Vec<LogLine> = (0..50).map(|i| log(900 + i, USUAL)).collect();
    logs.extend((0..5).map(|i| {
        log(1_010 + i, &format!("ERROR c.t.p.TaskSearchService : NullPointerException for task {} traceId=4f2a9c01d3e5b6a{i}", 100 + i))
    }));
    Evidence {
        logs,
        baseline_logs: (0..40).map(|i| log(i, USUAL)).collect(),
        ..Evidence::default()
    }
}

fn new_error(report: &Report) -> (&ErrorGroup, Option<&ErrorGroup>) {
    match report.suspects.first() {
        Some(Suspect::NewError {
            first,
            most_frequent,
        }) => (first, most_frequent.as_ref()),
        other => panic!("expected new error first, got {other:?}"),
    }
}

#[test]
fn error_that_did_not_exist_yesterday_is_the_first_suspect_even_if_rarer() {
    let report = run(usual_and_new_errors());

    let (first, most_frequent) = new_error(&report);
    assert_eq!(
        first.fingerprint,
        "ERROR c.t.p.TaskSearchService : NullPointerException for task #"
    );
    assert_eq!((first.first_seen_unix, first.count), (1_010, 5));
    assert_eq!(most_frequent, None);
}

#[test]
fn new_error_shows_both_the_earliest_and_the_most_frequent_when_they_differ() {
    let mut evidence = usual_and_new_errors();
    evidence
        .logs
        .push(log(1_001, "ERROR c.t.i.OAuthTokenCipher : decrypt failed"));

    let report = run(evidence);

    let (first, most_frequent) = new_error(&report);
    assert_eq!(
        (first.fingerprint.as_str(), first.count),
        ("ERROR c.t.i.OAuthTokenCipher : decrypt failed", 1)
    );
    assert_eq!(most_frequent.map(|g| g.count), Some(5));
}

#[test]
fn same_error_with_different_ids_and_stacks_is_one_error_and_not_new_if_seen_yesterday() {
    let sync = |id: &str, at: u64| {
        log(
            at,
            &format!(
                "ERROR c.t.c.CalendarOutboxWorker : sync failed for event {id} | com.google.api.client.http.HttpResponseException: 503 | at a.B.c(B.java:{at})"
            ),
        )
    };
    let lambda = |addr: &str, at: u64| {
        log(
            at,
            &format!(
                "ERROR c.t.c.CalendarOutboxWorker : listener com.taskflow.X$$Lambda/{addr} failed"
            ),
        )
    };
    let evidence = Evidence {
        logs: vec![
            sync("3f2c9a10-aaaa-4bbb-8ccc-1234567890ab", 1_010),
            sync("9e1d0b22-ffff-4ddd-8eee-0987654321cd", 1_020),
            lambda("0x00007f3c1a2b3c40", 1_030),
            lambda("0x00007f3c99887760", 1_040),
        ],
        baseline_logs: vec![sync("11112222-3333-4444-5555-666677778888", 10)],
        ..Evidence::default()
    };

    let report = run(evidence);

    let (first, _) = new_error(&report);
    assert!(
        first.fingerprint.contains("Lambda"),
        "{}",
        first.fingerprint
    );
    assert_eq!(first.count, 2);
}

#[test]
fn quoted_visitor_input_is_not_kept_in_the_fingerprint() {
    let evidence = Evidence {
        logs: vec![log(
            1_010,
            r#"ERROR c.t.c.GlobalExceptionHandler : Bad title "visitor@gmail.com secret plan""#,
        )],
        ..Evidence::default()
    };

    let report = run(evidence);

    let (first, _) = new_error(&report);
    assert!(
        !first.fingerprint.contains("visitor"),
        "{}",
        first.fingerprint
    );
}

#[test]
fn error_lines_with_a_syslog_tag_or_leading_spaces_are_still_read() {
    for text in [
        "backend: ERROR c.t.p.TaskService : boom",
        "  ERROR c.t.p.TaskService : boom",
    ] {
        let report = run(Evidence {
            logs: vec![log(1_010, text)],
            ..Evidence::default()
        });

        assert_eq!(
            new_error(&report).0.fingerprint,
            "ERROR c.t.p.TaskService : boom",
            "{text}"
        );
    }
}

#[test]
fn metric_that_changed_first_is_the_metric_suspect() {
    let evidence = Evidence {
        metrics: vec![
            metric(
                "http 5xx ratio",
                0.01,
                0.0,
                vec![(1_000, 0.01), (1_070, 0.2)],
            ),
            metric(
                "search latency p95",
                0.1,
                0.0,
                vec![(1_000, 0.1), (1_010, 0.9)],
            ),
            metric("jvm heap", 0.5, 0.0, vec![(1_000, 0.52), (1_070, 0.55)]),
        ],
        ..Evidence::default()
    };

    let report = run(evidence);

    assert!(report.suspects.contains(&Suspect::FirstChangedMetric {
        name: "search latency p95".to_owned(),
        changed_at_unix: 1_010,
    }));
}

#[test]
fn metric_that_is_usually_zero_changes_only_when_it_reaches_its_floor() {
    let evidence = Evidence {
        metrics: vec![
            metric("outbox backlog", 0.0, 5.0, vec![(1_000, 0.0), (1_005, 3.0)]),
            metric(
                "http 5xx count",
                0.0,
                5.0,
                vec![(1_000, 0.0), (1_020, 50.0)],
            ),
        ],
        ..Evidence::default()
    };

    let report = run(evidence);

    assert!(report.suspects.contains(&Suspect::FirstChangedMetric {
        name: "http 5xx count".to_owned(),
        changed_at_unix: 1_020,
    }));
}

#[test]
fn commit_touching_the_class_of_the_new_error_is_the_commit_suspect() {
    let evidence = Evidence {
        commits: vec![
            Commit {
                sha: "aaaaaaa".to_owned(),
                files: vec!["README.md".to_owned()],
            },
            Commit {
                sha: "bbbbbbb".to_owned(),
                files: vec![
                    "src/main/java/com/taskflow/planning/TaskSearchService.java".to_owned(),
                ],
            },
        ],
        ..usual_and_new_errors()
    };

    let report = run(evidence);

    assert!(report.suspects.contains(&Suspect::RelatedCommit {
        sha: "bbbbbbb".to_owned()
    }));
    assert!(!report.suspects.contains(&Suspect::RelatedCommit {
        sha: "aaaaaaa".to_owned()
    }));
}

#[test]
fn commit_is_found_even_when_the_class_name_contains_digits() {
    let evidence = Evidence {
        logs: vec![log(
            1_010,
            "ERROR c.t.a.Gemini429Classifier : unexpected body",
        )],
        commits: vec![Commit {
            sha: "ccccccc".to_owned(),
            files: vec!["src/main/java/com/taskflow/assistant/Gemini429Classifier.java".to_owned()],
        }],
        ..Evidence::default()
    };

    let report = run(evidence);

    assert!(report.suspects.contains(&Suspect::RelatedCommit {
        sha: "ccccccc".to_owned()
    }));
}

#[test]
fn at_most_three_suspects_one_of_each_kind() {
    let mut evidence = usual_and_new_errors();
    evidence.logs.push(log(
        1_020,
        "ERROR c.t.i.OAuthController : IllegalStateException state mismatch",
    ));
    evidence.metrics = vec![
        metric("a", 1.0, 0.0, vec![(1_010, 5.0)]),
        metric("b", 1.0, 0.0, vec![(1_020, 5.0)]),
    ];
    evidence.commits = vec![
        Commit {
            sha: "c1".to_owned(),
            files: vec!["x/TaskSearchService.java".to_owned()],
        },
        Commit {
            sha: "c2".to_owned(),
            files: vec!["x/OAuthController.java".to_owned()],
        },
    ];

    let report = run(evidence);

    assert_eq!(report.suspects.len(), 3);
    assert!(matches!(report.suspects[0], Suspect::NewError { .. }));
    assert!(matches!(
        report.suspects[1],
        Suspect::FirstChangedMetric { .. }
    ));
    assert!(matches!(report.suspects[2], Suspect::RelatedCommit { .. }));
}
