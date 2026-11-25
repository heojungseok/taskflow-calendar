use std::collections::{BTreeMap, BTreeSet};

use crate::model::{Evidence, LogLine, Suspect};

pub(crate) fn new_error_suspect(evidence: &Evidence) -> Option<Suspect> {
    let baseline: BTreeSet<String> = error_fingerprints(&evidence.baseline_logs)
        .map(|(fingerprint, _)| fingerprint)
        .collect();
    let mut groups: BTreeMap<String, (u64, usize)> = BTreeMap::new();
    for (fingerprint, at_unix) in error_fingerprints(&evidence.logs) {
        if !baseline.contains(&fingerprint) {
            let (first_seen, count) = groups.entry(fingerprint).or_insert((at_unix, 0));
            *first_seen = (*first_seen).min(at_unix);
            *count += 1;
        }
    }
    groups
        .into_iter()
        .max_by_key(|(_, (first_seen, count))| (*count, std::cmp::Reverse(*first_seen)))
        .map(
            |(fingerprint, (first_seen_unix, count))| Suspect::NewError {
                fingerprint,
                first_seen_unix,
                count,
            },
        )
}

fn error_fingerprints(lines: &[LogLine]) -> impl Iterator<Item = (String, u64)> + '_ {
    lines
        .iter()
        .filter(|line| line.text.starts_with("ERROR"))
        .map(|line| (fingerprint(&line.text), line.at_unix))
}

fn fingerprint(text: &str) -> String {
    let without_trace = text.split(" traceId=").next().unwrap_or(text);
    let mut out = String::with_capacity(without_trace.len());
    let mut in_digits = false;
    for c in without_trace.chars() {
        if c.is_ascii_digit() {
            if !in_digits {
                out.push('#');
            }
            in_digits = true;
        } else {
            out.push(c);
            in_digits = false;
        }
    }
    out.trim().to_owned()
}
