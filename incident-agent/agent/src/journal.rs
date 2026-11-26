use std::fs;
use std::path::Path;

use contract::GitSha;
use incident::{Deploy, DeployScope};

use crate::rfc3339;

struct Success {
    sha: GitSha,
    scope: DeployScope,
    at_unix: u64,
}

enum Row {
    Success(Success),
    Other,
}

fn parse_scope(text: &str) -> Option<Option<DeployScope>> {
    match text {
        "frontend" => Some(Some(DeployScope::Frontend)),
        "backend" => Some(Some(DeployScope::Backend)),
        "full" => Some(Some(DeployScope::Full)),
        "migration" => Some(Some(DeployScope::Migration)),
        "none" | "unknown" => Some(None),
        _ => None,
    }
}

fn parse_row(line: &str) -> Option<Row> {
    let mut columns = line.split('\t');
    let (time, sha, state, scope) = (
        columns.next()?,
        columns.next()?,
        columns.next()?,
        columns.next()?,
    );
    if state != "success" {
        return Some(Row::Other);
    }
    let Some(scope) = parse_scope(scope)? else {
        return Some(Row::Other);
    };
    Some(Row::Success(Success {
        sha: GitSha::try_from(sha.to_owned()).ok()?,
        scope,
        at_unix: rfc3339::parse_unix(time)?,
    }))
}

pub(crate) fn last_deploy(path: &Path) -> Option<Deploy> {
    let text = fs::read_to_string(path).ok()?;
    let mut successes = Vec::new();
    for line in text.lines().filter(|line| !line.trim().is_empty()) {
        if let Row::Success(success) = parse_row(line)? {
            successes.push(success);
        }
    }
    let last = successes.pop()?;
    let previous = successes.pop()?;
    Some(Deploy {
        sha: last.sha,
        previous_sha: previous.sha,
        scope: last.scope,
        at_unix: last.at_unix,
    })
}

#[cfg(test)]
mod tests {
    use std::fs;

    use incident::DeployScope;

    use super::*;

    const A: &str = "1f2a9320000000000000000000000000000000ab";
    const B: &str = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";
    const C: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    fn deploy_from(rows: &[String]) -> Option<Deploy> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("journal.tsv");
        fs::write(&path, rows.join("\n") + "\n").unwrap();
        last_deploy(&path)
    }

    fn row(time: &str, sha: &str, state: &str, scope: &str) -> String {
        format!("{time}\t{sha}\t{state}\t{scope}\t42\thash")
    }

    #[test]
    fn t35_마지막_success와_그_앞_success의_sha를_고른다() {
        let deploy = deploy_from(&[
            row("2025-11-26T00:00:00Z", A, "success", "full"),
            row("2025-11-26T01:00:00Z", B, "success", "backend"),
            row("2025-11-26T02:00:00Z", B, "no-change", "none"),
            row("2025-11-26T03:00:00Z", C, "approval-failed", "migration"),
        ])
        .unwrap();
        assert_eq!(deploy.sha.as_str(), B);
        assert_eq!(deploy.previous_sha.as_str(), A);
        assert_eq!(deploy.scope, DeployScope::Backend);
        assert_eq!(deploy.at_unix, 1_764_118_800);
    }

    #[test]
    fn t35_범위_매핑과_none_unknown_행_무시() {
        for (name, scope) in [
            ("frontend", DeployScope::Frontend),
            ("full", DeployScope::Full),
            ("migration", DeployScope::Migration),
        ] {
            let deploy = deploy_from(&[
                row("2025-11-26T00:00:00Z", A, "success", "full"),
                row("2025-11-26T01:00:00Z", B, "success", name),
            ])
            .unwrap();
            assert_eq!(deploy.scope, scope);
        }
        let deploy = deploy_from(&[
            row("2025-11-26T00:00:00Z", A, "success", "full"),
            row("2025-11-26T01:00:00Z", B, "success", "backend"),
            row("2025-11-26T02:00:00Z", C, "success", "none"),
            row("2025-11-26T03:00:00Z", C, "success", "unknown"),
        ])
        .unwrap();
        assert_eq!(deploy.sha.as_str(), B);
    }

    #[test]
    fn t35_행이_없거나_하나뿐이거나_형식이_틀리면_none이다() {
        assert!(deploy_from(&[]).is_none());
        assert!(deploy_from(&[row("2025-11-26T00:00:00Z", A, "success", "full")]).is_none());
        assert!(deploy_from(&["garbage line".to_owned()]).is_none());
        let bad_sha = deploy_from(&[
            row("2025-11-26T00:00:00Z", A, "success", "full"),
            row("2025-11-26T01:00:00Z", "xyz", "success", "full"),
        ]);
        assert!(bad_sha.is_none());
        assert!(last_deploy(Path::new("/nonexistent/journal.tsv")).is_none());
    }
}
