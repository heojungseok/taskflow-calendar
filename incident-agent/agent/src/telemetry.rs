use std::collections::BTreeMap;
use std::fmt::Write;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Instant;

use analyzer::{Analysis, Model, ModelError, SkipReason};
use contract::Outcome;
use incident::{Command, Event, Notice, Verdict};

type Labels = Vec<(String, String)>;

const ALERTS: &str = "incident_agent_alerts_received_total";
const ANALYSES: &str = "incident_agent_analyses_total";
const VERDICTS: &str = "incident_agent_verdicts_total";
const ACTION_REQUESTS: &str = "incident_agent_action_requests_total";
const ACTION_RESULTS: &str = "incident_agent_action_results_total";
const LIMIT_EXCEEDED: &str = "incident_agent_limit_exceeded_total";
const MODEL_CALLS: &str = "incident_agent_model_calls_total";
const MODEL_SECONDS: &str = "incident_agent_model_seconds_total";
const DISCORD_FAILURES: &str = "incident_agent_discord_failures_total";
const MAX_LOG_CHARS: usize = 300;

const SKIP_REASONS: [SkipReason; 7] = [
    SkipReason::ModelTimeout,
    SkipReason::ModelUnavailable,
    SkipReason::InvalidAnswer,
    SkipReason::UnsupportedCitation,
    SkipReason::NoEvidence,
    SkipReason::KnownIncident,
    SkipReason::CallLimit,
];
const MODEL_RESULTS: [&str; 3] = ["ok", "timeout", "unavailable"];

pub(crate) struct Telemetry {
    counters: Mutex<BTreeMap<String, BTreeMap<Labels, f64>>>,
}

impl Default for Telemetry {
    fn default() -> Self {
        let telemetry = Self {
            counters: Mutex::default(),
        };
        for name in [ALERTS, ACTION_REQUESTS, LIMIT_EXCEEDED, DISCORD_FAILURES] {
            telemetry.add(name, &[], 0.0);
        }
        for result in ["guess", "unknown"] {
            telemetry.add(ANALYSES, &[("result", result)], 0.0);
        }
        for reason in SKIP_REASONS {
            let labels = [("result", "skipped"), ("reason", skip_reason_label(reason))];
            telemetry.add(ANALYSES, &labels, 0.0);
        }
        for verdict in ["correct", "wrong"] {
            telemetry.add(VERDICTS, &[("verdict", verdict)], 0.0);
        }
        for outcome in ["executed", "rejected", "failed"] {
            telemetry.add(ACTION_RESULTS, &[("outcome", outcome)], 0.0);
        }
        for result in MODEL_RESULTS {
            telemetry.add(MODEL_CALLS, &[("result", result)], 0.0);
            telemetry.add(MODEL_SECONDS, &[("result", result)], 0.0);
        }
        telemetry
    }
}

impl Telemetry {
    pub(crate) fn add(&self, name: &str, labels: &[(&str, &str)], value: f64) {
        let key: Labels = labels
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect();
        let mut counters = self.counters.lock().unwrap_or_else(PoisonError::into_inner);
        *counters
            .entry(name.to_owned())
            .or_default()
            .entry(key)
            .or_insert(0.0) += value;
    }

    pub(crate) fn render(&self) -> String {
        let counters = self.counters.lock().unwrap_or_else(PoisonError::into_inner);
        let mut text = String::new();
        for (name, series) in counters.iter() {
            let _ = writeln!(text, "# TYPE {name} counter");
            for (labels, value) in series {
                let _ = writeln!(text, "{name}{} {value}", render_labels(labels));
            }
        }
        text
    }

    pub(crate) fn discord_failed(&self) {
        self.add(DISCORD_FAILURES, &[], 1.0);
    }

    pub(crate) fn observe_event(&self, event: &Event) {
        if let Event::AlertsReceived(alerts) = event {
            self.add(ALERTS, &[], alerts.len() as f64);
        }
    }

    pub(crate) fn observe_verdict(&self, verdict: Option<&Verdict>, commands: &[Command]) {
        let Some(verdict) = verdict else {
            return;
        };
        if !commands
            .iter()
            .any(|command| matches!(command, Command::SaveDraft { .. }))
        {
            return;
        }
        let label = match verdict {
            Verdict::Correct => "correct",
            Verdict::Wrong { .. } => "wrong",
        };
        self.add(VERDICTS, &[("verdict", label)], 1.0);
    }

    pub(crate) fn observe_command(&self, command: &Command) {
        match command {
            Command::Post(Notice::Incident { report, .. }) => self.count_analysis(&report.analysis),
            Command::Post(Notice::ActionFinished { outcome, .. }) => {
                let label = match outcome {
                    Outcome::Executed {} => "executed",
                    Outcome::Rejected { .. } => "rejected",
                    Outcome::Failed { .. } => "failed",
                };
                self.add(ACTION_RESULTS, &[("outcome", label)], 1.0);
            }
            Command::Post(Notice::LimitExceeded(_)) => self.add(LIMIT_EXCEEDED, &[], 1.0),
            Command::WriteRequest(_) => self.add(ACTION_REQUESTS, &[], 1.0),
            _ => {}
        }
    }

    fn count_analysis(&self, analysis: &Analysis) {
        match analysis {
            Analysis::Guess(_) => self.add(ANALYSES, &[("result", "guess")], 1.0),
            Analysis::Unknown => self.add(ANALYSES, &[("result", "unknown")], 1.0),
            Analysis::Skipped(reason) => self.add(
                ANALYSES,
                &[
                    ("result", "skipped"),
                    ("reason", skip_reason_label(*reason)),
                ],
                1.0,
            ),
        }
    }
}

fn skip_reason_label(reason: SkipReason) -> &'static str {
    match reason {
        SkipReason::ModelTimeout => "model_timeout",
        SkipReason::ModelUnavailable => "model_unavailable",
        SkipReason::InvalidAnswer => "invalid_answer",
        SkipReason::UnsupportedCitation => "unsupported_citation",
        SkipReason::NoEvidence => "no_evidence",
        SkipReason::KnownIncident => "known_incident",
        SkipReason::CallLimit => "call_limit",
    }
}

fn render_labels(labels: &Labels) -> String {
    if labels.is_empty() {
        return String::new();
    }
    let pairs: Vec<String> = labels
        .iter()
        .map(|(k, v)| format!("{k}=\"{}\"", escape_label_value(v)))
        .collect();
    format!("{{{}}}", pairs.join(","))
}

fn escape_label_value(value: &str) -> String {
    value
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}

pub(crate) struct Timed<M> {
    model: M,
    telemetry: Arc<Telemetry>,
}

impl<M> Timed<M> {
    pub(crate) fn new(model: M, telemetry: Arc<Telemetry>) -> Self {
        Self { model, telemetry }
    }
}

impl<M: Model> Model for Timed<M> {
    fn explain(&self, prompt: &str) -> Result<String, ModelError> {
        let started = Instant::now();
        let answer = self.model.explain(prompt);
        let result = match &answer {
            Ok(_) => "ok",
            Err(ModelError::Timeout) => "timeout",
            Err(ModelError::Unavailable(_)) => "unavailable",
        };
        let labels = [("result", result)];
        self.telemetry
            .add(MODEL_SECONDS, &labels, started.elapsed().as_secs_f64());
        self.telemetry.add(MODEL_CALLS, &labels, 1.0);
        if let Err(error) = &answer {
            crate::syslog::line(&format!("model failed: {}", log_text(error)));
        }
        answer
    }
}

fn log_text(error: &ModelError) -> String {
    error
        .to_string()
        .chars()
        .take(MAX_LOG_CHARS)
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect()
}

#[cfg(test)]
mod tests;
