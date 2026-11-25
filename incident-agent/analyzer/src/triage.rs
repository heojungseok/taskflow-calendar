use crate::commit::related_commit;
use crate::grouping::group_alerts;
use crate::known::match_known;
use crate::metric::first_changed_metric;
use crate::model::{Alert, Evidence, Knowledge, Report, Service, Suspect, Window};
use crate::new_error::new_error_suspect;
use crate::ports::{Clock, EvidenceSource};

const LOOKBACK_SECS: u64 = 900;

pub fn triage(
    alerts: &[Alert],
    knowledge: &Knowledge,
    evidence: &impl EvidenceSource,
    clock: &impl Clock,
) -> Vec<Report> {
    group_alerts(alerts)
        .into_iter()
        .map(|incident| report_for(incident, knowledge, evidence, clock))
        .collect()
}

fn report_for(
    alerts: Vec<Alert>,
    knowledge: &Knowledge,
    source: &impl EvidenceSource,
    clock: &impl Clock,
) -> Report {
    let collected = collect_evidence(&alerts, source, clock);
    Report {
        known_incident: match_known(knowledge, &collected),
        suspects: suspects_for(&collected),
        alerts,
    }
}

fn collect_evidence(
    alerts: &[Alert],
    source: &impl EvidenceSource,
    clock: &impl Clock,
) -> Evidence {
    let first = alerts.first().map_or(0, |alert| alert.starts_at_unix);
    let window = Window {
        from_unix: first.saturating_sub(LOOKBACK_SECS),
        to_unix: clock.now_unix(),
    };
    let mut services: Vec<Service> = Vec::new();
    for alert in alerts {
        if !services.contains(&alert.service) {
            services.push(alert.service.clone());
        }
    }
    source.collect(&window, &services).unwrap_or_default()
}

fn suspects_for(evidence: &Evidence) -> Vec<Suspect> {
    let new_error = new_error_suspect(evidence);
    let commit = new_error
        .as_ref()
        .and_then(|suspect| related_commit(evidence, suspect));
    new_error
        .into_iter()
        .chain(first_changed_metric(evidence))
        .chain(commit)
        .collect()
}
