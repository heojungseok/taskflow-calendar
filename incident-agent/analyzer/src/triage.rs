use std::collections::BTreeSet;

use crate::analysis::analyze;
use crate::commit::related_commit;
use crate::grouping::group_alerts;
use crate::known::match_known;
use crate::metric::first_changed_metric;
use crate::model::{Alert, Evidence, Knowledge, Missing, Report, Service, Suspect, Window};
use crate::new_error::new_error_suspect;
use crate::ports::{Clock, EvidenceSource, Model};
use crate::prompt;

const LOOKBACK_SECS: u64 = 900;

pub fn triage(
    alerts: &[Alert],
    knowledge: &Knowledge,
    evidence: &impl EvidenceSource,
    clock: &impl Clock,
    model: &impl Model,
) -> Vec<Report> {
    group_alerts(alerts)
        .into_iter()
        .map(|incident| report_for(incident, knowledge, evidence, clock, model))
        .collect()
}

fn report_for(
    alerts: Vec<Alert>,
    knowledge: &Knowledge,
    source: &impl EvidenceSource,
    clock: &impl Clock,
    model: &impl Model,
) -> Report {
    let collected = collect_evidence(&alerts, source, clock);
    let known_incident = match_known(knowledge, &collected);
    let suspects = suspects_for(&collected);
    let prompt = prompt::build(&collected, &suspects, known_incident.as_deref());
    Report {
        analysis: analyze(model, &prompt),
        known_incident,
        suspects,
        missing: collected.missing,
        alerts,
    }
}

fn collect_evidence(
    alerts: &[Alert],
    source: &impl EvidenceSource,
    clock: &impl Clock,
) -> Evidence {
    let first = alerts.first().map_or(0, |alert| alert.starts_at_unix);
    let last = alerts.last().map_or(0, |alert| alert.starts_at_unix);
    let window = Window {
        from_unix: first.saturating_sub(LOOKBACK_SECS),
        to_unix: clock.now_unix().max(last),
    };
    let services: Vec<Service> = alerts
        .iter()
        .map(|alert| alert.service.clone())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    source
        .collect(&window, &services)
        .unwrap_or_else(|_| Evidence {
            missing: vec![Missing::Evidence],
            ..Evidence::default()
        })
}

fn suspects_for(evidence: &Evidence) -> Vec<Suspect> {
    let can_judge_new = !evidence
        .missing
        .iter()
        .any(|gap| matches!(gap, Missing::Logs | Missing::Baseline));
    let new_error = if can_judge_new {
        new_error_suspect(evidence)
    } else {
        None
    };
    let commit = new_error
        .as_ref()
        .and_then(|suspect| related_commit(evidence, suspect));
    new_error
        .into_iter()
        .chain(first_changed_metric(evidence))
        .chain(commit)
        .collect()
}
