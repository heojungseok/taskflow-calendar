use std::fs::{self, OpenOptions};
use std::io;
use std::path::Path;

use contract::{Action, ActionRequest, ActionResult, GitSha, Outcome, RequestId};

use guard::Approved;

mod guard;
mod journal;
mod memory;
mod request;
mod setup;
mod store;

pub use setup::Setup;

pub trait CommandRunner {
    fn run(&self, command: &Command) -> Result<(), String>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    #[non_exhaustive]
    RestartBackend,
    #[non_exhaustive]
    RollbackBackend {
        observed: contract::GitSha,
        target: contract::GitSha,
    },
}

pub trait Clock {
    fn now_unix(&self) -> u64;
}

pub fn execute(
    request_file: &Path,
    setup: &Setup,
    runner: &impl CommandRunner,
    clock: &impl Clock,
) -> io::Result<ActionResult> {
    let id = request_id_from_file_name(request_file)?;
    let _lock = acquire_lock(setup)?;
    let now = clock.now_unix();
    if !store::claim(setup, &id)? {
        return Ok(rejected(&id, "request id already used", now));
    }
    let outcome = match request::load(request_file, &id) {
        Ok(request) => decide(&request, setup, runner, now)?,
        Err(reason) => Outcome::Rejected { reason },
    };
    let result = ActionResult::new(id, outcome, clock.now_unix());
    store::record(setup, &result)?;
    Ok(result)
}

fn acquire_lock(setup: &Setup) -> io::Result<fs::File> {
    if let Some(dir) = setup.lock_file.parent() {
        fs::create_dir_all(dir)?;
    }
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(&setup.lock_file)?;
    file.lock()?;
    Ok(file)
}

fn request_id_from_file_name(path: &Path) -> io::Result<RequestId> {
    path.file_stem()
        .and_then(|stem| stem.to_str())
        .and_then(|stem| RequestId::try_from(stem.to_owned()).ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid request file name"))
}

fn rejected(id: &RequestId, reason: &str, now: u64) -> ActionResult {
    let outcome = Outcome::Rejected {
        reason: reason.to_owned(),
    };
    ActionResult::new(id.clone(), outcome, now)
}

fn decide(
    request: &ActionRequest,
    setup: &Setup,
    runner: &impl CommandRunner,
    now: u64,
) -> io::Result<Outcome> {
    if now >= request.expires_at_unix() {
        return Ok(Outcome::Rejected {
            reason: "request expired".to_owned(),
        });
    }
    let approved = match guard::verify(setup, request.totp_code().as_str(), now)? {
        Ok(approved) => approved,
        Err(reason) => return Ok(rejected_outcome(reason)),
    };
    match request.action() {
        Action::RestartBackend {} => match journal::restart_allowed(&setup.deploy_journal) {
            Ok(()) => Ok(run(runner, &approved, &Command::RestartBackend)),
            Err(reason) => Ok(rejected_outcome(&reason)),
        },
        Action::Rollback { target_sha } => rollback(setup, runner, &approved, target_sha),
    }
}

fn rejected_outcome(reason: &str) -> Outcome {
    Outcome::Rejected {
        reason: reason.to_owned(),
    }
}

fn rollback(
    setup: &Setup,
    runner: &impl CommandRunner,
    approved: &Approved,
    target: &GitSha,
) -> io::Result<Outcome> {
    let command = match journal::rollback_command(&setup.deploy_journal, target) {
        Ok(command) => command,
        Err(reason) => return Ok(rejected_outcome(&reason)),
    };
    let Command::RollbackBackend { observed, target } = &command else {
        return Ok(rejected_outcome("rollback not allowed"));
    };
    match memory::contains(setup, target) {
        Ok(false) => {}
        Ok(true) => return Ok(rejected_outcome("rollback target was rolled back from")),
        Err(reason) => return Ok(rejected_outcome(&reason)),
    }
    memory::remember(setup, observed)?;
    Ok(run(runner, approved, &command))
}

fn run(runner: &impl CommandRunner, _approved: &Approved, command: &Command) -> Outcome {
    match runner.run(command) {
        Ok(()) => Outcome::Executed {},
        Err(reason) => Outcome::Failed { reason },
    }
}
