use crate::model::{Evidence, LogLine, Suspect};

struct Group {
    fingerprint: String,
    first_seen_unix: u64,
    count: usize,
}

pub(crate) fn new_error_suspect(evidence: &Evidence) -> Option<Suspect> {
    let baseline: Vec<String> = error_fingerprints(&evidence.baseline_logs)
        .map(|(fingerprint, _)| fingerprint)
        .collect();
    let mut groups: Vec<Group> = Vec::new();
    for (fingerprint, at_unix) in error_fingerprints(&evidence.logs) {
        if baseline.contains(&fingerprint) {
            continue;
        }
        match groups.iter_mut().find(|g| g.fingerprint == fingerprint) {
            Some(group) => {
                group.count += 1;
                group.first_seen_unix = group.first_seen_unix.min(at_unix);
            }
            None => groups.push(Group {
                fingerprint,
                first_seen_unix: at_unix,
                count: 1,
            }),
        }
    }
    groups
        .into_iter()
        .max_by_key(|g| (g.count, std::cmp::Reverse(g.first_seen_unix)))
        .map(|g| Suspect::NewError {
            fingerprint: g.fingerprint,
            first_seen_unix: g.first_seen_unix,
            count: g.count,
        })
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
