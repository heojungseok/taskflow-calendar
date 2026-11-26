use std::fs;
use std::io::{self, Write};
use std::path::Path;

use tempfile::NamedTempFile;

fn staged(dir: &Path, bytes: &[u8]) -> io::Result<NamedTempFile> {
    fs::create_dir_all(dir)?;
    let mut temp = NamedTempFile::new_in(dir)?;
    temp.write_all(bytes)?;
    temp.as_file().sync_all()?;
    Ok(temp)
}

pub(crate) fn write(dir: &Path, name: &str, bytes: &[u8]) -> io::Result<()> {
    staged(dir, bytes)?
        .persist(dir.join(name))
        .map_err(|error| error.error)?;
    Ok(())
}

pub(crate) fn create(dir: &Path, name: &str, bytes: &[u8]) -> io::Result<()> {
    staged(dir, bytes)?
        .persist_noclobber(dir.join(name))
        .map_err(|error| error.error)?;
    Ok(())
}
