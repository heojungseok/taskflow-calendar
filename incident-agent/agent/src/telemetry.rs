use std::collections::BTreeMap;
use std::fmt::Write;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Instant;

use analyzer::{Analysis, Model, ModelError, SkipReason};
use contract::Outcome;
use incident::{Command, Event, Notice, Verdict};

type Labels = Vec<(String, String)>;

#[derive(Default)]
pub(crate) struct Telemetry {
    counters: Mutex<BTreeMap<String, BTreeMap<Labels, f64>>>,
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

    pub(crate) fn observe_event(&self, event: &Event) {
        match event {
            Event::AlertsReceived(alerts) => self.add(
                "incident_agent_alerts_received_total",
                &[],
                alerts.len() as f64,
            ),
            Event::Analyzed { reports, .. } => {
                for report in reports {
                    self.count_analysis(&report.analysis);
                }
            }
            Event::VerdictGiven { verdict, .. } => {
                let label = match verdict {
                    Verdict::Correct => "correct",
                    Verdict::Wrong { .. } => "wrong",
                };
                self.add("incident_agent_verdicts_total", &[("verdict", label)], 1.0);
            }
            Event::ApprovalRequested(_) => self.add("incident_agent_approvals_total", &[], 1.0),
            Event::ExecutionResult(result) => {
                let label = match result.outcome() {
                    Outcome::Executed {} => "executed",
                    Outcome::Rejected { .. } => "rejected",
                    Outcome::Failed { .. } => "failed",
                };
                self.add(
                    "incident_agent_action_results_total",
                    &[("outcome", label)],
                    1.0,
                );
            }
            Event::AlertsResolved(_) | Event::Tick => {}
        }
    }

    pub(crate) fn observe_command(&self, command: &Command) {
        if let Command::Post(Notice::LimitExceeded(_)) = command {
            self.add("incident_agent_limit_exceeded_total", &[], 1.0);
        }
    }

    fn count_analysis(&self, analysis: &Analysis) {
        let name = "incident_agent_analyses_total";
        match analysis {
            Analysis::Guess(_) => self.add(name, &[("result", "guess")], 1.0),
            Analysis::Unknown => self.add(name, &[("result", "unknown")], 1.0),
            Analysis::Skipped(reason) => self.add(
                name,
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
        self.telemetry.add(
            "incident_agent_model_seconds_total",
            &[],
            started.elapsed().as_secs_f64(),
        );
        let result = match &answer {
            Ok(_) => "ok",
            Err(ModelError::Timeout) => "timeout",
            Err(ModelError::Unavailable(_)) => "unavailable",
        };
        self.telemetry.add(
            "incident_agent_model_calls_total",
            &[("result", result)],
            1.0,
        );
        answer
    }
}

#[cfg(test)]
mod tests;
