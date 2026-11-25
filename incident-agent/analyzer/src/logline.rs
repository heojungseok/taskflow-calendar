pub(crate) struct ErrorLine<'a> {
    pub(crate) logger: &'a str,
    pub(crate) rest: &'a str,
}

pub(crate) fn parse_error(text: &str) -> Option<ErrorLine<'_>> {
    let mut line = text.trim_start();
    if let Some((tag, after)) = line.split_once(' ')
        && tag.ends_with(':')
    {
        line = after.trim_start();
    }
    let body = line.strip_prefix("ERROR ")?;
    let (head, rest) = body.split_once(" : ")?;
    let logger = head.split_whitespace().next()?;
    Some(ErrorLine { logger, rest })
}

pub(crate) fn replace_quoted(text: &str, replacement: impl Fn(&str) -> String) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find(['"', '\'']) {
        let quote = &rest[start..=start];
        let after = &rest[start + 1..];
        let Some(end) = after.find(quote) else {
            break;
        };
        out.push_str(&rest[..start]);
        out.push_str(&replacement(&after[..end]));
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    out
}
