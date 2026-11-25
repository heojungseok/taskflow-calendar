mod common;

use common::*;
use incident::{Command, Event, Incidents, Notice, Settings};

fn analyze_commands(commands: &[Command]) -> usize {
    commands
        .iter()
        .filter(|c| matches!(c, Command::Analyze { .. }))
        .count()
}

#[test]
fn new_alerts_are_analyzed_once_and_alerts_already_tracked_are_not_analyzed_again() {
    let mut incidents = incidents();
    let first = receive(&mut incidents, vec![alert("backend", T0)], T0);
    analyze_all(&mut incidents, &first, true, None, T0 + 60);

    let again = receive(&mut incidents, vec![alert("backend", T0)], T0 + 120);

    assert_eq!(analyze_commands(&first), 1);
    assert!(again.is_empty());
}

#[test]
fn a_new_alert_arriving_with_an_ongoing_one_is_analyzed_on_its_own() {
    let mut incidents = incidents();
    open_backend(&mut incidents, true, None);

    let commands = receive(
        &mut incidents,
        vec![alert("backend", T0), alert("loki", T0 + 100)],
        T0 + 100,
    );

    let analyzed: Vec<_> = commands
        .iter()
        .filter_map(|c| match c {
            Command::Analyze { alerts, .. } => Some(
                alerts
                    .iter()
                    .map(|a| a.service.as_str().to_owned())
                    .collect::<Vec<_>>(),
            ),
            _ => None,
        })
        .collect();
    assert_eq!(analyzed, vec![vec!["loki".to_owned()]]);
}

#[test]
fn every_report_of_a_batch_becomes_its_own_incident_linked_by_batch_id() {
    let mut incidents = incidents();
    let commands = receive(
        &mut incidents,
        vec![alert("backend", T0), alert("loki", T0 + 10)],
        T0 + 10,
    );

    let posted = analyze_all(&mut incidents, &commands, true, None, T0 + 60);

    assert_eq!(incident_ids(&posted).len(), 2);
}

#[test]
fn analysis_result_attaches_to_its_own_batch_even_when_results_arrive_out_of_order() {
    let mut incidents = incidents();
    let first = receive(&mut incidents, vec![alert_named("a", "backend", T0)], T0);
    incidents
        .handle(
            Event::AlertsResolved(vec![alert_named("a", "backend", T0)]),
            T0 + 5,
        )
        .unwrap();
    let second = receive(
        &mut incidents,
        vec![alert_named("b", "backend", T0 + 20)],
        T0 + 20,
    );

    let later = analyze_all(&mut incidents, &second, true, None, T0 + 60);
    let earlier = analyze_all(&mut incidents, &first, false, None, T0 + 61);

    let alert_names = |commands: &[Command]| -> Vec<String> {
        commands
            .iter()
            .filter_map(|c| match c {
                Command::Post(Notice::Incident { report, .. }) => {
                    Some(report.alerts[0].name.clone())
                }
                _ => None,
            })
            .collect()
    };
    assert_eq!(alert_names(&later), vec!["b".to_owned()]);
    assert_eq!(alert_names(&earlier), vec!["a".to_owned()]);
}

#[test]
fn incident_stays_open_until_all_its_alerts_resolve() {
    let mut incidents = incidents();
    let commands = receive(
        &mut incidents,
        vec![
            alert_named("a", "backend", T0),
            alert_named("b", "backend", T0),
        ],
        T0,
    );
    analyze_all(&mut incidents, &commands, true, None, T0 + 60);
    incidents
        .handle(
            Event::AlertsResolved(vec![alert_named("a", "backend", T0)]),
            T0 + 100,
        )
        .unwrap();

    let again = receive(
        &mut incidents,
        vec![alert_named("b", "backend", T0)],
        T0 + 200,
    );
    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();

    assert!(again.is_empty());
    assert_eq!(proposals(&tick).len(), 1);
}

#[test]
fn over_the_daily_limit_alerts_are_posted_without_analysis() {
    let mut incidents = Incidents::open_in_memory(Settings {
        daily_limit: 1,
        ..settings()
    })
    .unwrap();
    let first = receive(&mut incidents, vec![alert("backend", T0)], T0);
    analyze_all(&mut incidents, &first, true, None, T0 + 60);

    let second = receive(&mut incidents, vec![alert("loki", T0 + 100)], T0 + 100);

    assert!(matches!(
        second.as_slice(),
        [Command::Post(Notice::LimitExceeded(_))]
    ));
}

#[test]
fn daily_limit_resets_at_midnight_korean_time() {
    let mut incidents = Incidents::open_in_memory(Settings {
        daily_limit: 1,
        ..settings()
    })
    .unwrap();
    let kst_2350 = 1_764_082_200;
    let first = receive(&mut incidents, vec![alert("backend", kst_2350)], kst_2350);
    analyze_all(&mut incidents, &first, true, None, kst_2350 + 60);

    let next_day = receive(
        &mut incidents,
        vec![alert("loki", kst_2350 + 1_200)],
        kst_2350 + 1_200,
    );

    assert_eq!(analyze_commands(&next_day), 1);
}

#[test]
fn resolving_unknown_alerts_changes_nothing() {
    let mut incidents = incidents();
    open_backend(&mut incidents, true, None);

    let commands = incidents
        .handle(Event::AlertsResolved(vec![alert("grafana", T0)]), T0 + 10)
        .unwrap();
    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();

    assert!(commands.is_empty());
    assert_eq!(proposals(&tick).len(), 1);
}
