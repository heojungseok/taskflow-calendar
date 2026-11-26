mod common;

use common::{
    CURRENT_SHA, FixedClock, Fixture, NOW, OTHER_SHA, PREVIOUS_SHA, RecordingRunner, code_at,
    restart, rollback, sha, valid_code,
};
use contract::Outcome;
use executor::{Command, execute};
use serde_json::json;

type JournalRow<'a> = (&'a str, &'a str, &'a str);

#[test]
fn action_outside_allow_list_is_rejected_under_the_file_name_id() {
    let fixture = Fixture::new();
    let request = fixture.write_request("req-4", json!({ "kind": "drop_database" }), &valid_code());
    let runner = RecordingRunner::default();

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert_eq!(result.request_id().as_str(), "req-4");
    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-4"), Some(result));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn backend_rollback_to_the_previous_backend_image_runs_once() {
    let journals: [&[JournalRow]; 2] = [
        &[
            ("success", "backend", PREVIOUS_SHA),
            ("deployment-running", "backend", CURRENT_SHA),
            ("observing", "backend", CURRENT_SHA),
            ("success", "backend", CURRENT_SHA),
        ],
        &[
            ("success", "full", PREVIOUS_SHA),
            ("success", "frontend", OTHER_SHA),
            ("no-change", "none", OTHER_SHA),
            ("deployment-running", "backend", CURRENT_SHA),
            ("observing", "backend", CURRENT_SHA),
            ("success", "backend", CURRENT_SHA),
            ("no-change", "none", OTHER_SHA),
        ],
    ];
    for (index, rows) in journals.into_iter().enumerate() {
        let fixture = Fixture::new();
        fixture.write_journal(rows);
        let id = format!("req-5-{index}");
        let request = fixture.write_request(&id, rollback(PREVIOUS_SHA), &valid_code());
        let runner = RecordingRunner::default();

        let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

        assert_eq!(result.outcome(), &Outcome::Executed {}, "case {index}");
        let calls = runner.calls.borrow();
        assert!(
            matches!(
                calls.as_slice(),
                [Command::RollbackBackend { observed, target, .. }]
                    if *observed == sha(CURRENT_SHA) && *target == sha(PREVIOUS_SHA)
            ),
            "case {index}"
        );
    }
}

fn observed_backend_deploy() -> Vec<JournalRow<'static>> {
    vec![
        ("deployment-running", "backend", CURRENT_SHA),
        ("observing", "backend", CURRENT_SHA),
        ("success", "backend", CURRENT_SHA),
    ]
}

fn with_observed_current(before: &[JournalRow<'static>]) -> Vec<JournalRow<'static>> {
    [before, &observed_backend_deploy()].concat()
}

fn rejected_rollback_cases() -> Vec<(Vec<JournalRow<'static>>, &'static str)> {
    let previous = ("success", "backend", PREVIOUS_SHA);
    vec![
        (with_observed_current(&[previous]), OTHER_SHA),
        (
            vec![
                previous,
                ("approval-running", "backend", CURRENT_SHA),
                ("success", "backend", CURRENT_SHA),
            ],
            PREVIOUS_SHA,
        ),
        (
            vec![previous, ("success", "frontend", CURRENT_SHA)],
            PREVIOUS_SHA,
        ),
        (
            with_observed_current(&[
                previous,
                ("approval-failed", "full", OTHER_SHA),
                ("reconciled", "full", OTHER_SHA),
            ]),
            PREVIOUS_SHA,
        ),
        (
            with_observed_current(&[previous, ("success", "backend", OTHER_SHA)]),
            PREVIOUS_SHA,
        ),
        (
            with_observed_current(&[previous, ("success", "full", OTHER_SHA)]),
            PREVIOUS_SHA,
        ),
        (
            [
                with_observed_current(&[previous]),
                vec![("rolled-back", "backend", CURRENT_SHA)],
            ]
            .concat(),
            PREVIOUS_SHA,
        ),
        (
            vec![
                previous,
                ("deployment-running", "backend", CURRENT_SHA),
                ("observing", "backend", CURRENT_SHA),
            ],
            PREVIOUS_SHA,
        ),
        (observed_backend_deploy(), PREVIOUS_SHA),
    ]
}

#[test]
fn rollback_is_rejected_unless_the_latest_deploy_is_an_observed_backend_image_deploy() {
    for (index, (rows, target)) in rejected_rollback_cases().into_iter().enumerate() {
        let fixture = Fixture::new();
        fixture.write_journal(&rows);
        let id = format!("req-6-{index}");
        let request = fixture.write_request(&id, rollback(target), &valid_code());
        let runner = RecordingRunner::default();

        let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

        assert!(
            matches!(result.outcome(), Outcome::Rejected { .. }),
            "case {index}"
        );
        assert!(runner.calls.borrow().is_empty(), "case {index}");
    }
}

#[test]
fn restart_is_rejected_while_a_deploy_is_in_progress() {
    let previous = ("success", "backend", PREVIOUS_SHA);
    let journals = [
        vec![previous, ("approval-running", "full", CURRENT_SHA)],
        vec![previous, ("deployment-running", "backend", CURRENT_SHA)],
        vec![
            previous,
            ("deployment-running", "backend", CURRENT_SHA),
            ("observing", "backend", CURRENT_SHA),
        ],
    ];
    for (index, rows) in journals.into_iter().enumerate() {
        let fixture = Fixture::new();
        fixture.write_journal(&rows);
        let request = fixture.write_request(&format!("req-30-{index}"), restart(), &valid_code());
        let runner = RecordingRunner::default();

        let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

        assert!(
            matches!(result.outcome(), Outcome::Rejected { .. }),
            "case {index}"
        );
        assert!(runner.calls.borrow().is_empty(), "case {index}");
    }
}

#[test]
fn an_image_this_executor_rolled_back_from_is_never_a_rollback_target_again() {
    let fixture = Fixture::new();
    let runner = RecordingRunner::default();
    let first = fixture.write_request("req-31", rollback(PREVIOUS_SHA), &code_at(NOW));
    execute(&first, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();
    fixture.write_journal(&[
        ("success", "backend", PREVIOUS_SHA),
        ("deployment-running", "backend", CURRENT_SHA),
        ("observing", "backend", CURRENT_SHA),
        ("success", "backend", CURRENT_SHA),
        ("deployment-running", "backend", OTHER_SHA),
        ("observing", "backend", OTHER_SHA),
        ("success", "backend", OTHER_SHA),
    ]);
    let second = fixture.write_request("req-32", rollback(CURRENT_SHA), &code_at(NOW + 30));

    let result = execute(&second, &fixture.setup, &runner, &FixedClock(NOW + 30)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(runner.calls.borrow().len(), 1);
}

#[test]
fn failed_command_is_recorded_without_retry() {
    let fixture = Fixture::new();
    let request = fixture.write_request("req-7", restart(), &valid_code());
    let runner = RecordingRunner {
        failure: Some("docker restart exited 1".to_owned()),
        ..RecordingRunner::default()
    };

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    let failed = Outcome::Failed {
        reason: "docker restart exited 1".to_owned(),
    };
    assert_eq!(result.outcome(), &failed);
    assert_eq!(fixture.result_file("req-7"), Some(result));
    assert!(matches!(
        runner.calls.borrow().as_slice(),
        [Command::RestartBackend { .. }]
    ));
}

#[test]
fn analyzer_deploys_do_not_block_backend_rollback() {
    let fixture = Fixture::new();
    fixture.write_journal(&[
        ("success", "backend", PREVIOUS_SHA),
        ("deployment-running", "agent", OTHER_SHA),
        ("success", "agent", OTHER_SHA),
        ("deployment-running", "backend", CURRENT_SHA),
        ("observing", "backend", CURRENT_SHA),
        ("success", "backend", CURRENT_SHA),
        ("deployment-running", "agent", OTHER_SHA),
        ("deployment-failed", "agent", OTHER_SHA),
    ]);
    let request = fixture.write_request("req-40", rollback(PREVIOUS_SHA), &valid_code());
    let runner = RecordingRunner::default();

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert_eq!(result.outcome(), &Outcome::Executed {});
    assert!(matches!(
        runner.calls.borrow().as_slice(),
        [Command::RollbackBackend { .. }]
    ));
}

#[test]
fn analyzer_deploys_in_progress_or_failed_do_not_block_backend_restart() {
    let tails: [JournalRow; 2] = [
        ("deployment-running", "agent", OTHER_SHA),
        ("deployment-failed", "agent", OTHER_SHA),
    ];
    for (index, tail) in tails.into_iter().enumerate() {
        let fixture = Fixture::new();
        fixture.write_journal(&[("success", "backend", CURRENT_SHA), tail]);
        let request = fixture.write_request(&format!("req-41-{index}"), restart(), &valid_code());
        let runner = RecordingRunner::default();

        let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

        assert_eq!(result.outcome(), &Outcome::Executed {}, "case {index}");
    }
}
