mod common;

use common::*;
use contract::{Action, Outcome};
use incident::{Command, DeployScope, Event, Incidents, Notice, ProposedAction};

fn proposed(incidents: &mut Incidents) -> (u64, u64) {
    let incident = open_backend(incidents, true, None);
    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();
    let (_, _, issued) = proposals(&tick).remove(0);
    (incident, issued)
}

fn approve(
    incidents: &mut Incidents,
    incident: u64,
    by: &str,
    issued: u64,
    now: u64,
) -> Vec<Command> {
    incidents
        .handle(
            approval(incident, ProposedAction::RestartBackend, by, CODE, issued),
            now,
        )
        .unwrap()
}

#[test]
fn approval_from_someone_else_writes_no_request() {
    let mut incidents = incidents();
    let (incident, issued) = proposed(&mut incidents);

    assert!(
        written(&approve(
            &mut incidents,
            incident,
            "999",
            issued,
            issued + 10
        ))
        .is_empty()
    );
}

#[test]
fn button_is_valid_for_ten_minutes() {
    let mut on_time = incidents();
    let (incident, issued) = proposed(&mut on_time);
    let mut late = incidents();
    proposed(&mut late);

    assert_eq!(
        written(&approve(
            &mut on_time,
            incident,
            OWNER,
            issued,
            issued + 600
        ))
        .len(),
        1
    );
    assert!(written(&approve(&mut late, incident, OWNER, issued, issued + 601)).is_empty());
}

#[test]
fn the_same_approval_writes_a_request_only_once() {
    let mut incidents = incidents();
    let (incident, issued) = proposed(&mut incidents);

    let first = approve(&mut incidents, incident, OWNER, issued, issued + 10);
    let second = approve(&mut incidents, incident, OWNER, issued, issued + 15);

    assert_eq!((written(&first).len(), written(&second).len()), (1, 0));
}

#[test]
fn approvals_for_unproposed_actions_forged_buttons_or_resolved_incidents_write_nothing() {
    let mut never_proposed = incidents();
    let incident = open_backend(&mut never_proposed, true, None);
    assert!(
        written(&approve(
            &mut never_proposed,
            incident,
            OWNER,
            T0 + 100,
            T0 + 110
        ))
        .is_empty()
    );

    let mut forged = incidents();
    let (incident, issued) = proposed(&mut forged);
    assert!(
        written(&approve(
            &mut forged,
            incident,
            OWNER,
            issued + 1,
            issued + 10
        ))
        .is_empty()
    );

    let mut resolved = incidents();
    let (incident, issued) = proposed(&mut resolved);
    resolved
        .handle(
            Event::AlertsResolved(vec![alert("backend", T0)]),
            issued + 5,
        )
        .unwrap();
    assert!(
        written(&approve(
            &mut resolved,
            incident,
            OWNER,
            issued,
            issued + 10
        ))
        .is_empty()
    );
}

#[test]
fn rollback_approval_requests_the_previous_image() {
    let mut incidents = incidents();
    let incident = open_backend(
        &mut incidents,
        true,
        deploy(DeployScope::Backend, T0 - 3_600),
    );
    restart_executed(&mut incidents, incident);
    incidents
        .handle(
            Event::AlertsResolved(vec![alert("backend", T0)]),
            T0 + 1_000,
        )
        .unwrap();
    let recur = receive(
        &mut incidents,
        vec![alert("backend", T0 + 1_100)],
        T0 + 1_100,
    );
    let (_, action, issued) = proposals(&recur).remove(0);

    let commands = incidents
        .handle(approval(incident, action, OWNER, CODE, issued), issued + 10)
        .unwrap();

    let request = written(&commands).remove(0);
    assert!(
        matches!(request.action(), Action::Rollback { target_sha } if target_sha.as_str() == PREVIOUS)
    );
}

#[test]
fn rejected_request_can_be_approved_again_within_the_button_window_with_a_new_request_id() {
    let mut incidents = incidents();
    let (incident, issued) = proposed(&mut incidents);
    let first = written(&approve(
        &mut incidents,
        incident,
        OWNER,
        issued,
        issued + 10,
    ))
    .remove(0);
    finish(
        &mut incidents,
        &first,
        Outcome::Rejected {
            reason: "invalid totp code".to_owned(),
        },
        issued + 20,
    );

    let retry = written(&approve(
        &mut incidents,
        incident,
        OWNER,
        issued,
        issued + 30,
    ));

    assert_eq!(retry.len(), 1);
    assert_ne!(retry[0].request_id(), first.request_id());
}

#[test]
fn expired_proposal_is_offered_once_more_and_then_handed_to_a_human() {
    let mut incidents = incidents();
    let (_, issued) = proposed(&mut incidents);

    let reoffer = incidents.handle(Event::Tick, issued + 660).unwrap();
    let (_, _, reissued) = proposals(&reoffer).remove(0);
    let last = incidents.handle(Event::Tick, reissued + 660).unwrap();

    assert!(reissued > issued);
    assert!(proposals(&last).is_empty());
    assert!(
        last.iter()
            .any(|c| matches!(c, Command::Post(Notice::ManualOnly { .. })))
    );
}

#[test]
fn failed_restart_is_not_retried_and_is_handed_to_a_human() {
    let mut incidents = incidents();
    let (incident, issued) = proposed(&mut incidents);
    let request = written(&approve(
        &mut incidents,
        incident,
        OWNER,
        issued,
        issued + 10,
    ))
    .remove(0);

    let result = finish(
        &mut incidents,
        &request,
        Outcome::Failed {
            reason: "docker exited 1".to_owned(),
        },
        issued + 20,
    );
    let retry = approve(&mut incidents, incident, OWNER, issued, issued + 30);

    assert!(
        result
            .iter()
            .any(|c| matches!(c, Command::Post(Notice::ManualOnly { .. })))
    );
    assert!(written(&retry).is_empty());
}
