use std::cmp::Reverse;

use crate::fingerprint::fingerprint;
use crate::model::{ErrorGroup, Evidence, LogLine, Suspect};

pub(crate) fn new_error_suspect(evidence: &Evidence) -> Option<Suspect> {
    let baseline: Vec<String> = fingerprints(&evidence.baseline_logs)
        .map(|(text, _, _)| text)
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
    for (text, logger, at_unix) in fingerprints(logs) {
        if baseline.contains(&text) {
            continue;
        }
        match groups.iter_mut().find(|g| g.fingerprint == text) {
            Some(group) => {
                group.count += 1;
                group.first_seen_unix = group.first_seen_unix.min(at_unix);
            }
            None => groups.push(ErrorGroup {
                fingerprint: text,
                logger,
                first_seen_unix: at_unix,
                count: 1,
            }),
        }
    }
    groups
}

fn fingerprints(lines: &[LogLine]) -> impl Iterator<Item = (String, String, u64)> + '_ {
    lines.iter().filter_map(|line| {
        let f = fingerprint(&line.text)?;
        Some((f.text, f.logger, line.at_unix))
    })
}
