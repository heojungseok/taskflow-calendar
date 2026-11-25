use std::path::Path;

use crate::model::{Commit, ErrorGroup, Evidence, Suspect};

pub(crate) fn related_commit(evidence: &Evidence, new_error: &Suspect) -> Option<Suspect> {
    let Suspect::NewError {
        first,
        most_frequent,
    } = new_error
    else {
        return None;
    };
    [Some(first), most_frequent.as_ref()]
        .into_iter()
        .flatten()
        .find_map(|group| find_commit(&evidence.commits, group))
        .map(|commit| Suspect::RelatedCommit {
            sha: commit.sha.clone(),
        })
}

fn find_commit<'a>(commits: &'a [Commit], group: &ErrorGroup) -> Option<&'a Commit> {
    let class = group.logger.rsplit('.').next()?;
    commits
        .iter()
        .find(|commit| commit.files.iter().any(|file| stem(file) == Some(class)))
}

fn stem(file: &str) -> Option<&str> {
    Path::new(file).file_stem()?.to_str()
}
