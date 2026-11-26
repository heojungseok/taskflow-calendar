use std::fs;
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

fn read_result(path: &Path) -> Option<ActionResult> {
    let metadata = fs::symlink_metadata(path).ok()?;
    if !metadata.is_file() || metadata.len() > MAX_RESULT_BYTES {
        return None;
    }
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

fn move_to(path: &Path, folder: &str) -> bool {
    let Some(parent) = path.parent() else {
        return false;
    };
    let Some(name) = path.file_name() else {
        return false;
    };
    let target = parent.join(folder);
    fs::create_dir_all(&target).is_ok() && fs::rename(path, target.join(name)).is_ok()
}

fn handle(path: &Path) -> Option<ActionResult> {
    match read_result(path) {
        Some(result) => move_to(path, "processed").then_some(result),
        None => {
            move_to(path, "quarantine");
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
}
