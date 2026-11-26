use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::mpsc::Receiver;

use analyzer::{Clock, EvidenceSource, Knowledge, Model, triage};
use incident::{Command, Event, Incidents, Notice};

use crate::{drafts, journal, requests};

pub(crate) trait Chat {
    fn post(&self, notice: &Notice);
}

pub(crate) struct Paths {
    pub(crate) drafts_dir: PathBuf,
    pub(crate) requests_dir: PathBuf,
    pub(crate) deploy_journal: PathBuf,
}

pub(crate) struct Core<E, M, C, K> {
    pub(crate) incidents: Incidents,
    pub(crate) knowledge: Knowledge,
    pub(crate) evidence: E,
    pub(crate) model: M,
    pub(crate) chat: C,
    pub(crate) clock: K,
    pub(crate) paths: Paths,
}

impl<E: EvidenceSource, M: Model, C: Chat, K: Clock> Core<E, M, C, K> {
    pub(crate) fn process(&mut self, event: Event) {
        let mut queue = VecDeque::from([event]);
        while let Some(event) = queue.pop_front() {
            let now = self.clock.now_unix();
            match self.incidents.handle(event, now) {
                Ok(commands) => {
                    for command in commands {
                        queue.extend(self.execute(command));
                    }
                }
                Err(error) => eprintln!("event not handled: {error}"),
            }
        }
    }

    fn execute(&self, command: Command) -> Option<Event> {
        match command {
            Command::Analyze { batch, alerts } => {
                let reports = triage(
                    &alerts,
                    &self.knowledge,
                    &self.evidence,
                    &self.clock,
                    &self.model,
                );
                Some(Event::Analyzed {
                    batch,
                    reports,
                    last_deploy: journal::last_deploy(&self.paths.deploy_journal),
                })
            }
            Command::Post(notice) => {
                self.chat.post(&notice);
                None
            }
            Command::WriteRequest(request) => {
                if let Err(error) = requests::write(&self.paths.requests_dir, &request) {
                    eprintln!("request not written: {}", error.kind());
                }
                None
            }
            Command::SaveDraft { incident, markdown } => {
                if let Err(error) = drafts::save(&self.paths.drafts_dir, incident, &markdown) {
                    eprintln!("draft not saved: {}", error.kind());
                }
                None
            }
        }
    }
}

pub(crate) fn run<E: EvidenceSource, M: Model, C: Chat, K: Clock>(
    mut core: Core<E, M, C, K>,
    events: Receiver<Event>,
) {
    while let Ok(event) = events.recv() {
        core.process(event);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use analyzer::{Alert, Evidence, EvidenceError, LogLine, ModelError, Service, Window};
    use contract::{DiscordUserId, TotpCode};
    use incident::Approval;

    use super::*;
    use crate::config;
    use crate::test_support::{T0, alert};

    struct FakeEvidence;

    impl EvidenceSource for FakeEvidence {
        fn collect(&self, _: &Window, services: &[Service]) -> Result<Evidence, EvidenceError> {
            let logs = services
                .iter()
                .map(|service| LogLine {
                    service: service.clone(),
                    at_unix: T0 + 5,
                    text: "ERROR c.t.p.TaskSearchService : NullPointerException".to_owned(),
                })
                .collect();
            Ok(Evidence {
                logs,
                ..Evidence::default()
            })
        }
    }

    struct FakeModel;

    impl Model for FakeModel {
        fn explain(&self, _: &str) -> Result<String, ModelError> {
            Ok(r#"{"cause":"검색 null 처리 누락","evidence":"NPE 반복","confidence":"high","cited_lines":[1]}"#.to_owned())
        }
    }

    struct FakeClock;

    impl Clock for FakeClock {
        fn now_unix(&self) -> u64 {
            T0 + 60
        }
    }

    #[derive(Clone, Default)]
    struct Recorder(Arc<Mutex<Vec<Notice>>>);

    impl Chat for Recorder {
        fn post(&self, notice: &Notice) {
            self.0.lock().unwrap().push(notice.clone());
        }
    }

    fn owner() -> DiscordUserId {
        DiscordUserId::try_from("1234567890".to_owned()).unwrap()
    }

    fn core(
        dir: &std::path::Path,
        chat: Recorder,
    ) -> Core<FakeEvidence, FakeModel, Recorder, FakeClock> {
        Core {
            incidents: Incidents::open_in_memory(config::settings(owner())).unwrap(),
            knowledge: Knowledge::default(),
            evidence: FakeEvidence,
            model: FakeModel,
            chat,
            clock: FakeClock,
            paths: Paths {
                drafts_dir: dir.join("drafts"),
                requests_dir: dir.join("requests"),
                deploy_journal: dir.join("journal.tsv"),
            },
        }
    }

    fn alerts() -> Vec<Alert> {
        vec![alert("backend", T0)]
    }

    #[test]
    fn t41_알림은_분석을_거쳐_사건_알림_한_건을_채팅에_올린다() {
        let dir = tempfile::tempdir().unwrap();
        let chat = Recorder::default();
        let mut core = core(dir.path(), chat.clone());
        core.process(Event::AlertsReceived(alerts()));
        let posted = chat.0.lock().unwrap();
        assert_eq!(posted.len(), 1);
        let Notice::Incident { report, .. } = &posted[0] else {
            panic!("사건 알림이 아니다");
        };
        assert!(matches!(report.analysis, analyzer::Analysis::Guess(_)));
    }

    #[test]
    fn t41_한_사건이_오류여도_다음_이벤트를_계속_처리한다() {
        let dir = tempfile::tempdir().unwrap();
        let chat = Recorder::default();
        let mut core = core(dir.path(), chat.clone());
        core.process(Event::ApprovalRequested(Approval {
            incident: 999,
            action: incident::ProposedAction::RestartBackend,
            by: owner(),
            code: TotpCode::try_from("042137".to_owned()).unwrap(),
            button_issued_at_unix: T0,
        }));
        core.process(Event::Tick);
        core.process(Event::AlertsReceived(alerts()));
        assert_eq!(chat.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn t41_run은_채널이_닫힐_때까지_이벤트를_처리한다() {
        let dir = tempfile::tempdir().unwrap();
        let chat = Recorder::default();
        let core = core(dir.path(), chat.clone());
        let (tx, rx) = std::sync::mpsc::channel();
        tx.send(Event::AlertsReceived(alerts())).unwrap();
        drop(tx);
        run(core, rx);
        assert_eq!(chat.0.lock().unwrap().len(), 1);
    }
}
