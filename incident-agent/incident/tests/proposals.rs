mod common;

use common::*;
use incident::{Command, DeployScope, Event, Notice, ProposedAction};

#[test]
fn restart_is_proposed_once_after_fifteen_unresolved_minutes_with_a_grounded_guess() {
    let mut grounded = incidents();
    open_backend(&mut grounded, true, None);
    let early = grounded.handle(Event::Tick, T0 + 600).unwrap();
    let late = grounded.handle(Event::Tick, T0 + 960).unwrap();
    let again = grounded.handle(Event::Tick, T0 + 1_020).unwrap();

    let mut ungrounded = incidents();
    open_backend(&mut ungrounded, false, None);
    let unknown = ungrounded.handle(Event::Tick, T0 + 960).unwrap();

    assert!(proposals(&early).is_empty());
    assert_eq!(
        proposals(&late)
            .into_iter()
            .map(|p| p.1)
            .collect::<Vec<_>>(),
        vec![ProposedAction::RestartBackend]
    );
    assert!(proposals(&again).is_empty());
    assert!(proposals(&unknown).is_empty());
}

#[test]
fn resolved_incident_gets_no_restart_proposal() {
    let mut incidents = incidents();
    open_backend(&mut incidents, true, None);
    incidents
        .handle(Event::AlertsResolved(vec![alert("backend", T0)]), T0 + 300)
        .unwrap();

    assert!(proposals(&incidents.handle(Event::Tick, T0 + 960).unwrap()).is_empty());
}

#[test]
fn restart_is_proposed_only_for_backend_side_incidents_and_only_once_at_a_time() {
    let mut incidents = incidents();
    let commands = receive(&mut incidents, vec![alert("loki", T0)], T0);
    analyze_all(&mut incidents, &commands, true, None, T0 + 60);
    let commands = receive(
        &mut incidents,
        vec![alert("backend", T0 + 10), alert("postgres", T0 + 10)],
        T0 + 10,
    );
    analyze_all(&mut incidents, &commands, true, None, T0 + 70);

    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();

    assert_eq!(proposals(&tick).len(), 1);
}

#[test]
fn alert_that_started_long_before_it_reached_us_still_waits_fifteen_minutes() {
    let mut incidents = incidents();
    let commands = receive(&mut incidents, vec![alert("backend", 0)], T0);
    analyze_all(&mut incidents, &commands, true, None, T0 + 60);

    assert!(proposals(&incidents.handle(Event::Tick, T0 + 120).unwrap()).is_empty());
}

fn recurs(deploy_scope: Option<DeployScope>, deploy_at: u64, recur_at: u64) -> Vec<Command> {
    let mut incidents = incidents();
    let incident = open_backend(
        &mut incidents,
        true,
        deploy_scope.and_then(|s| deploy(s, deploy_at)),
    );
    let restarted_at = restart_executed(&mut incidents, incident);
    incidents
        .handle(
            Event::AlertsResolved(vec![alert("backend", T0)]),
            restarted_at + 60,
        )
        .unwrap();
    receive(&mut incidents, vec![alert("backend", recur_at)], recur_at)
}

fn rollback_target(commands: &[Command]) -> Option<String> {
    proposals(commands)
        .into_iter()
        .find_map(|(_, action, _)| match action {
            ProposedAction::Rollback { target_sha } => Some(target_sha.as_str().to_owned()),
            ProposedAction::RestartBackend => None,
        })
}

#[test]
fn alert_starting_again_within_thirty_minutes_after_restart_proposes_rollback_after_a_backend_deploy()
 {
    let commands = recurs(Some(DeployScope::Backend), T0 - 3_600, T0 + 990 + 600);

    assert_eq!(rollback_target(&commands).as_deref(), Some(PREVIOUS));
}

#[test]
fn alert_resent_with_its_original_start_right_after_restart_is_not_a_recurrence() {
    let mut incidents = incidents();
    let incident = open_backend(
        &mut incidents,
        true,
        deploy(DeployScope::Backend, T0 - 3_600),
    );
    restart_executed(&mut incidents, incident);

    let resent = receive(&mut incidents, vec![alert("backend", T0)], T0 + 991);

    assert_eq!(rollback_target(&resent), None);
}

#[test]
fn alert_after_the_recurrence_window_is_a_new_incident() {
    let commands = recurs(Some(DeployScope::Backend), T0 - 3_600, T0 + 990 + 1_801);

    assert_eq!(rollback_target(&commands), None);
    assert!(
        commands
            .iter()
            .any(|c| matches!(c, Command::Analyze { .. }))
    );
}

#[test]
fn deploys_the_executor_cannot_roll_back_get_a_manual_notice() {
    for scope in [
        DeployScope::Frontend,
        DeployScope::Full,
        DeployScope::Migration,
    ] {
        let commands = recurs(Some(scope), T0 - 3_600, T0 + 990 + 600);

        assert_eq!(rollback_target(&commands), None, "{scope:?}");
        assert!(
            commands
                .iter()
                .any(|c| matches!(c, Command::Post(Notice::ManualOnly { .. }))),
            "{scope:?}"
        );
    }
}

#[test]
fn recurrence_without_a_recent_deploy_gets_no_rollback() {
    for (scope, at) in [(None, 0), (Some(DeployScope::Backend), T0 - 200_000)] {
        let commands = recurs(scope, at, T0 + 990 + 600);

        assert_eq!(rollback_target(&commands), None);
    }
}

#[test]
fn rollback_is_proposed_only_once_per_incident() {
    let mut incidents = incidents();
    let incident = open_backend(
        &mut incidents,
        true,
        deploy(DeployScope::Backend, T0 - 3_600),
    );
    restart_executed(&mut incidents, incident);
    let first = receive(
        &mut incidents,
        vec![alert("backend", T0 + 1_100)],
        T0 + 1_100,
    );
    incidents
        .handle(
            Event::AlertsResolved(vec![alert("backend", T0 + 1_100)]),
            T0 + 1_150,
        )
        .unwrap();

    let second = receive(
        &mut incidents,
        vec![alert("backend", T0 + 1_200)],
        T0 + 1_200,
    );

    assert!(rollback_target(&first).is_some());
    assert_eq!(rollback_target(&second), None);
}
