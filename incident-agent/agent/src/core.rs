use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, Sender};

use analyzer::Clock;
use contract::{ActionResult, Outcome};
use incident::{Command, Event, Incidents, Notice};

use crate::analysis::Job;
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
}

impl<C: Chat, K: Clock> Core<C, K> {
    pub(crate) fn process(&mut self, event: Event) {
        let mut queue = VecDeque::from([event]);
        while let Some(event) = queue.pop_front() {
            let now = self.clock.now_unix();
            match self.incidents.handle(event, now) {
                Ok(commands) => {
                    for command in commands {
                        queue.extend(self.execute(command, now));
                    }
                }
                Err(error) => eprintln!("event not handled: {error}"),
            }
        }
    }

    fn execute(&self, command: Command, now: u64) -> Option<Event> {
        match command {
            Command::Analyze { batch, alerts } => {
                if self.analysis.send((batch, alerts)).is_err() {
                    eprintln!("analysis stopped, batch {batch} dropped");
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
                eprintln!("request not written: {:?}", error.kind());
                let failed = Outcome::Failed {
                    reason: "request not written".to_owned(),
                };
                let result = ActionResult::new(request.request_id().clone(), failed, now);
                Some(Event::ExecutionResult(result))
            }
            Command::SaveDraft { incident, markdown } => {
                if let Err(error) = drafts::save(&self.paths.drafts_dir, incident, &markdown) {
                    eprintln!("draft not saved: {:?}", error.kind());
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
