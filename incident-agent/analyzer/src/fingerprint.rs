use crate::logline::{parse_error, replace_quoted};
use crate::mask::mask_identifying;

pub(crate) struct Fingerprint {
    pub(crate) text: String,
    pub(crate) logger: String,
}

pub(crate) fn fingerprint(log_text: &str) -> Option<Fingerprint> {
    let line = parse_error(log_text)?;
    let rest = cut_at(cut_at(line.rest, " | "), " traceId=");
    let normalized = mask_ids(&mask_identifying(&replace_quoted(rest, |_| {
        "\"*\"".to_owned()
    })));
    Some(Fingerprint {
        text: format!("ERROR {} : {}", line.logger, normalized.trim()),
        logger: line.logger.to_owned(),
    })
}

fn cut_at<'a>(text: &'a str, marker: &str) -> &'a str {
    text.split_once(marker).map_or(text, |(head, _)| head)
}

fn is_id_char(c: char) -> bool {
    c.is_ascii_digit() || matches!(c, 'a'..='f' | '-')
}

fn mask_ids(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut run = String::new();
    for c in text.chars() {
        if is_id_char(c) {
            run.push(c);
            continue;
        }
        flush_run(&mut run, &mut out);
        out.push(c);
    }
    flush_run(&mut run, &mut out);
    out
}

fn flush_run(run: &mut String, out: &mut String) {
    if run.chars().any(|c| c.is_ascii_digit()) {
        out.push('#');
    } else {
        out.push_str(run);
    }
    run.clear();
}
