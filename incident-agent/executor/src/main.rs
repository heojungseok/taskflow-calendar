use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{self, ExitCode};
use std::time::{SystemTime, UNIX_EPOCH};

use contract::Outcome;
use executor::{Clock, Command, CommandRunner, Setup, execute};

const TOTP_SECRET_KEY: &str = "EXECUTOR_TOTP_SECRET";
const MAX_REQUESTS_PER_RUN: usize = 10;

struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_secs())
    }
}

struct SystemCommandRunner {
    docker: PathBuf,
    backend_container: String,
    rollback_program: PathBuf,
}

impl CommandRunner for SystemCommandRunner {
    fn run(&self, command: &Command) -> Result<(), String> {
        let (program, args) = match command {
            Command::RestartBackend { .. } => {
                (&self.docker, ["restart", self.backend_container.as_str()])
            }
            Command::RollbackBackend {
                observed, target, ..
            } => (&self.rollback_program, [observed.as_str(), target.as_str()]),
        };
        let status = process::Command::new(program)
            .args(args)
            .env_remove(TOTP_SECRET_KEY)
            .status()
            .map_err(|error| format!("could not start command: {}", error.kind()))?;
        if status.success() {
            return Ok(());
        }
        Err(format!("command exited with {status}"))
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
        docker: PathBuf::from(required("EXECUTOR_DOCKER")?),
        backend_container: required("EXECUTOR_BACKEND_CONTAINER")?,
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
