mod common;

use analyzer::{Evidence, Knowledge, triage};
use common::{FixedClock, FixedEvidence, alert};

#[test]
fn related_alerts_within_five_minutes_become_one_incident_and_unrelated_ones_stay_apart() {
    let alerts = [
        alert("db connections exhausted", "postgres", 1_000),
        alert("backend error logs", "backend", 1_100),
        alert("dashboard down", "grafana", 1_050),
        alert("backend error logs", "backend", 1_500),
    ];

    let reports = triage(
        &alerts,
        &Knowledge::default(),
        &FixedEvidence(Evidence::default()),
        &FixedClock(1_600),
    );

    let grouped: Vec<Vec<(&str, u64)>> = reports
        .iter()
        .map(|report| {
            report
                .alerts
                .iter()
                .map(|a| (a.service.as_str(), a.starts_at_unix))
                .collect()
        })
        .collect();
    assert_eq!(
        grouped,
        vec![
            vec![("postgres", 1_000), ("backend", 1_100)],
            vec![("grafana", 1_050)],
            vec![("backend", 1_500)],
        ]
    );
}

#[test]
fn five_minutes_are_counted_from_the_first_alert_of_the_incident() {
    let alerts = [
        alert("backend error logs", "backend", 1_000),
        alert("backend error logs", "backend", 1_300),
        alert("backend error logs", "backend", 1_301),
    ];

    let reports = triage(
        &alerts,
        &Knowledge::default(),
        &FixedEvidence(Evidence::default()),
        &FixedClock(1_400),
    );

    let sizes: Vec<usize> = reports.iter().map(|report| report.alerts.len()).collect();
    assert_eq!(sizes, vec![2, 1]);
}
