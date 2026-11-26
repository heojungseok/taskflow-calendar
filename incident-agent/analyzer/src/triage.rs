use std::collections::BTreeSet;

use crate::analysis::analyze;
use crate::commit::related_commit;
use crate::grouping::group_alerts;
use crate::known::match_known;
use crate::metric::first_changed_metric;
use crate::model::{
    Alert, Analysis, Evidence, Knowledge, Missing, Report, Service, SkipReason, Suspect, Trail,
    Window,
};
use crate::new_error::new_error_suspect;
use crate::ports::{Clock, EvidenceSource, Model};
use crate::prompt::{self, Prompt};

const LOOKBACK_SECS: u64 = 900;

const MAX_MODEL_CALLS: usize = 3;

pub fn triage(
    alerts: &[Alert],
    knowledge: &Knowledge,
    evidence: &impl EvidenceSource,
    clock: &impl Clock,
    model: &impl Model,
) -> Vec<Report> {
    let mut calls = 0;
    group_alerts(alerts)
        .into_iter()
        .map(|incident| report_for(incident, knowledge, evidence, clock, (model, &mut calls)))
        .collect()
}

fn report_for(
    alerts: Vec<Alert>,
    knowledge: &Knowledge,
    source: &impl EvidenceSource,
    clock: &impl Clock,
    (model, calls): (&impl Model, &mut usize),
) -> Report {
    let (collected, window) = collect_evidence(&alerts, source, clock);
    let known_incident = match_known(knowledge, &collected);
    let suspects = suspects_for(&collected);
    let (analysis, sent_lines) = if known_incident
        .as_ref()
        .is_some_and(|known| !known.visitor_triggerable)
    {
        (Analysis::Skipped(SkipReason::KnownIncident), 0)
    } else {
        let prompt = prompt::build(&alerts, &collected, &suspects);
        (analysis_for(model, &prompt, calls), prompt.lines.len())
    };
    Report {
        trail: Trail {
            window,
            log_lines: collected.logs.len(),
            sent_lines,
        },
        analysis,
        known_incident,
        suspects,
        missing: collected.missing,
        alerts,
    }
}

fn analysis_for(model: &impl Model, prompt: &Prompt, calls: &mut usize) -> Analysis {
    if prompt.lines.is_empty() {
        return Analysis::Skipped(SkipReason::NoEvidence);
    }
    if *calls >= MAX_MODEL_CALLS {
        return Analysis::Skipped(SkipReason::CallLimit);
    }
    *calls += 1;
    analyze(model, prompt)
}

fn collect_evidence(
    alerts: &[Alert],
    source: &impl EvidenceSource,
    clock: &impl Clock,
) -> (Evidence, Window) {
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
    let evidence = source
        .collect(&window, &services)
        .unwrap_or_else(|_| Evidence {
            missing: vec![Missing::Evidence],
            ..Evidence::default()
        });
    (evidence, window)
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
