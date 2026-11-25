use std::path::Path;

use crate::model::{Evidence, Suspect};

pub(crate) fn related_commit(evidence: &Evidence, error_fingerprint: &str) -> Option<Suspect> {
    let class = class_name(error_fingerprint)?;
    evidence
        .commits
        .iter()
        .find(|commit| commit.files.iter().any(|file| stem(file) == Some(class)))
        .map(|commit| Suspect::RelatedCommit {
            sha: commit.sha.clone(),
        })
}

fn class_name(fingerprint: &str) -> Option<&str> {
    let logger = fingerprint
        .strip_prefix("ERROR")?
        .split_whitespace()
        .next()?;
    logger.rsplit('.').next()
}

fn stem(file: &str) -> Option<&str> {
    Path::new(file).file_stem()?.to_str()
}
