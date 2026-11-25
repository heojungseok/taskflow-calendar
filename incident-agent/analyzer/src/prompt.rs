use crate::mask::mask_log;
use crate::metric::changed_points;
use crate::model::{Evidence, Missing, Suspect};

const MAX_LOG_LINES: usize = 200;

const INSTRUCTIONS: &str = "당신은 장애 분석기다. 아래 증거 줄만 근거로 원인을 추정하라.\n\
다음 JSON 하나로만 답하라: {\"cause\":\"...\",\"evidence\":\"...\",\"confidence\":\"high|medium|low\",\"cited_lines\":[n]}\n\
cited_lines에는 근거로 쓴 증거 줄 번호(L 뒤의 숫자)를 넣는다. 확실하지 않으면 confidence를 low로 답하라.";

pub(crate) struct Prompt {
    pub(crate) text: String,
    pub(crate) line_count: usize,
}

pub(crate) fn build(
    evidence: &Evidence,
    suspects: &[Suspect],
    known_incident: Option<&str>,
) -> Prompt {
    let lines = evidence_lines(evidence);
    let mut sections = vec![INSTRUCTIONS.to_owned()];
    if evidence
        .missing
        .iter()
        .any(|gap| matches!(gap, Missing::Logs | Missing::Evidence))
    {
        sections.push("로그를 읽지 못함".to_owned());
    }
    sections.push(numbered(&lines));
    sections.extend(suspects.iter().map(describe_suspect));
    sections.extend(known_incident.map(|source| format!("알려진 사고 문서: {source}")));
    Prompt {
        text: sections.join("\n"),
        line_count: lines.len(),
    }
}

fn numbered(lines: &[String]) -> String {
    lines
        .iter()
        .enumerate()
        .map(|(i, line)| format!("L{}: {line}", i + 1))
        .collect::<Vec<_>>()
        .join("\n")
}

fn evidence_lines(evidence: &Evidence) -> Vec<String> {
    let logs = evidence
        .logs
        .iter()
        .take(MAX_LOG_LINES)
        .map(|line| mask_log(&line.text));
    let metrics = evidence.metrics.iter().map(|series| {
        let changed: Vec<String> = changed_points(series)
            .iter()
            .map(|(at, value)| format!("{at}={value}"))
            .collect();
        format!(
            "지표 {}: baseline {}, 변화 {}",
            series.name,
            series.baseline,
            if changed.is_empty() {
                "없음".to_owned()
            } else {
                changed.join(", ")
            }
        )
    });
    let commits = evidence
        .commits
        .iter()
        .map(|commit| format!("커밋 {}: {}", commit.sha, commit.files.join(", ")));
    logs.chain(metrics).chain(commits).collect()
}

fn describe_suspect(suspect: &Suspect) -> String {
    match suspect {
        Suspect::NewError {
            first,
            most_frequent,
        } => {
            let frequent = most_frequent.as_ref().map_or(String::new(), |g| {
                format!(", 최다 {} ({}회)", mask_log(&g.fingerprint), g.count)
            });
            format!(
                "용의: 새 에러 {} ({}회, {}부터){frequent}",
                mask_log(&first.fingerprint),
                first.count,
                first.first_seen_unix
            )
        }
        Suspect::FirstChangedMetric {
            name,
            changed_at_unix,
        } => {
            format!("용의: 가장 먼저 변한 지표 {name} ({changed_at_unix})")
        }
        Suspect::RelatedCommit { sha } => format!("용의: 관련 커밋 {sha}"),
    }
}
