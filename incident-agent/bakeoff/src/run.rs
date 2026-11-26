use std::cell::RefCell;
use std::time::{Duration, Instant};

use analyzer::{
    Analysis, Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, Model, ModelError,
    Service, Window, triage,
};
use ollama::{Ollama, Reply};
use serde::Deserialize;
use serde_json::json;

use crate::scenarios::{NOW, Scenario};
use crate::score::{Outcome, score};

const RUN_TIMEOUT: Duration = Duration::from_secs(60);

pub(crate) struct Row {
    pub(crate) outcome: Outcome,
    pub(crate) answer: Option<String>,
    pub(crate) reply: Option<Reply>,
    pub(crate) wall_ms: u128,
}

pub(crate) struct ModelRun {
    pub(crate) model: String,
    pub(crate) rows: Vec<Row>,
    pub(crate) memory_bytes: Option<u64>,
}

struct Fixed(Evidence);

impl EvidenceSource for Fixed {
    fn collect(&self, _window: &Window, _services: &[Service]) -> Result<Evidence, EvidenceError> {
        Ok(self.0.clone())
    }
}

struct At;

impl Clock for At {
    fn now_unix(&self) -> u64 {
        NOW
    }
}

struct Recorder<'a> {
    model: &'a Ollama,
    prompt: RefCell<String>,
    reply: RefCell<Option<Reply>>,
}

impl Model for Recorder<'_> {
    fn explain(&self, prompt: &str) -> Result<String, ModelError> {
        *self.prompt.borrow_mut() = prompt.to_owned();
        let reply = self.model.chat(prompt)?;
        let content = reply.content.clone();
        *self.reply.borrow_mut() = Some(reply);
        Ok(content)
    }
}

pub(crate) fn run_model(url: &str, name: &str, think: bool, scenarios: &[Scenario]) -> ModelRun {
    let model = Ollama::new(url, name)
        .think(think)
        .keep_alive("2m")
        .timeout(RUN_TIMEOUT);
    let rows = scenarios
        .iter()
        .map(|scenario| run_one(&model, scenario))
        .collect();
    let memory_bytes = loaded_size(url, name);
    unload(url, name);
    ModelRun {
        model: name.to_owned(),
        rows,
        memory_bytes,
    }
}

fn run_one(model: &Ollama, scenario: &Scenario) -> Row {
    let recorder = Recorder {
        model,
        prompt: RefCell::default(),
        reply: RefCell::default(),
    };
    let started = Instant::now();
    let report = triage(
        &scenario.alerts(),
        &Knowledge::default(),
        &Fixed(scenario.evidence.clone()),
        &At,
        &recorder,
    )
    .remove(0);
    let wall_ms = started.elapsed().as_millis();
    let prompt = recorder.prompt.borrow();
    let outcome = score(&scenario.expect, &report.analysis, &prompt);
    let answer = match &report.analysis {
        Analysis::Guess(guess) => Some(format!("{} — 근거: {}", guess.cause(), guess.evidence())),
        _ => recorder
            .reply
            .borrow()
            .as_ref()
            .map(|reply| reply.content.clone()),
    };
    Row {
        outcome,
        answer,
        reply: recorder.reply.take(),
        wall_ms,
    }
}

#[derive(Deserialize)]
struct Running {
    models: Vec<RunningModel>,
}

#[derive(Deserialize)]
struct RunningModel {
    name: String,
    size: u64,
}

fn loaded_size(url: &str, name: &str) -> Option<u64> {
    let running: Running = ureq::get(format!("{url}/api/ps"))
        .call()
        .ok()?
        .body_mut()
        .read_json()
        .ok()?;
    running
        .models
        .into_iter()
        .find(|model| model.name == name)
        .map(|model| model.size)
}

fn unload(url: &str, name: &str) {
    let _ = ureq::post(format!("{url}/api/generate"))
        .send_json(json!({"model": name, "keep_alive": 0}));
}
