#![allow(dead_code)]

use std::cell::RefCell;
use std::fs;
use std::path::PathBuf;

use contract::{ActionResult, GitSha};
use executor::{Clock, Command, CommandRunner, Setup};
use serde_json::json;
use tempfile::TempDir;
use totp_rs::{Builder, Secret};

pub(crate) const NOW: u64 = 1_764_120_100;
pub(crate) const SECRET: &str = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
pub(crate) const CURRENT_SHA: &str = "2222222222222222222222222222222222222222";
pub(crate) const OTHER_SHA: &str = "3333333333333333333333333333333333333333";
pub(crate) const PREVIOUS_SHA: &str = "1111111111111111111111111111111111111111";

pub(crate) struct FixedClock(pub(crate) u64);

impl Clock for FixedClock {
    fn now_unix(&self) -> u64 {
        self.0
    }
}

#[derive(Default)]
pub(crate) struct RecordingRunner {
    pub(crate) calls: RefCell<Vec<Command>>,
    pub(crate) failure: Option<String>,
}

impl CommandRunner for RecordingRunner {
    fn run(&self, command: &Command) -> Result<(), String> {
        self.calls.borrow_mut().push(command.clone());
        self.failure.clone().map_or(Ok(()), Err)
    }
}

pub(crate) struct Fixture {
    dir: TempDir,
    pub(crate) setup: Setup,
}

impl Fixture {
    pub(crate) fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        for sub in ["requests", "results", "state", "deploy"] {
            fs::create_dir(dir.path().join(sub)).unwrap();
        }
        fs::write(dir.path().join("state/totp-guard"), "0 0").unwrap();
        let setup = Setup::new(
            dir.path().join("results"),
            dir.path().join("state"),
            dir.path().join("deploy"),
            SECRET,
        )
        .unwrap();
        let fixture = Self { dir, setup };
        fixture.write_journal(&[
            ("success", "backend", PREVIOUS_SHA),
            ("deployment-running", "backend", CURRENT_SHA),
            ("observing", "backend", CURRENT_SHA),
            ("success", "backend", CURRENT_SHA),
        ]);
        fixture
    }

    pub(crate) fn write_journal(&self, rows: &[(&str, &str, &str)]) {
        let lines: String = rows
            .iter()
            .map(|(status, scope, sha)| {
                format!("2026-11-26T00:00:00Z\t{sha}\t{status}\t{scope}\t77\tunknown\n")
            })
            .collect();
        fs::write(self.path("deploy/journal.tsv"), lines).unwrap();
    }

    pub(crate) fn path(&self, relative: &str) -> PathBuf {
        self.dir.path().join(relative)
    }

    pub(crate) fn write_request(&self, id: &str, action: serde_json::Value, code: &str) -> PathBuf {
        self.write_request_at(id, action, code, NOW - 60, NOW + 540)
    }

    pub(crate) fn write_request_at(
        &self,
        id: &str,
        action: serde_json::Value,
        code: &str,
        issued: u64,
        expires: u64,
    ) -> PathBuf {
        let body = json!({
            "request_id": id,
            "incident_id": "inc-3",
            "action": action,
            "requested_by": "1234567890",
            "totp_code": code,
            "issued_at_unix": issued,
            "expires_at_unix": expires,
        });
        let path = self.path(&format!("requests/{id}.json"));
        fs::write(&path, body.to_string()).unwrap();
        path
    }

    pub(crate) fn result_file(&self, id: &str) -> Option<ActionResult> {
        let path = self.path(&format!("results/{id}.json"));
        fs::read_to_string(path)
            .ok()
            .map(|body| serde_json::from_str(&body).unwrap())
    }
}

pub(crate) fn valid_code() -> String {
    code_at(NOW)
}

pub(crate) fn code_at(time: u64) -> String {
    Builder::new()
        .with_secret(Secret::try_from_base32(SECRET).unwrap())
        .build()
        .unwrap()
        .generate(time)
        .to_string()
}

pub(crate) fn restart() -> serde_json::Value {
    json!({ "kind": "restart_backend" })
}

pub(crate) fn rollback(sha: &str) -> serde_json::Value {
    json!({ "kind": "rollback", "target_sha": sha })
}

pub(crate) fn sha(value: &str) -> GitSha {
    GitSha::try_from(value.to_owned()).unwrap()
}
