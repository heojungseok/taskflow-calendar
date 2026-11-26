use analyzer::{Analysis, Confidence, Missing, SkipReason, Suspect};

const MARKDOWN: &str = "\\*_~`|#[]()<>@";

pub(crate) fn safe(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            '\n' | '\r' => escaped.push(' '),
            ch if MARKDOWN.contains(ch) => {
                escaped.push('\\');
                escaped.push(ch);
            }
            ch => escaped.push(ch),
        }
    }
    escaped
}

pub(crate) fn missing_text(missing: &[Missing]) -> Option<String> {
    if missing.is_empty() {
        return None;
    }
    let names: Vec<&str> = missing
        .iter()
        .map(|gap| match gap {
            Missing::Evidence => "증거 전체",
            Missing::Logs => "로그",
            Missing::Baseline => "기준 로그",
        })
        .collect();
    Some(format!("누락 증거: {}", names.join(", ")))
}

pub(crate) fn suspect_text(suspect: &Suspect) -> String {
    match suspect {
        Suspect::NewError { first, .. } => {
            format!("새 오류: {} ({}건)", safe(&first.logger), first.count)
        }
        Suspect::FirstChangedMetric { name, .. } => format!("먼저 바뀐 지표: {}", safe(name)),
        Suspect::RelatedCommit { sha } => {
            format!(
                "관련 커밋: {}",
                safe(&sha.chars().take(7).collect::<String>())
            )
        }
    }
}

fn confidence_name(confidence: Confidence) -> &'static str {
    match confidence {
        Confidence::High => "높음",
        Confidence::Medium => "중간",
    }
}

fn skip_reason(reason: SkipReason) -> &'static str {
    match reason {
        SkipReason::ModelTimeout => "모델 응답 시간 초과",
        SkipReason::ModelUnavailable => "모델 사용 불가",
        SkipReason::InvalidAnswer => "모델 답이 형식에 맞지 않음",
        SkipReason::UnsupportedCitation => "모델 답의 근거를 확인하지 못함",
        SkipReason::NoEvidence => "증거 없음",
        SkipReason::KnownIncident => "알려진 장애",
        SkipReason::CallLimit => "분석 호출 한도",
    }
}

pub(crate) fn analysis_text(analysis: &Analysis) -> String {
    match analysis {
        Analysis::Guess(guess) => format!(
            "원인 추정: {}\n근거: {}\n신뢰도: {}",
            safe(guess.cause()),
            safe(guess.evidence()),
            confidence_name(guess.confidence())
        ),
        Analysis::Unknown => "원인을 추정하지 못했다".to_owned(),
        Analysis::Skipped(reason) => format!("분석 건너뜀: {}", skip_reason(*reason)),
    }
}
