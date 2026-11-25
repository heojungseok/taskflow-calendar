use std::fs;
use std::path::Path;

use crate::fingerprint::fingerprint;
use crate::model::{Knowledge, KnownIncident};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KnowledgeIssue {
    pub file: String,
    pub reason: String,
}

const SECTIONS: [&str; 4] = ["증상", "원인", "확인할 것", "조치"];

pub fn load_knowledge(dir: &Path) -> (Knowledge, Vec<KnowledgeIssue>) {
    let mut knowledge = Knowledge::default();
    let mut issues = Vec::new();
    let paths = match markdown_files(dir) {
        Ok(paths) => paths,
        Err(reason) => return (knowledge, vec![issue(dir, reason)]),
    };
    for path in paths {
        let outcome = fs::read_to_string(&path)
            .map_err(|e| format!("파일을 읽을 수 없음: {e}"))
            .and_then(|text| parse(&text));
        match outcome {
            Ok(signature) => knowledge.incidents.push(KnownIncident {
                source: path.to_string_lossy().into_owned(),
                signature,
            }),
            Err(reason) => issues.push(issue(&path, reason)),
        }
    }
    (knowledge, issues)
}

fn issue(path: &Path, reason: String) -> KnowledgeIssue {
    KnowledgeIssue {
        file: path.to_string_lossy().into_owned(),
        reason,
    }
}

fn markdown_files(dir: &Path) -> Result<Vec<std::path::PathBuf>, String> {
    let entries = fs::read_dir(dir).map_err(|e| format!("지식 폴더 없음: {e}"))?;
    let mut paths: Vec<_> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.is_file() && path.extension().is_some_and(|ext| ext == "md"))
        .collect();
    paths.sort();
    Ok(paths)
}

fn parse(text: &str) -> Result<String, String> {
    let first = text.lines().next().unwrap_or("");
    if first
        .strip_prefix("# ")
        .is_none_or(|title| title.trim().is_empty())
    {
        return Err("제목이 없음".to_owned());
    }
    let pattern = backticked(&bullet(text, "식별 패턴")?);
    let logger = backticked(&bullet(text, "로거")?);
    bullet(text, "관련 알림")?;
    let confirmed = bullet(text, "확정")?;
    for name in SECTIONS {
        section_is_filled(text, name)?;
    }
    check_pattern(&pattern, &logger)?;
    check_confirmed(&confirmed)?;
    Ok(pattern)
}

fn bullet(text: &str, label: &str) -> Result<String, String> {
    let prefix = format!("- {label}:");
    text.lines()
        .find_map(|line| line.strip_prefix(&prefix))
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("{label} 항목이 없음"))
}

fn backticked(value: &str) -> String {
    value.trim_matches('`').trim().to_owned()
}

fn section_is_filled(text: &str, name: &str) -> Result<(), String> {
    let heading = format!("## {name}");
    let mut lines = text.lines().skip_while(|line| line.trim() != heading);
    if lines.next().is_none() {
        return Err(format!("{name} 절이 없음"));
    }
    let body_is_empty = lines
        .take_while(|line| !line.starts_with("## "))
        .all(|line| line.trim().is_empty());
    if body_is_empty {
        return Err(format!("{name} 절이 비어 있음"));
    }
    Ok(())
}

fn last_segment(name: &str) -> &str {
    name.rsplit('.').next().unwrap_or(name)
}

fn check_pattern(pattern: &str, logger: &str) -> Result<(), String> {
    let head = pattern
        .strip_prefix("ERROR ")
        .and_then(|rest| rest.split_once(" : "))
        .map(|(token, _)| token);
    let Some(token) = head.filter(|token| !token.is_empty() && !token.contains(' ')) else {
        return Err(
            "식별 패턴이 너무 넓음: 'ERROR <로거> : ' 로 시작하는 오류 줄 앞부분이어야 함"
                .to_owned(),
        );
    };
    if last_segment(token) != last_segment(logger) {
        return Err("식별 패턴의 로거가 로거 항목과 다름".to_owned());
    }
    match fingerprint(pattern) {
        Some(normalized) if normalized.text == pattern => Ok(()),
        _ => Err("식별 패턴이 정규화되어 있지 않음: 숫자와 식별자는 '#'로 쓴다".to_owned()),
    }
}

fn check_confirmed(value: &str) -> Result<(), String> {
    let (date, name) = value.split_once(' ').unwrap_or((value, ""));
    let bytes = date.as_bytes();
    let is_date = bytes.len() == 10
        && bytes.iter().enumerate().all(|(i, b)| {
            if matches!(i, 4 | 7) {
                *b == b'-'
            } else {
                b.is_ascii_digit()
            }
        });
    if is_date && !name.trim().is_empty() {
        return Ok(());
    }
    Err("확정 대기: 정석님 확인 전에는 쓰지 않음".to_owned())
}
