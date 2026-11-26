use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::mpsc::{Receiver, Sender};

use analyzer::Clock;
use contract::{ActionResult, Outcome};
use incident::{Command, Event, Incidents, Notice};

use crate::analysis::Job;
use crate::telemetry::Telemetry;
use crate::{drafts, requests};

pub(crate) trait Chat {
    fn post(&self, notice: &Notice);
}

pub(crate) struct Paths {
    pub(crate) drafts_dir: PathBuf,
    pub(crate) requests_dir: PathBuf,
}

pub(crate) struct Core<C, K> {
    pub(crate) incidents: Incidents,
    pub(crate) chat: C,
    pub(crate) clock: K,
    pub(crate) paths: Paths,
    pub(crate) analysis: Sender<Job>,
    pub(crate) telemetry: Arc<Telemetry>,
}

impl<C: Chat, K: Clock> Core<C, K> {
    pub(crate) fn process(&mut self, event: Event) {
        let mut queue = VecDeque::from([event]);
        while let Some(event) = queue.pop_front() {
            let now = self.clock.now_unix();
            self.telemetry.observe_event(&event);
            let verdict = match &event {
                Event::VerdictGiven { verdict, .. } => Some(verdict.clone()),
                _ => None,
            };
            match self.incidents.handle(event, now) {
                Ok(commands) => {
                    self.telemetry.observe_verdict(verdict.as_ref(), &commands);
                    for command in commands {
                        queue.extend(self.execute(command, now));
                    }
                }
                Err(error) => crate::syslog::line(&format!("event not handled: {error}")),
            }
        }
    }

    fn execute(&self, command: Command, now: u64) -> Option<Event> {
        self.telemetry.observe_command(&command);
        match command {
            Command::Analyze { batch, alerts } => {
                if self.analysis.send((batch, alerts)).is_err() {
                    crate::syslog::line(&format!("analysis stopped, batch {batch} dropped"));
                }
                None
            }
            Command::Post(notice) => {
                self.chat.post(&notice);
                None
            }
            Command::WriteRequest(request) => {
                let written = requests::write(&self.paths.requests_dir, &request);
                let Err(error) = written else {
                    return None;
                };
                crate::syslog::line(&format!("request not written: {:?}", error.kind()));
                let failed = Outcome::Failed {
                    reason: "request not written".to_owned(),
                };
                let result = ActionResult::new(request.request_id().clone(), failed, now);
                Some(Event::ExecutionResult(result))
            }
            Command::SaveDraft { incident, markdown } => {
                if let Err(error) = drafts::save(&self.paths.drafts_dir, incident, &markdown) {
                    crate::syslog::line(&format!("draft not saved: {:?}", error.kind()));
                }
                None
            }
        }
    }
}

pub(crate) fn run<C: Chat, K: Clock>(mut core: Core<C, K>, events: Receiver<Event>) {
    while let Ok(event) = events.recv() {
        core.process(event);
    }
}

#[cfg(test)]
mod tests;
