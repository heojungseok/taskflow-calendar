use std::fs::{self, OpenOptions};
use std::io::{self, Write};

use contract::{ActionResult, RequestId};
use tempfile::NamedTempFile;

use crate::Setup;

pub(crate) fn claim(setup: &Setup, id: &RequestId) -> io::Result<bool> {
    fs::create_dir_all(&setup.used_dir)?;
    let marker = setup.used_dir.join(id.as_str());
    match OpenOptions::new().write(true).create_new(true).open(marker) {
        Ok(_) => Ok(true),
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => Ok(false),
        Err(error) => Err(error),
    }
}

pub(crate) fn record(setup: &Setup, result: &ActionResult) -> io::Result<()> {
    let body = serde_json::to_vec(result).map_err(io::Error::other)?;
    let id = result.request_id().as_str();
    fs::write(setup.used_dir.join(id), &body)?;
    let mut temp = NamedTempFile::new_in(&setup.results_dir)?;
    temp.write_all(&body)?;
    temp.persist(setup.results_dir.join(format!("{id}.json")))
        .map_err(|error| error.error)?;
    Ok(())
}
