use crate::logline::replace_quoted;

const SECRET_MIN_LEN: usize = 20;

pub(crate) fn mask_identifying(text: &str) -> String {
    mask_secrets(&mask_emails(text), is_jwt)
}

pub(crate) fn mask_log(text: &str) -> String {
    match text.split_once(" : ") {
        Some((head, message)) => format!("{head} : {}", mask_message(message)),
        None => mask_message(text),
    }
}

fn mask_message(message: &str) -> String {
    let unquoted = replace_quoted(message, |inner| {
        format!("[본문 {}자]", inner.chars().count())
    });
    mask_secrets(&mask_emails(&unquoted), is_secret)
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
    token
        .split_once('@')
        .is_some_and(|(_, domain)| domain.contains('.'))
}

fn is_secret_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '+' | '/' | '=' | '-')
}

fn is_jwt(run: &str) -> bool {
    run.contains("eyJ")
}

fn is_secret(run: &str) -> bool {
    is_jwt(run)
        || (run.chars().count() >= SECRET_MIN_LEN
            && run.chars().any(|c| c.is_ascii_alphabetic())
            && run.chars().any(|c| c.is_ascii_digit()))
}

fn mask_secrets(text: &str, is_secret: fn(&str) -> bool) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    for c in text.chars() {
        if is_secret_char(c) {
            run.push(c);
            continue;
        }
        flush(&mut run, &mut out, is_secret);
        out.push(c);
    }
    flush(&mut run, &mut out, is_secret);
    out
}

fn flush(run: &mut String, out: &mut String, is_secret: fn(&str) -> bool) {
    out.push_str(if is_secret(run) { "[토큰]" } else { run });
    run.clear();
}
