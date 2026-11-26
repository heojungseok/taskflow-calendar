use std::fs;
use std::path::{Path, PathBuf};

use crate::knowledge_doc::{Rejection, parse};
use crate::model::{Knowledge, KnownIncident};

const MAX_FILE_BYTES: u64 = 64 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IssueKind {
    Draft,
    Malformed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KnowledgeIssue {
    pub file: String,
    pub kind: IssueKind,
    pub reason: String,
}

pub fn load_knowledge(dir: &Path) -> (Knowledge, Vec<KnowledgeIssue>) {
    let mut accepted: Vec<KnownIncident> = Vec::new();
    let mut issues = Vec::new();
    let paths = match markdown_files(dir) {
        Ok(paths) => paths,
        Err(rejection) => return (Knowledge::default(), vec![issue(dir, rejection)]),
    };
    for path in paths {
        match read_incident(&path, &accepted) {
            Ok(incident) => accepted.push(incident),
            Err(rejection) => issues.push(issue(&path, rejection)),
        }
    }
    (Knowledge::from_verified(accepted), issues)
}

fn issue(path: &Path, rejection: Rejection) -> KnowledgeIssue {
    KnowledgeIssue {
        file: path.to_string_lossy().into_owned(),
        kind: rejection.kind,
        reason: rejection.reason,
    }
}

fn markdown_files(dir: &Path) -> Result<Vec<PathBuf>, Rejection> {
    let entries =
        fs::read_dir(dir).map_err(|e| Rejection::malformed(format!("지식 폴더 없음: {e}")))?;
    let mut paths: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "md"))
        .filter(|path| fs::symlink_metadata(path).is_ok_and(|meta| meta.is_file()))
        .collect();
    paths.sort();
    Ok(paths)
}

fn read_incident(path: &Path, accepted: &[KnownIncident]) -> Result<KnownIncident, Rejection> {
    let size = fs::metadata(path).map_or(0, |meta| meta.len());
    if size > MAX_FILE_BYTES {
        return Err(Rejection::malformed("파일이 64KiB를 넘음".to_owned()));
    }
    let text = fs::read_to_string(path)
        .map_err(|e| Rejection::malformed(format!("파일을 읽을 수 없음: {e}")))?;
    let doc = parse(text.strip_prefix('\u{feff}').unwrap_or(&text))?;
    if let Some(other) = accepted
        .iter()
        .find(|known| overlaps(known.signature(), &doc.signature))
    {
        return Err(Rejection::malformed(format!(
            "식별 패턴이 {}와 겹침",
            other.source()
        )));
    }
    let source = path
        .file_name()
        .map_or_else(String::new, |name| name.to_string_lossy().into_owned());
    Ok(KnownIncident::new(
        source,
        doc.signature,
        doc.visitor_triggerable,
    ))
}

fn overlaps(a: &str, b: &str) -> bool {
    a.starts_with(b) || b.starts_with(a)
}
