use analyzer::Commit;
use incident::Deploy;
use serde::Deserialize;

use crate::evidence::Github;
use crate::http::{Failure, Http};

#[derive(Deserialize)]
struct Compare {
    files: Vec<File>,
}

#[derive(Deserialize)]
struct File {
    filename: String,
}

pub(crate) fn changed_files(
    http: &Http,
    github: &Github,
    deploy: &Deploy,
) -> Result<Commit, Failure> {
    let url = format!(
        "{}/repos/{}/compare/{}...{}",
        github.api_base,
        github.repo,
        deploy.previous_sha.as_str(),
        deploy.sha.as_str()
    );
    let body = http.get(&url, &[])?;
    let compare: Compare = serde_json::from_str(&body).map_err(|_| Failure::Format)?;
    Ok(Commit {
        sha: deploy.sha.as_str().to_owned(),
        files: compare
            .files
            .into_iter()
            .map(|file| file.filename)
            .collect(),
    })
}
