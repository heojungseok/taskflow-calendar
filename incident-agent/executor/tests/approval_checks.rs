mod common;

use std::fs;

use common::{FixedClock, Fixture, NOW, RecordingRunner, code_at, restart, valid_code};
use contract::Outcome;
use executor::execute;

#[test]
fn wrong_totp_code_is_rejected_and_nothing_runs() {
    let fixture = Fixture::new();
    let wrong = if valid_code() == "000000" {
        "111111"
    } else {
        "000000"
    };
    let request = fixture.write_request("req-1", restart(), wrong);
    let runner = RecordingRunner::default();

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-1"), Some(result));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn reused_request_id_is_rejected_and_first_result_is_kept() {
    let fixture = Fixture::new();
    let request = fixture.write_request("req-2", restart(), &valid_code());
    let runner = RecordingRunner::default();
    let first = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    let second = execute(&request, &fixture.setup, &runner, &FixedClock(NOW + 5)).unwrap();

    assert!(matches!(second.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-2"), Some(first));
    assert_eq!(runner.calls.borrow().len(), 1);
}

#[test]
fn expired_request_is_rejected_and_nothing_runs() {
    let fixture = Fixture::new();
    let request = fixture.write_request_at("req-3", restart(), &valid_code(), NOW - 700, NOW - 100);
    let runner = RecordingRunner::default();

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-3"), Some(result));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn after_five_wrong_codes_even_a_valid_code_is_rejected() {
    let fixture = Fixture::new();
    let runner = RecordingRunner::default();
    let wrong = if valid_code() == "000000" {
        "111111"
    } else {
        "000000"
    };
    for index in 0..5 {
        let request = fixture.write_request(&format!("req-20-{index}"), restart(), wrong);
        execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();
    }
    let request = fixture.write_request("req-21", restart(), &valid_code());

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn a_code_accepted_once_is_not_accepted_for_another_request() {
    let fixture = Fixture::new();
    let runner = RecordingRunner::default();
    let first = fixture.write_request("req-22", restart(), &valid_code());
    let second = fixture.write_request("req-23", restart(), &valid_code());
    execute(&first, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    let result = execute(&second, &fixture.setup, &runner, &FixedClock(NOW + 5)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(runner.calls.borrow().len(), 1);
}

#[test]
fn lock_holds_over_time_and_when_the_guard_file_is_missing() {
    let fixture = Fixture::new();
    let runner = RecordingRunner::default();
    fs::write(fixture.path("state/totp-guard"), "5 0").unwrap();
    let later = fixture.write_request("req-24", restart(), &code_at(NOW + 3600));
    let locked = execute(&later, &fixture.setup, &runner, &FixedClock(NOW + 3600)).unwrap();
    fs::remove_file(fixture.path("state/totp-guard")).unwrap();
    let request = fixture.write_request("req-25", restart(), &valid_code());

    let missing = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(locked.outcome(), Outcome::Rejected { .. }));
    assert!(matches!(missing.outcome(), Outcome::Rejected { .. }));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn a_code_from_an_earlier_step_than_the_accepted_one_is_rejected() {
    let fixture = Fixture::new();
    let runner = RecordingRunner::default();
    let first = fixture.write_request("req-26", restart(), &code_at(NOW));
    execute(&first, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();
    let earlier = fixture.write_request("req-27", restart(), &code_at(NOW - 30));

    let result = execute(&earlier, &fixture.setup, &runner, &FixedClock(NOW + 1)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(runner.calls.borrow().len(), 1);
}
