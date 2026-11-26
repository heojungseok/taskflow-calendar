use std::path::PathBuf;
use std::sync::mpsc::{Receiver, Sender};

use analyzer::{Alert, Clock, EvidenceSource, Knowledge, Model, triage};
use incident::{Deploy, Event};

use crate::journal;

pub(crate) type Job = (u64, Vec<Alert>);

pub(crate) struct Analyst<F, M, K> {
    pub(crate) knowledge: Knowledge,
    pub(crate) evidence_for: F,
    pub(crate) model: M,
    pub(crate) clock: K,
    pub(crate) deploy_journal: PathBuf,
}

impl<F, S, M, K> Analyst<F, M, K>
where
    F: Fn(Option<Deploy>) -> S,
    S: EvidenceSource,
    M: Model,
    K: Clock,
{
    fn analyze(&self, (batch, alerts): Job) -> Event {
        let last_deploy = journal::last_deploy(&self.deploy_journal);
        let evidence = (self.evidence_for)(last_deploy.clone());
        let reports = triage(
            &alerts,
            &self.knowledge,
            &evidence,
            &self.clock,
            &self.model,
        );
        Event::Analyzed {
            batch,
            reports,
            last_deploy,
        }
    }
}

pub(crate) fn run<F, S, M, K>(
    analyst: &Analyst<F, M, K>,
    jobs: Receiver<Job>,
    events: Sender<Event>,
) where
    F: Fn(Option<Deploy>) -> S,
    S: EvidenceSource,
    M: Model,
    K: Clock,
{
    while let Ok(job) = jobs.recv() {
        if events.send(analyst.analyze(job)).is_err() {
            break;
        }
    }
}
