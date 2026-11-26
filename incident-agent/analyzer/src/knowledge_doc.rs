use crate::fingerprint::fingerprint;
use crate::knowledge_file::IssueKind;

const SECTIONS: [&str; 4] = ["증상", "원인", "확인할 것", "조치"];
const MIN_MESSAGE_LETTERS: usize = 12;

pub(crate) struct Rejection {
    pub(crate) kind: IssueKind,
    pub(crate) reason: String,
}

impl Rejection {
    pub(crate) fn malformed(reason: String) -> Self {
        Self {
            kind: IssueKind::Malformed,
            reason,
        }
    }
}

pub(crate) struct Doc {
    pub(crate) signature: String,
    pub(crate) visitor_triggerable: bool,
}

pub(crate) fn parse(text: &str) -> Result<Doc, Rejection> {
    let lines: Vec<&str> = text.lines().collect();
    if lines
        .first()
        .and_then(|line| line.strip_prefix("# "))
        .is_none_or(|t| t.trim().is_empty())
    {
        return Err(Rejection::malformed("제목이 없음".to_owned()));
    }
    let headings = headings(&lines);
    let header_end = headings.first().map_or(lines.len(), |(index, _)| *index);
    let header = &lines[1..header_end];
    let pattern = backticked(&bullet(header, "식별 패턴")?);
    let logger = backticked(&bullet(header, "로거")?);
    bullet(header, "관련 알림")?;
    let visitor = bullet(header, "방문자 유발")?;
    let confirmed = bullet(header, "확정")?;
    for name in SECTIONS {
        section_is_filled(&lines, &headings, name)?;
    }
    let visitor_triggerable = visitor_flag(&visitor)?;
    check_pattern(&pattern, &logger)?;
    check_confirmed(&confirmed)?;
    Ok(Doc {
        signature: pattern,
        visitor_triggerable,
    })
}

fn headings<'a>(lines: &[&'a str]) -> Vec<(usize, &'a str)> {
    let mut in_fence = false;
    let mut found = Vec::new();
    for (index, line) in lines.iter().enumerate() {
        if line.trim_start().starts_with("```") {
            in_fence = !in_fence;
        } else if !in_fence && let Some(name) = line.strip_prefix("## ") {
            found.push((index, name.trim()));
        }
    }
    found
}

fn bullet(header: &[&str], label: &str) -> Result<String, Rejection> {
    let prefix = format!("- {label}:");
    let mut values = header.iter().filter_map(|line| line.strip_prefix(&prefix));
    let value = values
        .next()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| Rejection::malformed(format!("{label} 항목이 없음")))?;
    if values.next().is_some() {
        return Err(Rejection::malformed(format!("{label} 항목이 중복됨")));
    }
    Ok(value)
}

fn backticked(value: &str) -> String {
    value.trim_matches('`').trim().to_owned()
}

fn section_is_filled(
    lines: &[&str],
    headings: &[(usize, &str)],
    name: &str,
) -> Result<(), Rejection> {
    let Some(position) = headings.iter().position(|(_, heading)| *heading == name) else {
        return Err(Rejection::malformed(format!("{name} 절이 없음")));
    };
    let start = headings[position].0 + 1;
    let end = headings
        .get(position + 1)
        .map_or(lines.len(), |(index, _)| *index);
    if lines[start..end].iter().all(|line| line.trim().is_empty()) {
        return Err(Rejection::malformed(format!("{name} 절이 비어 있음")));
    }
    Ok(())
}

fn visitor_flag(value: &str) -> Result<bool, Rejection> {
    match value {
        "가능" => Ok(true),
        "불가" => Ok(false),
        _ => Err(Rejection::malformed(
            "방문자 유발 항목은 '가능' 또는 '불가'여야 함".to_owned(),
        )),
    }
}

fn last_segment(name: &str) -> &str {
    name.rsplit('.').next().unwrap_or(name)
}

fn check_pattern(pattern: &str, logger: &str) -> Result<(), Rejection> {
    let head = pattern
        .strip_prefix("ERROR ")
        .and_then(|rest| rest.split_once(" : "));
    let Some((token, message)) =
        head.filter(|(token, _)| !token.is_empty() && !token.contains(' '))
    else {
        return Err(Rejection::malformed(
            "식별 패턴이 너무 넓음: 'ERROR <로거> : ' 로 시작하는 오류 줄 앞부분이어야 함"
                .to_owned(),
        ));
    };
    if last_segment(token) != last_segment(logger) {
        return Err(Rejection::malformed(
            "식별 패턴의 로거가 로거 항목과 다름".to_owned(),
        ));
    }
    if !message_is_specific(message) {
        return Err(Rejection::malformed(
            "식별 패턴이 너무 짧음: 메시지에 영문 글자 12자 이상, 3자 이상 단어 2개가 필요함"
                .to_owned(),
        ));
    }
    match fingerprint(pattern) {
        Some(normalized) if normalized.text == pattern => Ok(()),
        _ => Err(Rejection::malformed(
            "식별 패턴이 정규화되어 있지 않음: 숫자와 식별자는 '#'로 쓴다".to_owned(),
        )),
    }
}

fn message_is_specific(message: &str) -> bool {
    let letters = message.chars().filter(|c| c.is_alphabetic()).count();
    let words = message
        .split(|c: char| !c.is_ascii_alphabetic())
        .filter(|word| word.len() >= 3)
        .count();
    letters >= MIN_MESSAGE_LETTERS && words >= 2
}

fn check_confirmed(value: &str) -> Result<(), Rejection> {
    if value == "대기" {
        return Err(Rejection {
            kind: IssueKind::Draft,
            reason: "확정 대기: 정석님 확인 전에는 쓰지 않음".to_owned(),
        });
    }
    let (date, name) = value.split_once(' ').unwrap_or((value, ""));
    if is_calendar_date(date) && !name.trim().is_empty() {
        return Ok(());
    }
    Err(Rejection::malformed(
        "확정 항목은 'YYYY-MM-DD 이름' 형식의 실제 날짜여야 함".to_owned(),
    ))
}

fn is_calendar_date(date: &str) -> bool {
    let parts: Vec<&str> = date.split('-').collect();
    let [year, month, day] = parts[..] else {
        return false;
    };
    let digits = |s: &str, len: usize| s.len() == len && s.bytes().all(|b| b.is_ascii_digit());
    if !(digits(year, 4) && digits(month, 2) && digits(day, 2)) {
        return false;
    }
    let (year, month, day): (u32, u32, u32) = (
        year.parse().unwrap_or(0),
        month.parse().unwrap_or(0),
        day.parse().unwrap_or(0),
    );
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => return false,
    };
    (1..=days).contains(&day)
}
