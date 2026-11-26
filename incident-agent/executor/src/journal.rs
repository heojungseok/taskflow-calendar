use std::fs;
use std::path::Path;

use contract::GitSha;

use crate::Command;

const INFORMATIONAL: [&str; 4] = [
    "no-change",
    "ci-rejected",
    "classification-required",
    "reconciled-classification-required",
];

const ANALYZER_SCOPE: &str = "agent";

struct Row {
    sha: GitSha,
    status: String,
    scope: String,
}

impl Row {
    fn is(&self, status: &str, scope: &str) -> bool {
        self.status == status && self.scope == scope
    }
}

pub(crate) fn restart_allowed(journal: &Path) -> Result<(), String> {
    let rows = read_rows(journal)?;
    match rows.last() {
        Some(row) if matches!(row.status.as_str(), "success" | "rolled-back") => Ok(()),
        _ => Err("deploy in progress or unsettled".to_owned()),
    }
}

pub(crate) fn rollback_command(journal: &Path, target: &GitSha) -> Result<Command, String> {
    let rows = read_rows(journal)?;
    let not_allowed = || "rollback not allowed".to_owned();
    let [earlier @ .., observing, last] = rows.as_slice() else {
        return Err(not_allowed());
    };
    if !last.is("success", "backend") || !observing.is("observing", "backend") {
        return Err(not_allowed());
    }
    if observing.sha != last.sha {
        return Err(not_allowed());
    }
    let allowed = previous_deploy(earlier, &last.sha).ok_or_else(not_allowed)?;
    if &allowed.sha != target {
        return Err(not_allowed());
    }
    Ok(Command::RollbackBackend {
        observed: last.sha.clone(),
        target: allowed.sha.clone(),
    })
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

fn read_rows(journal: &Path) -> Result<Vec<Row>, String> {
    let body = fs::read_to_string(journal).map_err(|_| "deploy journal unreadable".to_owned())?;
    let rows: Result<Vec<Row>, String> = body
        .lines()
        .filter(|line| !line.is_empty())
        .map(parse_row)
        .collect();
    let mut rows = rows?;
    rows.retain(|row| !INFORMATIONAL.contains(&row.status.as_str()) && row.scope != ANALYZER_SCOPE);
    Ok(rows)
}

fn parse_row(line: &str) -> Result<Row, String> {
    let malformed = || "deploy journal malformed".to_owned();
    let fields: Vec<&str> = line.split('\t').collect();
    let [_, sha, status, scope, _, _] = fields[..] else {
        return Err(malformed());
    };
    let sha = GitSha::try_from(sha.to_owned()).map_err(|_| malformed())?;
    Ok(Row {
        sha,
        status: status.to_owned(),
        scope: scope.to_owned(),
    })
}
