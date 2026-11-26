use std::io;
use std::path::Path;

use contract::ActionRequest;

use crate::atomic;

pub(crate) fn write(dir: &Path, request: &ActionRequest) -> io::Result<()> {
    let body = serde_json::to_vec(request).map_err(io::Error::other)?;
    atomic::write(
        dir,
        &format!("{}.json", request.request_id().as_str()),
        &body,
    )
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::os::unix::fs::PermissionsExt;

    use contract::{Action, DiscordUserId, IncidentId, RequestId, TotpCode};

    use super::*;

    fn request() -> ActionRequest {
        ActionRequest::new(
            RequestId::try_from("req-1".to_owned()).unwrap(),
            IncidentId::try_from("incident-7".to_owned()).unwrap(),
            Action::RestartBackend {},
            DiscordUserId::try_from("1234567890".to_owned()).unwrap(),
            TotpCode::try_from("042137".to_owned()).unwrap(),
            1_000,
            1_600,
        )
        .unwrap()
    }

    #[test]
    fn t36_id_json으로_0600_원자적_쓰기하고_다시_읽힌다() {
        let dir = tempfile::tempdir().unwrap();
        write(dir.path(), &request()).unwrap();
        let path = dir.path().join("req-1.json");
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let reread: ActionRequest = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(reread, request());
        assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn t36_폴더가_없으면_만든다() {
        let dir = tempfile::tempdir().unwrap();
        let nested = dir.path().join("a/b");
        write(&nested, &request()).unwrap();
        assert!(nested.join("req-1.json").exists());
    }
}
