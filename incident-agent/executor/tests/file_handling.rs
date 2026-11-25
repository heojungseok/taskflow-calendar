mod common;

use std::fs;
use std::os::unix::fs::symlink;

use common::{FixedClock, Fixture, NOW, RecordingRunner, code_at, restart, valid_code};
use contract::Outcome;
use executor::execute;

#[test]
fn request_whose_id_differs_from_its_file_name_is_rejected_under_the_file_name() {
    let fixture = Fixture::new();
    let written = fixture.write_request("req-9", restart(), &valid_code());
    let renamed = fixture.path("requests/req-8.json");
    fs::rename(&written, &renamed).unwrap();
    let runner = RecordingRunner::default();

    let result = execute(&renamed, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert_eq!(result.request_id().as_str(), "req-8");
    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-8"), Some(result));
    assert_eq!(fixture.result_file("req-9"), None);
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn symlinked_request_is_rejected_without_following_it() {
    let fixture = Fixture::new();
    let real = fixture.write_request("req-10", restart(), &valid_code());
    let outside = fixture.path("outside.json");
    fs::rename(&real, &outside).unwrap();
    symlink(&outside, &real).unwrap();
    let runner = RecordingRunner::default();

    let result = execute(&real, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn oversized_request_is_rejected_without_running() {
    let fixture = Fixture::new();
    let request = fixture.write_request("req-11", restart(), &valid_code());
    let mut body = fs::read(&request).unwrap();
    body.extend(vec![b' '; 70 * 1024]);
    fs::write(&request, body).unwrap();
    let runner = RecordingRunner::default();

    let result = execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).unwrap();

    assert!(matches!(result.outcome(), Outcome::Rejected { .. }));
    assert_eq!(fixture.result_file("req-11"), Some(result));
    assert!(runner.calls.borrow().is_empty());
}

#[test]
fn command_is_not_run_again_when_its_result_could_not_be_written() {
    let fixture = Fixture::new();
    let request = fixture.write_request("req-12", restart(), &valid_code());
    fs::remove_dir(fixture.path("results")).unwrap();
    fs::write(fixture.path("results"), "not a directory").unwrap();
    let runner = RecordingRunner::default();

    assert!(execute(&request, &fixture.setup, &runner, &FixedClock(NOW)).is_err());
    fs::remove_file(fixture.path("results")).unwrap();
    fs::create_dir(fixture.path("results")).unwrap();
    let preserved = fs::read_to_string(fixture.path("state/used/req-12")).unwrap();
    let later = fixture.write_request("req-12", restart(), &code_at(NOW + 30));
    let again = execute(&later, &fixture.setup, &runner, &FixedClock(NOW + 30)).unwrap();

    assert!(preserved.contains("\"executed\""));
    assert!(matches!(again.outcome(), Outcome::Rejected { .. }));
    assert_eq!(runner.calls.borrow().len(), 1);
}
