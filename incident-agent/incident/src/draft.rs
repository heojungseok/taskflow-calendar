use analyzer::{Analysis, Report, Suspect};

use crate::Verdict;

fn suspect_line(suspect: &Suspect) -> String {
    match suspect {
        Suspect::NewError { first, .. } => format!("- 새 에러: {}", first.logger),
        Suspect::FirstChangedMetric { name, .. } => format!("- 가장 먼저 변한 지표: {name}"),
        Suspect::RelatedCommit { sha } => format!("- 관련 커밋: {sha}"),
    }
}

fn analysis_line(analysis: &Analysis) -> String {
    match analysis {
        Analysis::Guess(guess) => {
            format!("추정 원인: {} (근거: {})", guess.cause(), guess.evidence())
        }
        Analysis::Unknown => "추정 원인: 알 수 없음".to_owned(),
        Analysis::Skipped(reason) => format!("분석 건너뜀: {reason:?}"),
    }
}

pub(crate) fn summarize(report: &Report) -> String {
    let mut lines = Vec::new();
    if let Some(known) = &report.known_incident {
        lines.push(format!("알려진 장애: {known}"));
    }
    lines.extend(report.suspects.iter().map(suspect_line));
    lines.push(analysis_line(&report.analysis));
    lines.join("\n")
}

pub(crate) fn verdict_text(verdict: &Verdict) -> &'static str {
    match verdict {
        Verdict::Correct => "correct",
        Verdict::Wrong { .. } => "wrong",
    }
}

pub(crate) fn markdown(
    incident: u64,
    alert_names: &str,
    summary: &str,
    verdict: &Verdict,
) -> String {
    let judgement = match verdict {
        Verdict::Correct => "분석이 맞았다".to_owned(),
        Verdict::Wrong { actual_cause } => format!("분석이 틀렸다\n\n실제 원인: {actual_cause}"),
    };
    format!(
        "# 장애 #{incident} 문서 초안\n\n## 알림\n{alert_names}\n\n## 분석 요약\n{summary}\n\n## 판정\n{judgement}\n"
    )
}
