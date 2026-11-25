#![allow(dead_code, unreachable_pub)]

use analyzer::{
    Alert, Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, LogLine, Model, ModelError,
    Report, Service, Window, triage,
};
use contract::{ActionResult, DiscordUserId, GitSha, Outcome, TotpCode};
use incident::{
    Approval, Command, Deploy, DeployScope, Event, Incidents, Notice, ProposedAction, Settings,
};

pub const OWNER: &str = "1234567890";
pub const T0: u64 = 1_764_120_000;
pub const PREVIOUS: &str = "1f2a9320000000000000000000000000000000ab";
pub const CURRENT: &str = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";
pub const CODE: &str = "042137";

struct Fixed(Evidence);
impl EvidenceSource for Fixed {
    fn collect(&self, _: &Window, _: &[Service]) -> Result<Evidence, EvidenceError> {
        Ok(self.0.clone())
    }
}
struct At(u64);
impl Clock for At {
    fn now_unix(&self) -> u64 {
        self.0
    }
}
struct Answer(&'static str);
impl Model for Answer {
    fn explain(&self, _: &str) -> Result<String, ModelError> {
        Ok(self.0.to_owned())
    }
}

pub fn alert_named(name: &str, service: &str, at: u64) -> Alert {
    Alert {
        name: name.to_owned(),
        service: Service::new(service),
        starts_at_unix: at,
    }
}

pub fn alert(service: &str, at: u64) -> Alert {
    alert_named("error logs", service, at)
}

pub fn reports(alerts: &[Alert], grounded: bool) -> Vec<Report> {
    let logs = alerts
        .iter()
        .map(|a| LogLine {
            service: a.service.clone(),
            at_unix: a.starts_at_unix + 5,
            text: "ERROR c.t.p.TaskSearchService : NullPointerException".to_owned(),
        })
        .collect();
    let answer = if grounded {
        Answer(
            r#"{"cause":"검색 null 처리 누락","evidence":"NPE 반복","confidence":"high","cited_lines":[1]}"#,
        )
    } else {
        Answer(r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#)
    };
    let now = alerts.iter().map(|a| a.starts_at_unix).max().unwrap_or(T0) + 60;
    let evidence = Fixed(Evidence {
        logs,
        ..Evidence::default()
    });
    triage(alerts, &Knowledge::default(), &evidence, &At(now), &answer)
}

pub fn deploy(scope: DeployScope, at: u64) -> Option<Deploy> {
    Some(Deploy {
        sha: GitSha::try_from(CURRENT.to_owned()).unwrap(),
        previous_sha: GitSha::try_from(PREVIOUS.to_owned()).unwrap(),
        scope,
        at_unix: at,
    })
}

pub fn settings() -> Settings {
    Settings {
        owner: DiscordUserId::try_from(OWNER.to_owned()).unwrap(),
        daily_limit: 20,
        restart_after_secs: 900,
        button_ttl_secs: 600,
        rollback_window_secs: 86_400,
        recurrence_window_secs: 1_800,
    }
}

pub fn incidents() -> Incidents {
    Incidents::open_in_memory(settings()).unwrap()
}

pub fn receive(incidents: &mut Incidents, alerts: Vec<Alert>, now: u64) -> Vec<Command> {
    incidents
        .handle(Event::AlertsReceived(alerts), now)
        .unwrap()
}

pub fn analyze_all(
    incidents: &mut Incidents,
    commands: &[Command],
    grounded: bool,
    last_deploy: Option<Deploy>,
    now: u64,
) -> Vec<Command> {
    let mut out = Vec::new();
    for command in commands {
        if let Command::Analyze { batch, alerts } = command {
            out.extend(
                incidents
                    .handle(
                        Event::Analyzed {
                            batch: *batch,
                            reports: reports(alerts, grounded),
                            last_deploy: last_deploy.clone(),
                        },
                        now,
                    )
                    .unwrap(),
            );
        }
    }
    out
}

pub fn open_backend(incidents: &mut Incidents, grounded: bool, last_deploy: Option<Deploy>) -> u64 {
    let commands = receive(incidents, vec![alert("backend", T0)], T0);
    let posted = analyze_all(incidents, &commands, grounded, last_deploy, T0 + 60);
    incident_ids(&posted)[0]
}

pub fn incident_ids(commands: &[Command]) -> Vec<u64> {
    commands
        .iter()
        .filter_map(|c| match c {
            Command::Post(Notice::Incident { incident, .. }) => Some(*incident),
            _ => None,
        })
        .collect()
}

pub fn proposals(commands: &[Command]) -> Vec<(u64, ProposedAction, u64)> {
    commands
        .iter()
        .filter_map(|c| match c {
            Command::Post(Notice::Proposal {
                incident,
                action,
                issued_at_unix,
            }) => Some((*incident, action.clone(), *issued_at_unix)),
            _ => None,
        })
        .collect()
}

pub fn approval(
    incident: u64,
    action: ProposedAction,
    by: &str,
    code: &str,
    issued_at: u64,
) -> Event {
    Event::ApprovalRequested(Approval {
        incident,
        action,
        by: DiscordUserId::try_from(by.to_owned()).unwrap(),
        code: TotpCode::try_from(code.to_owned()).unwrap(),
        button_issued_at_unix: issued_at,
    })
}

pub fn written(commands: &[Command]) -> Vec<contract::ActionRequest> {
    commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteRequest(request) => Some(request.clone()),
            _ => None,
        })
        .collect()
}

pub fn finish(
    incidents: &mut Incidents,
    request: &contract::ActionRequest,
    outcome: Outcome,
    now: u64,
) -> Vec<Command> {
    incidents
        .handle(
            Event::ExecutionResult(ActionResult::new(
                request.request_id().clone(),
                outcome,
                now,
            )),
            now,
        )
        .unwrap()
}

pub fn restart_executed(incidents: &mut Incidents, incident: u64) -> u64 {
    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();
    let (_, action, issued) = proposals(&tick)
        .into_iter()
        .find(|(i, _, _)| *i == incident)
        .expect("restart proposal");
    let commands = incidents
        .handle(approval(incident, action, OWNER, CODE, issued), T0 + 970)
        .unwrap();
    let request = written(&commands).remove(0);
    finish(incidents, &request, Outcome::Executed {}, T0 + 990);
    T0 + 990
}
