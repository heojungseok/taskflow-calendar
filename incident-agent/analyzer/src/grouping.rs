use crate::model::{Alert, Service};

const GROUPING_WINDOW_SECS: u64 = 300;

const DEPENDENCY_EDGES: [(&str, &str); 4] = [
    ("postgres", "backend"),
    ("redis", "backend"),
    ("backend", "nginx"),
    ("alloy", "loki"),
];

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
    if a == b {
        return true;
    }
    let mut reached = vec![a.as_str()];
    let mut next = 0;
    while let Some(&current) = reached.get(next) {
        next += 1;
        for neighbor in neighbors(current) {
            if neighbor == b.as_str() {
                return true;
            }
            if !reached.contains(&neighbor) {
                reached.push(neighbor);
            }
        }
    }
    false
}

fn neighbors(service: &str) -> impl Iterator<Item = &'static str> {
    DEPENDENCY_EDGES.into_iter().filter_map(move |(x, y)| {
        if x == service {
            Some(y)
        } else if y == service {
            Some(x)
        } else {
            None
        }
    })
}
