use crate::model::{Alert, Service};

const GROUPING_WINDOW_SECS: u64 = 300;

pub(crate) fn group_alerts(alerts: &[Alert]) -> Vec<Vec<Alert>> {
    let mut sorted = alerts.to_vec();
    sorted.sort_by_key(|alert| alert.starts_at_unix);

    let mut incidents: Vec<Vec<Alert>> = Vec::new();
    for alert in sorted {
        match incidents
            .iter_mut()
            .find(|incident| joins(incident, &alert))
        {
            Some(incident) => incident.push(alert),
            None => incidents.push(vec![alert]),
        }
    }
    incidents
}

fn joins(incident: &[Alert], alert: &Alert) -> bool {
    let Some(first) = incident.first() else {
        return false;
    };
    alert.starts_at_unix - first.starts_at_unix <= GROUPING_WINDOW_SECS
        && incident
            .iter()
            .any(|member| connected(&member.service, &alert.service))
}

fn connected(a: &Service, b: &Service) -> bool {
    a == b
        || component(a.as_str())
            .zip(component(b.as_str()))
            .is_some_and(|(x, y)| x == y)
}

fn component(service: &str) -> Option<u8> {
    match service {
        "postgres" | "redis" | "backend" | "nginx" => Some(0),
        "alloy" | "loki" => Some(1),
        _ => None,
    }
}
