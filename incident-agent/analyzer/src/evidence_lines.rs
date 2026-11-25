use crate::fingerprint::fingerprint_any_level;
use crate::logline::Level;
use crate::logline::parse_log;
use crate::mask::{clean, mask_log, mask_message};
use crate::metric::changed_points;
use crate::model::{Evidence, Suspect};

const MAX_LINE_CHARS: usize = 300;
const COUNT_SUFFIX_ROOM: usize = 20;

#[derive(Debug, Clone)]
pub(crate) struct EvidenceLine {
    pub(crate) text: String,
    pub(crate) picked: bool,
    pub(crate) is_log: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Error,
    Warn,
    Nginx,
}

struct LogGroup {
    key: String,
    text: String,
    count: usize,
    kind: Kind,
}

pub(crate) fn truncate(text: &str, max: usize) -> String {
    text.chars().take(max).collect()
}

pub(crate) fn collect(evidence: &Evidence, suspects: &[Suspect]) -> Vec<EvidenceLine> {
    let picked_keys = picked_fingerprints(suspects);
    let mut ranked: Vec<(u8, EvidenceLine)> = Vec::new();
    for group in log_groups(evidence) {
        let picked = group.kind == Kind::Error && picked_keys.contains(&group.key);
        let rank = match (picked, group.kind) {
            (true, _) => 0,
            (_, Kind::Error) => 2,
            (_, Kind::Warn) => 3,
            (_, Kind::Nginx) => 4,
        };
        ranked.push((rank, log_line(&group, picked)));
    }
    ranked.extend(metric_lines(evidence));
    ranked.extend(evidence.commits.iter().map(|commit| {
        let text = format!("커밋 {}: {}", commit.sha, commit.files.join(", "));
        (
            6,
            EvidenceLine {
                text: truncate(&mask_message(&text), MAX_LINE_CHARS),
                picked: false,
                is_log: false,
            },
        )
    }));
    ranked.sort_by_key(|(rank, _)| *rank);
    ranked.into_iter().map(|(_, line)| line).collect()
}

fn picked_fingerprints(suspects: &[Suspect]) -> Vec<String> {
    suspects
        .iter()
        .filter_map(|suspect| match suspect {
            Suspect::NewError {
                first,
                most_frequent,
            } => Some([Some(first), most_frequent.as_ref()]),
            _ => None,
        })
        .flatten()
        .flatten()
        .map(|group| group.fingerprint.clone())
        .collect()
}

fn log_line(group: &LogGroup, picked: bool) -> EvidenceLine {
    let text = if group.count > 1 {
        let room = MAX_LINE_CHARS - COUNT_SUFFIX_ROOM;
        format!("{} (×{})", truncate(&group.text, room), group.count)
    } else {
        truncate(&group.text, MAX_LINE_CHARS)
    };
    EvidenceLine {
        text,
        picked,
        is_log: true,
    }
}

fn log_groups(evidence: &Evidence) -> Vec<LogGroup> {
    let mut groups: Vec<LogGroup> = Vec::new();
    for line in &evidence.logs {
        let text = clean(&line.text);
        let Some((key, shown, kind)) = describe(line.service.as_str(), &text) else {
            continue;
        };
        match groups.iter_mut().find(|g| g.key == key) {
            Some(group) => group.count += 1,
            None => groups.push(LogGroup {
                key,
                text: shown,
                count: 1,
                kind,
            }),
        }
    }
    groups
}

fn describe(service: &str, text: &str) -> Option<(String, String, Kind)> {
    if service == "nginx" {
        let summary = nginx_summary(text)?;
        return Some((format!("nginx {summary}"), summary, Kind::Nginx));
    }
    let entry = parse_log(text)?;
    let kind = if entry.level == Level::Error {
        Kind::Error
    } else {
        Kind::Warn
    };
    Some((fingerprint_any_level(text)?.text, mask_log(text), kind))
}

fn metric_lines(evidence: &Evidence) -> Vec<(u8, EvidenceLine)> {
    evidence
        .metrics
        .iter()
        .map(|series| {
            let changed = changed_points(series);
            let shown = if changed.is_empty() {
                "없음".to_owned()
            } else {
                changed
                    .iter()
                    .map(|(at, v)| format!("{at}={v}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            };
            let text = format!(
                "지표 {}: baseline {}, 변화 {shown}",
                series.name, series.baseline
            );
            let picked = !changed.is_empty();
            let line = EvidenceLine {
                text: truncate(&mask_message(&text), MAX_LINE_CHARS),
                picked,
                is_log: false,
            };
            (if picked { 1 } else { 5 }, line)
        })
        .collect()
}

fn nginx_summary(text: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(text.trim()).ok()?;
    let object = value.as_object()?;
    let field = |key: &str| match object.get(key) {
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
        None => String::new(),
    };
    let method = safe(&field("method"), |c| c.is_ascii_alphabetic(), 10);
    let status = safe(&field("status"), |c| c.is_ascii_digit(), 3);
    let rt = safe(&field("rt"), |c| c.is_ascii_digit() || c == '.', 10);
    let path_field = field("path");
    let path = path_field.split('?').next().unwrap_or("");
    let path = mask_message(&mask_digits(&truncate(path, 200)));
    Some(format!("{method} {path} {status} {rt}s"))
}

fn safe(value: &str, allowed: fn(char) -> bool, max: usize) -> String {
    if !value.is_empty() && value.chars().count() <= max && value.chars().all(allowed) {
        value.to_owned()
    } else {
        "?".to_owned()
    }
}

fn mask_digits(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut in_digits = false;
    for c in text.chars() {
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
    out
}
