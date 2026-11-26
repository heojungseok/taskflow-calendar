use std::env;
use std::fs;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{self, ExitCode};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use contract::Outcome;
use executor::{Clock, Command, CommandRunner, Setup, execute};

const TOTP_SECRET_KEY: &str = "EXECUTOR_TOTP_SECRET";
const MAX_REQUESTS_PER_RUN: usize = 10;
const RESTART_TIMEOUT: Duration = Duration::from_secs(120);
const ROLLBACK_TIMEOUT: Duration = Duration::from_secs(900);
const POLL_INTERVAL: Duration = Duration::from_millis(200);

struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_secs())
    }
}

struct SystemCommandRunner {
    restart_program: PathBuf,
    rollback_program: PathBuf,
}

impl CommandRunner for SystemCommandRunner {
    fn run(&self, command: &Command) -> Result<(), String> {
        match command {
            Command::RestartBackend { .. } => {
                run_with_timeout(&self.restart_program, &[], RESTART_TIMEOUT)
            }
            Command::RollbackBackend {
                observed, target, ..
            } => run_with_timeout(
                &self.rollback_program,
                &[observed.as_str(), target.as_str()],
                ROLLBACK_TIMEOUT,
            ),
        }
    }
}

fn run_with_timeout(program: &Path, args: &[&str], timeout: Duration) -> Result<(), String> {
    let mut child = process::Command::new(program)
        .args(args)
        .env_remove(TOTP_SECRET_KEY)
        .process_group(0)
        .spawn()
        .map_err(|error| format!("could not start command: {}", error.kind()))?;
    let deadline = Instant::now() + timeout;
    loop {
        let waited = child
            .try_wait()
            .map_err(|error| format!("could not wait for command: {}", error.kind()))?;
        if let Some(status) = waited {
            return if status.success() {
                Ok(())
            } else {
                Err(format!("command exited with {status}"))
            };
        }
        if Instant::now() >= deadline {
            let group = format!("-{}", child.id());
            let _ = process::Command::new("/bin/kill")
                .env_clear()
                .args(["-KILL", "--", &group])
                .status();
            let _ = child.wait();
            return Err(format!("command timed out after {}s", timeout.as_secs()));
        }
        thread::sleep(POLL_INTERVAL);
    }
}

fn required(key: &str) -> Result<String, String> {
    env::var(key).map_err(|_| format!("{key} is not set"))
}

fn outcome_name(outcome: &Outcome) -> &'static str {
    match outcome {
        Outcome::Executed {} => "executed",
        Outcome::Rejected { .. } => "rejected",
        Outcome::Failed { .. } => "failed",
    }
}

fn pending_requests(requests_dir: &Path) -> Result<Vec<PathBuf>, String> {
    let entries = fs::read_dir(requests_dir)
        .map_err(|error| format!("could not read requests dir: {}", error.kind()))?;
    let mut requests: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.ends_with(".json") && !name.starts_with('.'))
        })
        .collect();
    requests.sort();
    requests.truncate(MAX_REQUESTS_PER_RUN);
    Ok(requests)
}

fn quarantine(request: &Path, quarantine_dir: &Path) {
    let name = request.file_name().unwrap_or_default();
    let moved = fs::create_dir_all(quarantine_dir)
        .and_then(|()| fs::rename(request, quarantine_dir.join(name)));
    if let Err(error) = moved {
        eprintln!("could not quarantine {:?}: {}", request, error.kind());
    }
}

fn handle(
    request: &Path,
    setup: &Setup,
    runner: &SystemCommandRunner,
    quarantine_dir: &Path,
) -> bool {
    match execute(request, setup, runner, &SystemClock) {
        Ok(result) => {
            println!(
                "request {:?} {}",
                result.request_id().as_str(),
                outcome_name(result.outcome())
            );
            if fs::remove_file(request).is_err() {
                quarantine(request, quarantine_dir);
            }
            true
        }
        Err(error) => {
            eprintln!("request {:?} not handled: {}", request, error.kind());
            quarantine(request, quarantine_dir);
            false
        }
    }
}

fn run() -> Result<bool, String> {
    let requests_dir = PathBuf::from(required("EXECUTOR_REQUESTS_DIR")?);
    let state_dir = PathBuf::from(required("EXECUTOR_STATE_DIR")?);
    let setup = Setup::new(
        PathBuf::from(required("EXECUTOR_RESULTS_DIR")?),
        state_dir.clone(),
        PathBuf::from(required("EXECUTOR_DEPLOY_STATE_DIR")?),
        &required(TOTP_SECRET_KEY)?,
    )?;
    let runner = SystemCommandRunner {
        restart_program: PathBuf::from(required("EXECUTOR_RESTART_PROGRAM")?),
        rollback_program: PathBuf::from(required("EXECUTOR_ROLLBACK_PROGRAM")?),
    };
    let quarantine = state_dir.join("unhandled");
    let mut all_handled = true;
    for request in pending_requests(&requests_dir)? {
        all_handled &= handle(&request, &setup, &runner, &quarantine);
    }
    Ok(all_handled)
}

fn main() -> ExitCode {
    match run() {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;
    use std::time::{Duration, Instant};

    use super::run_with_timeout;

    fn alive(pid: &str) -> bool {
        std::process::Command::new("/bin/kill")
            .args(["-0", pid])
            .status()
            .is_ok_and(|status| status.success())
    }

    #[test]
    fn command_that_outlives_its_timeout_is_stopped_with_its_children() {
        let dir = tempfile::tempdir().unwrap();
        let pid_file = dir.path().join("child.pid");
        let script = format!("sleep 30 & echo $! > {}; wait", pid_file.display());
        let started = Instant::now();

        let result = run_with_timeout(
            Path::new("/bin/sh"),
            &["-c", &script],
            Duration::from_secs(1),
        );

        assert!(result.unwrap_err().contains("timed out"));
        assert!(started.elapsed() < Duration::from_secs(5));
        let child = std::fs::read_to_string(&pid_file).unwrap();
        std::thread::sleep(Duration::from_millis(300));
        assert!(!alive(child.trim()));
    }

    #[test]
    fn command_that_finishes_in_time_reports_its_exit_status() {
        let ok = run_with_timeout(
            Path::new("/bin/sh"),
            &["-c", "exit 0"],
            Duration::from_secs(5),
        );
        let failed = run_with_timeout(
            Path::new("/bin/sh"),
            &["-c", "exit 3"],
            Duration::from_secs(5),
        );

        assert_eq!(ok, Ok(()));
        assert!(failed.unwrap_err().contains("exit"));
    }
}
