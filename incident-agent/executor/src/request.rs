use std::fs::OpenOptions;
use std::io::Read;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

use contract::{ActionRequest, RequestId};

const MAX_REQUEST_BYTES: u64 = 64 * 1024;

pub(crate) fn load(path: &Path, id: &RequestId) -> Result<ActionRequest, String> {
    let invalid = || "invalid request".to_owned();
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
        .map_err(|_| invalid())?;
    if !file.metadata().is_ok_and(|metadata| metadata.is_file()) {
        return Err(invalid());
    }
    let mut body = Vec::new();
    file.take(MAX_REQUEST_BYTES + 1)
        .read_to_end(&mut body)
        .map_err(|_| invalid())?;
    if body.len() as u64 > MAX_REQUEST_BYTES {
        return Err(invalid());
    }
    let request: ActionRequest = serde_json::from_slice(&body).map_err(|_| invalid())?;
    if request.request_id() != id {
        return Err(invalid());
    }
    Ok(request)
}
