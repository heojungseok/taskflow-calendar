use crate::logline::{parse_log, replace_quoted};

const SECRET_MIN_LEN: usize = 20;
const SECRET_MIN_DIGITS: usize = 3;
const IDENT_MAX_LEN: usize = 64;
const PLACEHOLDERS: [&str; 4] = ["[본문 ", "[이메일]", "[토큰]", "[한글 "];

pub(crate) fn clean(text: &str) -> String {
    text.chars()
        .map(|c| {
            if c.is_control() || matches!(c, '\u{2028}' | '\u{2029}') {
                ' '
            } else {
                c
            }
        })
        .collect()
}

pub(crate) fn mask_log(text: &str) -> String {
    let cleaned = clean(text);
    match parse_log(&cleaned) {
        Some(entry) => format!(
            "{} {} : {}",
            entry.level.as_str(),
            entry.logger,
            mask_message(entry.rest)
        ),
        None => mask_message(&cleaned),
    }
}

pub(crate) fn mask_message(message: &str) -> String {
    let unquoted = replace_quoted(message, |quote, inner, closed| {
        if closed && is_short_identifier(inner) {
            format!("{quote}{inner}{quote}")
        } else {
            format!("[본문 {}자]", inner.chars().count())
        }
    });
    mask_hangul(&mask_pii(&unquoted))
}

pub(crate) fn mask_pii(text: &str) -> String {
    mask_secrets(&mask_identifying(text))
}

pub(crate) fn mask_identifying(text: &str) -> String {
    let emails = mask_emails(text);
    map_runs(&emails, |c| is_secret_char(c) || c == '.', mask_jwt)
}

pub(crate) fn mask_secrets(text: &str) -> String {
    map_runs(text, is_secret_char, |run| {
        if is_secret(run) {
            "[토큰]".to_owned()
        } else {
            run.to_owned()
        }
    })
}

fn is_short_identifier(text: &str) -> bool {
    let mut chars = text.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && text.chars().count() <= IDENT_MAX_LEN
        && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '$'))
}

fn mask_emails(text: &str) -> String {
    text.split(' ')
        .map(|token| {
            if is_email(token) {
                "[이메일]"
            } else {
                token
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn is_email(token: &str) -> bool {
    let lower = token.to_ascii_lowercase();
    let Some(at) = [lower.find('@'), lower.find("%40")]
        .into_iter()
        .flatten()
        .min()
    else {
        return false;
    };
    let domain_start = if lower[at..].starts_with('@') {
        at + 1
    } else {
        at + 3
    };
    at > 0 && lower[domain_start..].contains('.')
}

fn is_secret_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '_' | '+' | '/' | '=' | '-')
}

fn is_secret(run: &str) -> bool {
    run.chars().count() >= SECRET_MIN_LEN
        && run.chars().any(|c| c.is_ascii_alphabetic())
        && run.chars().filter(char::is_ascii_digit).count() >= SECRET_MIN_DIGITS
}

fn mask_jwt(run: &str) -> String {
    let start = run
        .match_indices("eyJ")
        .map(|(i, _)| i)
        .find(|&i| (i == 0 || run.as_bytes()[i - 1] == b'=') && run[i..].matches('.').count() >= 2);
    match start {
        Some(i) => format!("{}[토큰]", &run[..i]),
        None => run.to_owned(),
    }
}

fn map_runs(text: &str, in_run: fn(char) -> bool, convert: impl Fn(&str) -> String) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    for c in text.chars() {
        if in_run(c) {
            run.push(c);
            continue;
        }
        if !run.is_empty() {
            out.push_str(&convert(&run));
            run.clear();
        }
        out.push(c);
    }
    if !run.is_empty() {
        out.push_str(&convert(&run));
    }
    out
}

fn is_hangul(c: char) -> bool {
    matches!(c, '\u{AC00}'..='\u{D7A3}' | '\u{1100}'..='\u{11FF}' | '\u{3130}'..='\u{318F}'
        | '\u{A960}'..='\u{A97F}' | '\u{D7B0}'..='\u{D7FF}')
}

fn mask_hangul(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(c) = rest.chars().next() {
        if c == '[' && PLACEHOLDERS.iter().any(|p| rest.starts_with(p)) {
            let end = rest.find(']').map_or(rest.len(), |e| e + 1);
            out.push_str(&rest[..end]);
            rest = &rest[end..];
        } else if is_hangul(c) {
            let run = rest.chars().take_while(|&c| is_hangul(c)).count();
            let bytes: usize = rest.chars().take(run).map(char::len_utf8).sum();
            out.push_str(&format!("[한글 {run}자]"));
            rest = &rest[bytes..];
        } else {
            out.push(c);
            rest = &rest[c.len_utf8()..];
        }
    }
    out
}
