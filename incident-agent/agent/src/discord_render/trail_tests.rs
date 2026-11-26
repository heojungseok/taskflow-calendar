use analyzer::{Analysis, ErrorGroup, Missing, Report, Suspect, Trail, Window};
use incident::Notice;

use super::render;
use crate::test_support::{T0, alert, report};

const NINE_KST: u64 = 1_764_115_200;

fn text_of(report: Report, grafana_url: Option<&str>) -> String {
    render(
        &Notice::Incident {
            incident: 3,
            report,
        },
        grafana_url,
    )
    .text
}

fn with_trail(from_unix: u64, to_unix: u64, sent_lines: usize) -> Report {
    let mut report = report(&[alert("backend", T0)], false);
    report.analysis = Analysis::Unknown;
    report.trail = Trail {
        window: Window { from_unix, to_unix },
        log_lines: 914,
        sent_lines,
    };
    report
}

fn position(text: &str, needle: &str) -> usize {
    text.find(needle)
        .unwrap_or_else(|| panic!("{needle} not in {text}"))
}

fn decode(encoded: &str) -> String {
    let bytes = encoded.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            out.push(u8::from_str_radix(&encoded[i + 1..i + 3], 16).unwrap());
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap()
}

#[test]
fn t39_모델_추정과_코드가_고른_의심을_나눠_보인다() {
    let text = text_of(report(&[alert("backend", T0)], true), None);
    let model = position(&text, "모델 추정(확인 필요)");
    assert!(model < position(&text, "원인: 검색 null 처리 누락"));
    assert!(position(&text, "신뢰도: 높음") < position(&text, "코드가 고른 의심"));
}

#[test]
fn t39_새_오류_의심에_traceid를_붙인다() {
    let mut report = with_trail(NINE_KST, NINE_KST + 900, 9);
    report.suspects = vec![Suspect::NewError {
        first: ErrorGroup {
            fingerprint: "f".to_owned(),
            logger: "c.t.TaskSearchService".to_owned(),
            first_seen_unix: T0,
            count: 3,
            trace_ids: vec!["9f1a2b3c4d5e6f70".to_owned(), "0a1b2c3d4e5f6071".to_owned()],
        },
        most_frequent: None,
    }];
    let text = text_of(report, None);
    assert!(
        text.contains("traceId 9f1a2b3c4d5e6f70, 0a1b2c3d4e5f6071"),
        "{text}"
    );
}

#[test]
fn t39_읽은_로그와_모델에_보낸_증거를_단위와_함께_보인다() {
    let text = text_of(with_trail(NINE_KST, NINE_KST + 900, 9), None);
    assert!(
        text.contains(
            "증거: ERROR·WARN 로그 914줄 · 모델에 보낸 증거 9줄(같은 오류는 한 줄로) · 09:00~09:15 KST"
        ),
        "{text}"
    );
    assert!(!text.contains("로그 보기"), "{text}");
}

#[test]
fn t39_모델을_부르지_않았으면_보내지_않았다고_한다() {
    let text = text_of(with_trail(NINE_KST, NINE_KST + 900, 0), None);
    assert!(text.contains("모델에 보내지 않음"), "{text}");
}

#[test]
fn t39_구간이_자정을_넘으면_날짜를_붙인다() {
    let before_midnight = NINE_KST + 15 * 3600 - 600;
    let text = text_of(with_trail(before_midnight, before_midnight + 900, 9), None);
    assert!(text.contains("11-26 23:50~11-27 00:05 KST"), "{text}");
}

#[test]
fn t39_구간_누락_증거_링크를_의심_목록보다_앞에_둔다() {
    let mut report = with_trail(NINE_KST, NINE_KST + 900, 9);
    report.missing = vec![Missing::Baseline];
    report.suspects = vec![Suspect::FirstChangedMetric {
        name: "pg_connection_ratio".to_owned(),
        changed_at_unix: T0,
    }];
    let text = text_of(report, Some("https://grafana.example"));
    let suspects = position(&text, "코드가 고른 의심");
    assert!(position(&text, "증거: ERROR·WARN") < position(&text, "누락 증거"));
    assert!(position(&text, "누락 증거") < position(&text, "로그 보기"));
    assert!(position(&text, "로그 보기") < suspects);
}

#[test]
fn t39_grafana_주소가_있으면_그_구간의_loki_explore_링크를_붙인다() {
    let text = text_of(
        with_trail(NINE_KST, NINE_KST + 900, 9),
        Some("https://grafana.example/"),
    );
    let prefix = "(<https://grafana.example/explore?schemaVersion=1&orgId=1&panes=";
    let start = position(&text, prefix) + prefix.len();
    let end = start + text[start..].find(">)").unwrap();
    let panes: serde_json::Value = serde_json::from_str(&decode(&text[start..end])).unwrap();
    assert_eq!(panes["a"]["datasource"], "loki");
    assert_eq!(
        panes["a"]["queries"][0]["expr"],
        "{service=~\"backend|nginx\"}"
    );
    assert_eq!(panes["a"]["range"]["from"], "1764115200000");
    assert_eq!(panes["a"]["range"]["to"], "1764116100000");
}
