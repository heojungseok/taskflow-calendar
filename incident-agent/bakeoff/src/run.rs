use std::cell::RefCell;
use std::process::Command;
use std::time::{Duration, Instant};

use analyzer::{
    Analysis, Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, Model, ModelError,
    Service, Window, triage,
};
use ollama::{Ollama, Reply};
use serde_json::json;

use crate::scenarios::Scenario;
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

struct At(u64);

impl Clock for At {
    fn now_unix(&self) -> u64 {
        self.0
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
    let memory_bytes = runner_rss_bytes();
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
        &At(scenario.now),
        &recorder,
    )
    .remove(0);
    let wall_ms = started.elapsed().as_millis();
    if let Some(answer) = &scenario.suspect
        && !answer.is_in(&report.suspects)
    {
        let gap = scenario
            .known_gap
            .as_ref()
            .map_or_else(String::new, |gap| format!(" (알려진 약점: {gap})"));
        eprintln!("{}: 코드가 고른 의심에 {answer:?} 없음{gap}", scenario.name);
    }
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

fn runner_rss_bytes() -> Option<u64> {
    let output = Command::new("ps")
        .args(["-A", "-o", "rss=,command="])
        .output()
        .ok()?;
    let kilobytes: u64 = String::from_utf8_lossy(&output.stdout)
        .lines()
        .filter(|line| line.contains("ollama") && line.contains("llama-server"))
        .filter_map(|line| line.split_whitespace().next()?.parse::<u64>().ok())
        .sum();
    (kilobytes > 0).then_some(kilobytes * 1024)
}

fn unload(url: &str, name: &str) {
    let _ = ureq::post(format!("{url}/api/generate"))
        .send_json(json!({"model": name, "keep_alive": 0}));
}
