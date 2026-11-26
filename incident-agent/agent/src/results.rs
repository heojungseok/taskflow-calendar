use std::fs::{self, File};
use std::io::Read as _;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;
use std::thread;
use std::time::Duration;

use contract::ActionResult;
use incident::Event;

const MAX_RESULT_BYTES: u64 = 64 * 1024;

fn is_candidate(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.ends_with(".json") && !name.starts_with('.'))
}

enum Read {
    Parsed(ActionResult),
    Invalid,
    Unreadable,
}

fn read_result(path: &Path) -> Read {
    let Ok(before) = fs::symlink_metadata(path) else {
        return Read::Unreadable;
    };
    if !before.is_file() || before.len() > MAX_RESULT_BYTES {
        return Read::Invalid;
    }
    let Ok(file) = File::open(path) else {
        return Read::Unreadable;
    };
    let Ok(opened) = file.metadata() else {
        return Read::Unreadable;
    };
    if (opened.dev(), opened.ino()) != (before.dev(), before.ino()) {
        return Read::Invalid;
    }
    let mut body = Vec::new();
    if file
        .take(MAX_RESULT_BYTES + 1)
        .read_to_end(&mut body)
        .is_err()
    {
        return Read::Unreadable;
    }
    match serde_json::from_slice(&body) {
        Ok(result) if body.len() as u64 <= MAX_RESULT_BYTES => Read::Parsed(result),
        _ => Read::Invalid,
    }
}

fn move_to(path: &Path, folder: &str) -> bool {
    let (Some(parent), Some(name)) = (path.parent(), path.file_name()) else {
        return false;
    };
    let target = parent.join(folder);
    let moved = fs::create_dir_all(&target).and_then(|()| fs::rename(path, target.join(name)));
    if let Err(error) = &moved {
        eprintln!("result file not moved to {folder}: {:?}", error.kind());
    }
    moved.is_ok()
}

fn handle(path: &Path) -> Option<ActionResult> {
    match read_result(path) {
        Read::Parsed(result) => move_to(path, "processed").then_some(result),
        Read::Invalid => {
            move_to(path, "quarantine");
            None
        }
        Read::Unreadable => {
            eprintln!("result file unreadable, will retry");
            None
        }
    }
}

pub(crate) fn pump(dir: &Path, events: &Sender<Event>) {
    let mut paths: Vec<_> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| is_candidate(path))
        .collect();
    paths.sort();
    for result in paths.iter().filter_map(|path| handle(path)) {
        let _ = events.send(Event::ExecutionResult(result));
    }
    let _ = events.send(Event::Tick);
}

pub(crate) fn spawn(dir: PathBuf, events: Sender<Event>, interval: Duration) {
    thread::spawn(move || {
        loop {
            thread::sleep(interval);
            pump(&dir, &events);
        }
    });
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::sync::mpsc;

    use contract::{Outcome, RequestId};

    use super::*;

    fn write_result(dir: &Path, id: &str) {
        let result = ActionResult::new(
            RequestId::try_from(id.to_owned()).unwrap(),
            Outcome::Executed {},
            2_000,
        );
        fs::write(
            dir.join(format!("{id}.json")),
            serde_json::to_vec(&result).unwrap(),
        )
        .unwrap();
    }

    fn drain(events: &mpsc::Receiver<Event>) -> Vec<Event> {
        events.try_iter().collect()
    }

    #[test]
    fn t37_새_결과는_한_번만_이벤트가_되고_processed로_옮겨진다() {
        let dir = tempfile::tempdir().unwrap();
        let (tx, rx) = mpsc::channel();
        write_result(dir.path(), "req-1");
        pump(dir.path(), &tx);
        let first = drain(&rx);
        assert!(
            matches!(&first[0], Event::ExecutionResult(r) if r.request_id().as_str() == "req-1")
        );
        assert!(matches!(first.last(), Some(Event::Tick)));
        assert!(dir.path().join("processed/req-1.json").exists());
        assert!(!dir.path().join("req-1.json").exists());
        pump(dir.path(), &tx);
        let second = drain(&rx);
        assert_eq!(second.len(), 1);
        assert!(matches!(second[0], Event::Tick));
    }

    #[test]
    fn t37_깨진_파일은_quarantine으로_옮기고_이벤트가_없다() {
        let dir = tempfile::tempdir().unwrap();
        let (tx, rx) = mpsc::channel();
        fs::write(dir.path().join("bad.json"), "not json").unwrap();
        fs::write(dir.path().join(".hidden.json"), "x").unwrap();
        pump(dir.path(), &tx);
        assert!(dir.path().join("quarantine/bad.json").exists());
        assert!(dir.path().join(".hidden.json").exists());
        let events = drain(&rx);
        assert_eq!(events.len(), 1);
        assert!(matches!(events[0], Event::Tick));
    }

    #[test]
    fn t37_폴더가_없어도_tick은_보낸다() {
        let (tx, rx) = mpsc::channel();
        pump(Path::new("/nonexistent/results"), &tx);
        assert!(matches!(drain(&rx).as_slice(), [Event::Tick]));
    }

    #[test]
    fn t37_읽기_오류는_제자리에_두고_다음에_다시_읽는다() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let (tx, rx) = mpsc::channel();
        write_result(dir.path(), "req-1");
        let path = dir.path().join("req-1.json");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o000)).unwrap();
        pump(dir.path(), &tx);
        assert!(path.exists());
        assert!(!dir.path().join("quarantine").exists());
        assert!(matches!(drain(&rx).as_slice(), [Event::Tick]));
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).unwrap();
        pump(dir.path(), &tx);
        assert!(matches!(drain(&rx)[0], Event::ExecutionResult(_)));
    }

    #[test]
    fn t37_너무_크거나_심볼릭_링크인_파일은_quarantine이다() {
        let dir = tempfile::tempdir().unwrap();
        let (tx, rx) = mpsc::channel();
        fs::write(dir.path().join("big.json"), vec![b' '; 64 * 1024 + 1]).unwrap();
        let outside = tempfile::tempdir().unwrap();
        write_result(outside.path(), "req-9");
        let target = outside.path().join("req-9.json");
        std::os::unix::fs::symlink(target, dir.path().join("link.json")).unwrap();
        pump(dir.path(), &tx);
        assert!(dir.path().join("quarantine/big.json").exists());
        assert!(dir.path().join("quarantine/link.json").exists());
        assert!(matches!(drain(&rx).as_slice(), [Event::Tick]));
    }
}
