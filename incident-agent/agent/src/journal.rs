use std::fs;
use std::path::Path;

use contract::GitSha;
use incident::{Deploy, DeployScope};

use crate::rfc3339;

const INFORMATIONAL: [&str; 4] = [
    "no-change",
    "ci-rejected",
    "classification-required",
    "reconciled-classification-required",
];

struct Row {
    time: String,
    sha: GitSha,
    status: String,
    scope: String,
}

impl Row {
    fn is(&self, status: &str, scope: &str) -> bool {
        self.status == status && self.scope == scope
    }
}

fn parse_row(line: &str) -> Option<Row> {
    let fields: Vec<&str> = line.split('\t').collect();
    let [time, sha, status, scope, _, _] = fields[..] else {
        return None;
    };
    Some(Row {
        time: time.to_owned(),
        sha: GitSha::try_from(sha.to_owned()).ok()?,
        status: status.to_owned(),
        scope: scope.to_owned(),
    })
}

fn read_rows(path: &Path) -> Option<Vec<Row>> {
    let body = fs::read_to_string(path).ok()?;
    let mut rows = Vec::new();
    for line in body.lines().filter(|line| !line.is_empty()) {
        rows.push(parse_row(line)?);
    }
    rows.retain(|row| !INFORMATIONAL.contains(&row.status.as_str()));
    Some(rows)
}

fn previous_deploy<'a>(earlier: &'a [Row], observed: &GitSha) -> Option<&'a Row> {
    let mut rows = earlier.iter().rev().skip_while(|row| {
        row.sha == *observed
            && matches!(row.status.as_str(), "deployment-running" | "retry-runtime")
    });
    let found = rows.find(|row| row.scope != "frontend")?;
    let deployable = matches!(found.scope.as_str(), "backend" | "full");
    (found.status == "success" && deployable && found.sha != *observed).then_some(found)
}

fn rollback_target(rows: &[Row]) -> Option<GitSha> {
    let [earlier @ .., observing, last] = rows else {
        return None;
    };
    let settled = last.is("success", "backend") && observing.is("observing", "backend");
    if !settled || observing.sha != last.sha {
        return None;
    }
    previous_deploy(earlier, &last.sha).map(|row| row.sha.clone())
}

fn scope_of(text: &str) -> Option<DeployScope> {
    match text {
        "frontend" => Some(DeployScope::Frontend),
        "backend" => Some(DeployScope::Backend),
        "full" => Some(DeployScope::Full),
        "migration" => Some(DeployScope::Migration),
        _ => None,
    }
}

pub(crate) fn last_deploy(path: &Path) -> Option<Deploy> {
    let rows = read_rows(path)?;
    let (last, scope) = rows
        .iter()
        .rev()
        .filter(|row| row.status == "success")
        .find_map(|row| Some((row, scope_of(&row.scope)?)))?;
    let at_unix = rfc3339::parse_unix(&last.time)?;
    let rollback = if scope == DeployScope::Backend {
        rollback_target(&rows)
    } else {
        None
    };
    let (scope, previous_sha) = match (scope, rollback) {
        (DeployScope::Backend, Some(previous)) => (DeployScope::Backend, previous),
        (DeployScope::Backend, None) => (DeployScope::Full, last.sha.clone()),
        (other, _) => (other, last.sha.clone()),
    };
    Some(Deploy {
        sha: last.sha.clone(),
        previous_sha,
        scope,
        at_unix,
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
    const F: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn deploy_from(rows: &[String]) -> Option<Deploy> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("journal.tsv");
        fs::write(&path, rows.join("\n") + "\n").unwrap();
        last_deploy(&path)
    }

    fn row(time: &str, sha: &str, state: &str, scope: &str) -> String {
        format!("{time}\t{sha}\t{state}\t{scope}\t42\thash")
    }

    fn backend_release(sha: &str, hour: u32) -> Vec<String> {
        let at = format!("2025-11-26T{hour:02}:00:00Z");
        vec![
            row(&at, sha, "deployment-running", "backend"),
            row(&at, sha, "observing", "backend"),
            row(&at, sha, "success", "backend"),
        ]
    }

    fn after(mut rows: Vec<String>, more: Vec<String>) -> Vec<String> {
        rows.extend(more);
        rows
    }

    #[test]
    fn t35_실행기가_받아들이는_backend_배포는_되돌릴_sha를_담는다() {
        let rows = after(
            vec![row("2025-11-26T00:00:00Z", A, "success", "full")],
            backend_release(B, 1),
        );
        let deploy = deploy_from(&rows).unwrap();
        assert_eq!(deploy.sha.as_str(), B);
        assert_eq!(deploy.previous_sha.as_str(), A);
        assert_eq!(deploy.scope, DeployScope::Backend);
        assert_eq!(deploy.at_unix, 1_764_118_800);
    }

    #[test]
    fn t35_사이의_frontend_배포는_건너뛰고_정보_행은_무시한다() {
        let mut rows = vec![
            row("2025-11-26T00:00:00Z", A, "success", "backend"),
            row("2025-11-26T00:30:00Z", F, "success", "frontend"),
        ];
        rows.extend(backend_release(B, 1));
        rows.push(row("2025-11-26T02:00:00Z", B, "no-change", "none"));
        let deploy = deploy_from(&rows).unwrap();
        assert_eq!(deploy.previous_sha.as_str(), A);
        assert_eq!(deploy.scope, DeployScope::Backend);
    }

    #[test]
    fn t35_앞이_migration이거나_성공이_아니면_되돌릴_수_없는_범위로_낸다() {
        for before in [
            row("2025-11-26T00:00:00Z", A, "success", "migration"),
            row("2025-11-26T00:00:00Z", A, "approval-failed", "backend"),
            row("2025-11-26T00:00:00Z", A, "success", "none"),
        ] {
            let deploy = deploy_from(&after(vec![before], backend_release(B, 1))).unwrap();
            assert_ne!(deploy.scope, DeployScope::Backend);
        }
    }

    #[test]
    fn t35_앞선_배포가_없거나_같은_sha뿐이면_되돌릴_수_없는_범위로_낸다() {
        let deploy = deploy_from(&backend_release(B, 1)).unwrap();
        assert_ne!(deploy.scope, DeployScope::Backend);
        let same = after(
            vec![row("2025-11-26T00:00:00Z", B, "success", "backend")],
            backend_release(B, 1),
        );
        assert_ne!(deploy_from(&same).unwrap().scope, DeployScope::Backend);
    }

    #[test]
    fn t35_마지막_행이_성공이_아니면_되돌릴_수_없는_범위로_낸다() {
        let mut rows = after(
            vec![row("2025-11-26T00:00:00Z", A, "success", "full")],
            backend_release(B, 1),
        );
        rows.push(row(
            "2025-11-26T02:00:00Z",
            C,
            "deployment-running",
            "backend",
        ));
        assert_ne!(deploy_from(&rows).unwrap().scope, DeployScope::Backend);
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
            assert_eq!(deploy.sha.as_str(), B);
        }
        let deploy = deploy_from(&[
            row("2025-11-26T00:00:00Z", A, "success", "full"),
            row("2025-11-26T02:00:00Z", C, "success", "none"),
            row("2025-11-26T03:00:00Z", C, "success", "unknown"),
        ])
        .unwrap();
        assert_eq!(deploy.sha.as_str(), A);
    }

    #[test]
    fn t35_행이_없거나_형식이_틀리면_none이다() {
        assert!(deploy_from(&[]).is_none());
        assert!(deploy_from(&["garbage line".to_owned()]).is_none());
        let bad_sha = deploy_from(&[
            row("2025-11-26T00:00:00Z", A, "success", "full"),
            row("2025-11-26T01:00:00Z", "xyz", "success", "full"),
        ]);
        assert!(bad_sha.is_none());
        let extra_column = format!(
            "{}\textra",
            row("2025-11-26T00:00:00Z", A, "success", "full")
        );
        assert!(deploy_from(&[extra_column]).is_none());
        assert!(last_deploy(Path::new("/nonexistent/journal.tsv")).is_none());
    }
}
