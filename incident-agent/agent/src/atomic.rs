use std::fs;
use std::io::{self, Write};
use std::path::Path;

use tempfile::NamedTempFile;

pub(crate) fn write(dir: &Path, name: &str, bytes: &[u8]) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    let mut temp = NamedTempFile::new_in(dir)?;
    temp.write_all(bytes)?;
    temp.persist(dir.join(name)).map_err(|error| error.error)?;
    Ok(())
}
