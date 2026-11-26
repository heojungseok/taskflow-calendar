use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use analyzer::{
    Alert, Evidence, EvidenceError, EvidenceSource, Knowledge, LogLine, Model, ModelError, Service,
    Window,
};
use contract::{DiscordUserId, Outcome, TotpCode};
use incident::{Approval, Deploy, ProposedAction};

use super::*;
use crate::analysis::{self, Analyst};
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

#[derive(Clone, Default)]
struct Gate(Arc<(Mutex<bool>, Condvar)>);

impl Gate {
    fn set(&self, open: bool) {
        *self.0.0.lock().unwrap() = open;
        self.0.1.notify_all();
    }

    fn wait_open(&self) {
        let mut open = self.0.0.lock().unwrap();
        while !*open {
            open = self.0.1.wait(open).unwrap();
        }
    }
}

struct GatedModel(Gate);

impl Model for GatedModel {
    fn explain(&self, _: &str) -> Result<String, ModelError> {
        self.0.wait_open();
        Ok(r#"{"cause":"검색 null 처리 누락","evidence":"NPE 반복","confidence":"high","cited_lines":[1]}"#.to_owned())
    }
}

#[derive(Clone)]
struct SharedClock(Arc<AtomicU64>);

impl Clock for SharedClock {
    fn now_unix(&self) -> u64 {
        self.0.load(Ordering::SeqCst)
    }
}

#[derive(Clone, Default)]
struct Recorder(Arc<Mutex<Vec<Notice>>>);

impl Chat for Recorder {
    fn post(&self, notice: &Notice) {
        self.0.lock().unwrap().push(notice.clone());
    }
}

struct Rig {
    core: Core<Recorder, SharedClock>,
    chat: Recorder,
    gate: Gate,
    clock: SharedClock,
    events: Receiver<Event>,
    dir: tempfile::TempDir,
}

fn owner() -> DiscordUserId {
    DiscordUserId::try_from("1234567890".to_owned()).unwrap()
}

fn rig() -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let (event_tx, events) = mpsc::channel();
    let (job_tx, job_rx) = mpsc::channel();
    let (chat, gate) = (Recorder::default(), Gate::default());
    gate.set(true);
    let clock = SharedClock(Arc::new(AtomicU64::new(T0 + 60)));
    let analyst = Analyst {
        knowledge: Knowledge::default(),
        evidence_for: |_: Option<Deploy>| FakeEvidence,
        model: GatedModel(gate.clone()),
        clock: clock.clone(),
        deploy_journal: dir.path().join("journal.tsv"),
    };
    thread::spawn(move || analysis::run(&analyst, job_rx, event_tx));
    let core = Core {
        incidents: Incidents::open_in_memory(config::settings(owner())).unwrap(),
        chat: chat.clone(),
        clock: clock.clone(),
        paths: Paths {
            drafts_dir: dir.path().join("drafts"),
            requests_dir: dir.path().join("requests"),
        },
        analysis: job_tx,
        telemetry: Arc::new(crate::telemetry::Telemetry::default()),
    };
    Rig {
        core,
        chat,
        gate,
        clock,
        events,
        dir,
    }
}

impl Rig {
    fn notices(&self) -> usize {
        self.chat.0.lock().unwrap().len()
    }

    fn settle(&mut self, notices: usize) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while self.notices() < notices && Instant::now() < deadline {
            if let Ok(event) = self.events.recv_timeout(Duration::from_millis(50)) {
                self.core.process(event);
            }
        }
        assert_eq!(self.notices(), notices);
    }

    fn open_proposal(&mut self) -> u64 {
        self.core
            .process(Event::AlertsReceived(vec![alert("backend", T0)]));
        self.settle(1);
        self.clock.0.store(T0 + 960, Ordering::SeqCst);
        self.core.process(Event::Tick);
        assert!(matches!(
            self.chat.0.lock().unwrap().last(),
            Some(Notice::Proposal { incident: 1, .. })
        ));
        1
    }
}

fn restart_approval(incident: u64) -> Event {
    Event::ApprovalRequested(Approval {
        incident,
        action: ProposedAction::RestartBackend,
        by: owner(),
        code: TotpCode::try_from("042137".to_owned()).unwrap(),
        button_issued_at_unix: T0 + 960,
    })
}

#[test]
fn t43_사건_처리를_거친_알림과_분석_결과가_지표에_남는다() {
    let mut rig = rig();
    rig.core
        .process(Event::AlertsReceived(vec![alert("backend", T0)]));
    rig.settle(1);
    let text = rig.core.telemetry.render();
    assert!(
        text.contains("incident_agent_alerts_received_total 1"),
        "{text}"
    );
    assert!(
        text.contains("incident_agent_analyses_total{result=\"guess\"} 1"),
        "{text}"
    );
}

#[test]
fn t41_알림은_분석을_거쳐_사건_알림_한_건을_채팅에_올린다() {
    let mut rig = rig();
    rig.core
        .process(Event::AlertsReceived(vec![alert("backend", T0)]));
    rig.settle(1);
    let posted = rig.chat.0.lock().unwrap();
    let Notice::Incident { report, .. } = &posted[0] else {
        panic!("사건 알림이 아니다");
    };
    assert!(matches!(report.analysis, analyzer::Analysis::Guess(_)));
}

#[test]
fn t41_한_사건이_오류여도_다음_이벤트를_계속_처리한다() {
    let mut rig = rig();
    rig.core.process(restart_approval(999));
    rig.core.process(Event::Tick);
    rig.core
        .process(Event::AlertsReceived(vec![alert("backend", T0)]));
    rig.settle(1);
}

#[test]
fn t41_분석이_느려도_승인은_바로_처리되어_요청_파일이_써진다() {
    let mut rig = rig();
    let incident = rig.open_proposal();
    rig.gate.set(false);
    let slow = Alert {
        name: "slow".to_owned(),
        ..alert("backend", T0 + 900)
    };
    rig.core.process(Event::AlertsReceived(vec![slow]));
    let started = Instant::now();
    rig.core.process(restart_approval(incident));
    assert!(started.elapsed() < Duration::from_secs(2));
    let requests = rig.dir.path().join("requests");
    assert_eq!(std::fs::read_dir(&requests).unwrap().count(), 1);
    assert_eq!(rig.notices(), 2);
    rig.gate.set(true);
    rig.settle(3);
}

#[test]
fn t41_요청_파일을_못_쓰면_실패_결과를_흘려_알림이_나간다() {
    let mut rig = rig();
    let incident = rig.open_proposal();
    std::fs::write(rig.dir.path().join("requests"), "막는 파일").unwrap();
    rig.core.process(restart_approval(incident));
    let posted = rig.chat.0.lock().unwrap();
    let finished = posted.iter().any(|n| {
        matches!(
            n,
            Notice::ActionFinished {
                outcome: Outcome::Failed { .. },
                ..
            }
        )
    });
    assert!(finished);
    assert!(
        posted
            .iter()
            .any(|n| matches!(n, Notice::ManualOnly { .. }))
    );
}
