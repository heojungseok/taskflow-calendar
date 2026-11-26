use std::cmp::Reverse;

use crate::fingerprint::fingerprint;
use crate::logline::request_trace_id;
use crate::model::{ErrorGroup, Evidence, LogLine, Suspect};

const MAX_TRACE_IDS: usize = 3;

pub(crate) fn new_error_suspect(evidence: &Evidence) -> Option<Suspect> {
    let baseline: Vec<String> = fingerprints(&evidence.baseline_logs)
        .map(|(text, ..)| text)
        .collect();
    let groups = group_new(&evidence.logs, &baseline);
    let first = groups
        .iter()
        .min_by_key(|g| (g.first_seen_unix, Reverse(g.count)))?;
    let most_frequent = groups
        .iter()
        .max_by_key(|g| (g.count, Reverse(g.first_seen_unix)))
        .filter(|g| g.fingerprint != first.fingerprint);
    Some(Suspect::NewError {
        first: first.clone(),
        most_frequent: most_frequent.cloned(),
    })
}

fn group_new(logs: &[LogLine], baseline: &[String]) -> Vec<ErrorGroup> {
    let mut groups: Vec<ErrorGroup> = Vec::new();
    for (text, logger, at_unix, trace_id) in fingerprints(logs) {
        if baseline.contains(&text) {
            continue;
        }
        match groups.iter_mut().find(|g| g.fingerprint == text) {
            Some(group) => {
                group.count += 1;
                group.first_seen_unix = group.first_seen_unix.min(at_unix);
                keep_trace_id(group, trace_id);
            }
            None => {
                let mut group = ErrorGroup {
                    fingerprint: text,
                    logger,
                    first_seen_unix: at_unix,
                    count: 1,
                    trace_ids: Vec::new(),
                };
                keep_trace_id(&mut group, trace_id);
                groups.push(group);
            }
        }
    }
    groups
}

fn fingerprints(
    lines: &[LogLine],
) -> impl Iterator<Item = (String, String, u64, Option<String>)> + '_ {
    lines.iter().filter_map(|line| {
        let f = fingerprint(&line.text)?;
        Some((
            f.text,
            f.logger,
            line.at_unix,
            request_trace_id(&line.text).map(str::to_owned),
        ))
    })
}

fn keep_trace_id(group: &mut ErrorGroup, trace_id: Option<String>) {
    if let Some(id) = trace_id
        && group.trace_ids.len() < MAX_TRACE_IDS
        && !group.trace_ids.contains(&id)
    {
        group.trace_ids.push(id);
    }
}
