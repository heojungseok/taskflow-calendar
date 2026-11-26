use analyzer::{Analysis, Confidence, Missing, SkipReason, Suspect, Trail, Window};

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
            let line = format!("새 오류: {} ({}건)", safe(&first.logger), first.count);
            if first.trace_ids.is_empty() {
                return line;
            }
            let ids: Vec<String> = first.trace_ids.iter().map(|id| safe(id)).collect();
            format!("{line} · traceId {}", ids.join(", "))
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
            "모델 추정(확인 필요)\n원인: {}\n근거: {}\n신뢰도: {}",
            safe(guess.cause()),
            safe(guess.evidence()),
            confidence_name(guess.confidence())
        ),
        Analysis::Unknown => "모델 추정: 원인을 추정하지 못했다".to_owned(),
        Analysis::Skipped(reason) => format!("모델 분석 건너뜀: {}", skip_reason(*reason)),
    }
}

const KST_OFFSET_SECS: u64 = 9 * 3600;
const DAY_SECS: u64 = 86_400;

fn kst_day(unix: u64) -> u64 {
    (unix + KST_OFFSET_SECS) / DAY_SECS
}

fn kst_clock(unix: u64) -> String {
    let seconds = (unix + KST_OFFSET_SECS) % DAY_SECS;
    format!("{:02}:{:02}", seconds / 3600, seconds % 3600 / 60)
}

fn month_day(days_since_epoch: u64) -> String {
    let (month, day) = crate::rfc3339::month_day(days_since_epoch);
    format!("{month:02}-{day:02}")
}

fn kst_range(window: &Window) -> String {
    let (from, to) = (window.from_unix, window.to_unix);
    if kst_day(from) == kst_day(to) {
        return format!("{}~{} KST", kst_clock(from), kst_clock(to));
    }
    format!(
        "{} {}~{} {} KST",
        month_day(kst_day(from)),
        kst_clock(from),
        month_day(kst_day(to)),
        kst_clock(to)
    )
}

pub(crate) fn trail_text(trail: &Trail) -> String {
    let sent = if trail.sent_lines == 0 {
        "모델에 보내지 않음".to_owned()
    } else {
        format!(
            "모델에 보낸 증거 {}줄(같은 오류는 한 줄로)",
            trail.sent_lines
        )
    };
    format!(
        "증거: ERROR·WARN 로그 {}줄 · {sent} · {}",
        trail.log_lines,
        kst_range(&trail.window)
    )
}

pub(crate) fn loki_link(base: &str, window: &Window) -> String {
    let panes = serde_json::json!({
        "a": {
            "datasource": "loki",
            "queries": [{"refId": "A", "expr": "{service=~\"backend|nginx\"}"}],
            "range": {
                "from": (window.from_unix * 1000).to_string(),
                "to": (window.to_unix * 1000).to_string(),
            },
        }
    });
    format!(
        "{}/explore?schemaVersion=1&orgId=1&panes={}",
        base.trim_end_matches('/'),
        query_escape(&panes.to_string())
    )
}

fn query_escape(text: &str) -> String {
    text.bytes()
        .map(|byte| {
            if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
                char::from(byte).to_string()
            } else {
                format!("%{byte:02X}")
            }
        })
        .collect()
}
