use std::fs::{self, OpenOptions};
use std::io::{self, Write};

use contract::GitSha;

use crate::Setup;

pub(crate) fn contains(setup: &Setup, sha: &GitSha) -> Result<bool, String> {
    let body = match fs::read_to_string(&setup.rolled_back_file) {
        Ok(body) => body,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(_) => return Err("rollback memory unreadable".to_owned()),
    };
    for line in body.lines() {
        let known = GitSha::try_from(line.to_owned())
            .map_err(|_| "rollback memory malformed".to_owned())?;
        if &known == sha {
            return Ok(true);
        }
    }
    Ok(false)
}

pub(crate) fn remember(setup: &Setup, sha: &GitSha) -> io::Result<()> {
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&setup.rolled_back_file)?;
    writeln!(file, "{}", sha.as_str())?;
    file.sync_all()
}
