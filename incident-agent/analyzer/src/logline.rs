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
