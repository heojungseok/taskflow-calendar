use analyzer::{Analysis, Report, Suspect};

use crate::Verdict;

pub(crate) struct DraftData {
    pub(crate) alerts: Vec<String>,
    pub(crate) summary: String,
    pub(crate) cause: Option<String>,
    pub(crate) evidence: Option<String>,
    pub(crate) note: String,
}

fn suspect_line(suspect: &Suspect) -> String {
    match suspect {
        Suspect::NewError { first, .. } => format!("- 새 에러: {}", first.logger),
        Suspect::FirstChangedMetric { name, .. } => format!("- 가장 먼저 변한 지표: {name}"),
        Suspect::RelatedCommit { sha } => format!("- 관련 커밋: {sha}"),
    }
}

pub(crate) fn summarize(report: &Report) -> String {
    let mut lines = Vec::new();
    if let Some(known) = &report.known_incident {
        lines.push(format!("- 알려진 장애: {known}"));
    }
    lines.extend(report.suspects.iter().map(suspect_line));
    lines.join("\n")
}

pub(crate) fn analysis_note(analysis: &Analysis) -> String {
    match analysis {
        Analysis::Guess(_) => String::new(),
        Analysis::Unknown => "모델이 원인을 특정하지 못했다".to_owned(),
        Analysis::Skipped(reason) => format!("분석을 건너뛰었다 ({reason:?})"),
    }
}

pub(crate) fn verdict_text(verdict: &Verdict) -> &'static str {
    match verdict {
        Verdict::Correct => "correct",
        Verdict::Wrong { .. } => "wrong",
    }
}

fn quote(text: &str) -> String {
    text.lines()
        .map(|line| format!("> {line}"))
        .collect::<Vec<_>>()
        .join("\n")
}

fn analysis_section(data: &DraftData) -> String {
    match (&data.cause, &data.evidence) {
        (Some(cause), evidence) => {
            let body = format!(
                "추정 원인: {cause}\n근거: {}",
                evidence.as_deref().unwrap_or("")
            );
            format!(
                "## 모델 분석 (모델 출력, 사람이 검증하지 않음)\n{}",
                quote(&body)
            )
        }
        _ => format!("## 분석\n분석 결과가 없다. {}", data.note),
    }
}

pub(crate) fn markdown(incident: u64, data: &DraftData, verdict: &Verdict) -> String {
    let alerts = data
        .alerts
        .iter()
        .map(|a| format!("- {a}"))
        .collect::<Vec<_>>()
        .join("\n");
    let judgement = match verdict {
        Verdict::Correct => "분석이 맞았다".to_owned(),
        Verdict::Wrong { actual_cause } => format!("분석이 틀렸다\n\n실제 원인: {actual_cause}"),
    };
    format!(
        "# 장애 #{incident} 문서 초안\n\n## 알림\n{alerts}\n\n## 코드가 고른 의심\n{}\n\n{}\n\n## 판정\n{judgement}\n",
        data.summary,
        analysis_section(data)
    )
}
