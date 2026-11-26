use std::fs;
use std::io::{self, Write};

use tempfile::NamedTempFile;

use crate::Setup;

const MAX_FAILURES: u32 = 5;

struct State {
    failures: u32,
    last_step: u64,
}

pub(crate) struct Approved(());

pub(crate) fn verify(
    setup: &Setup,
    code: &str,
    now: u64,
) -> io::Result<Result<Approved, &'static str>> {
    let Some(state) = load(setup)? else {
        return Ok(Err("totp locked"));
    };
    if state.failures >= MAX_FAILURES {
        return Ok(Err("totp locked"));
    }
    let Some(step) = setup.totp.check(code, now) else {
        save(setup, state.failures + 1, state.last_step)?;
        return Ok(Err("invalid totp code"));
    };
    if step <= state.last_step {
        save(setup, state.failures + 1, state.last_step)?;
        return Ok(Err("totp code already used"));
    }
    save(setup, 0, step)?;
    Ok(Ok(Approved(())))
}

fn load(setup: &Setup) -> io::Result<Option<State>> {
    let body = match fs::read_to_string(&setup.guard_file) {
        Ok(body) => body,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    Ok(parse(&body))
}

fn parse(body: &str) -> Option<State> {
    let mut parts = body.split_whitespace();
    let failures = parts.next()?.parse().ok()?;
    let last_step = parts.next()?.parse().ok()?;
    parts.next().is_none().then_some(State {
        failures,
        last_step,
    })
}

fn save(setup: &Setup, failures: u32, last_step: u64) -> io::Result<()> {
    let dir = setup
        .guard_file
        .parent()
        .ok_or_else(|| io::Error::other("guard file has no parent"))?;
    fs::create_dir_all(dir)?;
    let mut temp = NamedTempFile::new_in(dir)?;
    write!(temp, "{failures} {last_step}")?;
    temp.persist(&setup.guard_file)
        .map_err(|error| error.error)?;
    Ok(())
}
