#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Level {
    Error,
    Warn,
}

impl Level {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Error => "ERROR",
            Self::Warn => "WARN",
        }
    }
}

pub(crate) struct LogEntry<'a> {
    pub(crate) level: Level,
    pub(crate) logger: &'a str,
    pub(crate) rest: &'a str,
}

pub(crate) fn parse_log(text: &str) -> Option<LogEntry<'_>> {
    let mut line = text.trim_start();
    if let Some((tag, after)) = line.split_once(' ')
        && tag.ends_with(':')
    {
        line = after.trim_start();
    }
    let (level, after) = [Level::Error, Level::Warn]
        .into_iter()
        .find_map(|level| Some((level, line.strip_prefix(level.as_str())?)))?;
    if !after.starts_with(' ') {
        return None;
    }
    let (head, rest) = after.trim_start().split_once(" : ")?;
    let logger = head.split_whitespace().next()?;
    Some(LogEntry {
        level,
        logger,
        rest,
    })
}

pub(crate) fn parse_error(text: &str) -> Option<LogEntry<'_>> {
    parse_log(text).filter(|entry| entry.level == Level::Error)
}

pub(crate) fn replace_quoted(
    text: &str,
    replacement: impl Fn(char, &str, bool) -> String,
) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        if !opens_quote(&chars, i) {
            out.push(chars[i]);
            i += 1;
            continue;
        }
        let (end, closed) = find_close(&chars, i);
        let inner: String = chars[i + 1..end].iter().collect();
        out.push_str(&replacement(chars[i], &inner, closed));
        i = if closed { end + 1 } else { end };
    }
    out
}

fn opens_quote(chars: &[char], i: usize) -> bool {
    match chars[i] {
        '"' => true,
        '\'' => i == 0 || !chars[i - 1].is_alphanumeric(),
        _ => false,
    }
}

fn find_close(chars: &[char], open: usize) -> (usize, bool) {
    let quote = chars[open];
    let mut j = open + 1;
    while j < chars.len() {
        if chars[j] == '\\' {
            j += 2;
            continue;
        }
        if chars[j] == quote && !is_inner_apostrophe(chars, j) {
            return (j, true);
        }
        j += 1;
    }
    (chars.len(), false)
}

fn is_inner_apostrophe(chars: &[char], j: usize) -> bool {
    chars[j] == '\''
        && j > 0
        && chars[j - 1].is_alphanumeric()
        && chars.get(j + 1).is_some_and(|c| c.is_alphanumeric())
}
