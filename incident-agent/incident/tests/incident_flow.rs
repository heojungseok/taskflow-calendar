use analyzer::{
    Alert, Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, LogLine, Model, ModelError,
    Report, Service, Window, triage,
};
use contract::{DiscordUserId, GitSha, Outcome, TotpCode};
use incident::{
    Approval, Command, Deploy, DeployScope, Event, Incidents, Notice, ProposedAction, Settings,
    Verdict,
};

const OWNER: &str = "1234567890";
const T0: u64 = 1_764_120_000;
const PREVIOUS: &str = "1f2a9320000000000000000000000000000000ab";
const CURRENT: &str = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";

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

fn alert(at: u64) -> Alert {
    Alert {
        name: "backend error logs".to_owned(),
        service: Service::new("backend"),
        starts_at_unix: at,
    }
}

fn report(grounded: bool) -> Report {
    let evidence = Evidence {
        logs: vec![LogLine {
            service: Service::new("backend"),
            at_unix: T0 + 5,
            text: "ERROR c.t.p.TaskSearchService : NullPointerException".to_owned(),
        }],
        ..Evidence::default()
    };
    let answer = if grounded {
        Answer(
            r#"{"cause":"검색 null 처리 누락","evidence":"NPE 반복","confidence":"high","cited_lines":[1]}"#,
        )
    } else {
        Answer(r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#)
    };
    triage(
        &[alert(T0)],
        &Knowledge::default(),
        &Fixed(evidence),
        &At(T0 + 60),
        &answer,
    )
    .remove(0)
}

fn deploy(scope: DeployScope, at: u64) -> Option<Deploy> {
    Some(Deploy {
        sha: GitSha::try_from(CURRENT.to_owned()).unwrap(),
        previous_sha: GitSha::try_from(PREVIOUS.to_owned()).unwrap(),
        scope,
        at_unix: at,
    })
}

fn settings() -> Settings {
    Settings {
        owner: DiscordUserId::try_from(OWNER.to_owned()).unwrap(),
        daily_limit: 20,
        restart_after_secs: 900,
        button_ttl_secs: 600,
        rollback_window_secs: 86_400,
    }
}

fn incidents() -> Incidents {
    Incidents::open_in_memory(settings()).unwrap()
}

fn approval(action: ProposedAction, by: &str, issued_at: u64) -> Event {
    Event::ApprovalRequested(Approval {
        incident: 1,
        action,
        by: DiscordUserId::try_from(by.to_owned()).unwrap(),
        code: TotpCode::try_from("042137".to_owned()).unwrap(),
        button_issued_at_unix: issued_at,
    })
}

fn opened(incidents: &mut Incidents, grounded: bool, last_deploy: Option<Deploy>) {
    incidents
        .handle(Event::AlertsReceived(vec![alert(T0)]), T0)
        .unwrap();
    incidents
        .handle(
            Event::Analyzed {
                report: report(grounded),
                last_deploy,
            },
            T0 + 60,
        )
        .unwrap();
}

fn proposals(commands: &[Command]) -> Vec<ProposedAction> {
    commands
        .iter()
        .filter_map(|c| match c {
            Command::Post(Notice::Proposal { action, .. }) => Some(action.clone()),
            _ => None,
        })
        .collect()
}

fn requests(commands: &[Command]) -> usize {
    commands
        .iter()
        .filter(|c| matches!(c, Command::WriteRequest(_)))
        .count()
}

fn restarted(incidents: &mut Incidents) {
    incidents.handle(Event::Tick, T0 + 960).unwrap();
    let written = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 960),
            T0 + 970,
        )
        .unwrap();
    let request_id = written
        .iter()
        .find_map(|c| match c {
            Command::WriteRequest(request) => Some(request.request_id().clone()),
            _ => None,
        })
        .expect("restart request");
    incidents
        .handle(
            Event::ExecutionResult(contract::ActionResult::new(
                request_id,
                Outcome::Executed {},
                T0 + 990,
            )),
            T0 + 990,
        )
        .unwrap();
}

#[test]
fn new_alerts_are_analyzed_once_and_an_ongoing_incident_is_not_analyzed_again() {
    let mut incidents = incidents();

    let first = incidents
        .handle(Event::AlertsReceived(vec![alert(T0)]), T0)
        .unwrap();
    let again = incidents
        .handle(Event::AlertsReceived(vec![alert(T0 + 120)]), T0 + 120)
        .unwrap();

    assert!(matches!(first.as_slice(), [Command::Analyze(_)]));
    assert!(again.is_empty());
}

#[test]
fn over_the_daily_limit_alerts_are_posted_without_analysis() {
    let mut incidents = Incidents::open_in_memory(Settings {
        daily_limit: 1,
        ..settings()
    })
    .unwrap();
    incidents
        .handle(Event::AlertsReceived(vec![alert(T0)]), T0)
        .unwrap();
    incidents
        .handle(
            Event::AlertsResolved(vec![Service::new("backend")]),
            T0 + 10,
        )
        .unwrap();

    let second = incidents
        .handle(Event::AlertsReceived(vec![alert(T0 + 20)]), T0 + 20)
        .unwrap();

    assert!(matches!(
        second.as_slice(),
        [Command::Post(Notice::LimitExceeded(_))]
    ));
}

#[test]
fn restart_is_proposed_only_after_fifteen_unresolved_minutes_with_a_grounded_guess() {
    let mut grounded = incidents();
    opened(&mut grounded, true, None);
    let early = grounded.handle(Event::Tick, T0 + 600).unwrap();
    let late = grounded.handle(Event::Tick, T0 + 960).unwrap();
    let again = grounded.handle(Event::Tick, T0 + 1_020).unwrap();

    let mut ungrounded = incidents();
    opened(&mut ungrounded, false, None);
    let unknown = ungrounded.handle(Event::Tick, T0 + 960).unwrap();

    assert!(proposals(&early).is_empty());
    assert_eq!(proposals(&late), vec![ProposedAction::RestartBackend]);
    assert!(proposals(&again).is_empty());
    assert!(proposals(&unknown).is_empty());
}

#[test]
fn resolved_incident_gets_no_restart_proposal() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);
    incidents
        .handle(
            Event::AlertsResolved(vec![Service::new("backend")]),
            T0 + 300,
        )
        .unwrap();

    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();

    assert!(proposals(&tick).is_empty());
}

#[test]
fn rollback_is_proposed_when_it_recurs_after_restart_following_an_image_deploy() {
    let mut incidents = incidents();
    opened(
        &mut incidents,
        true,
        deploy(DeployScope::Backend, T0 - 3_600),
    );
    restarted(&mut incidents);

    let recur = incidents
        .handle(Event::AlertsReceived(vec![alert(T0 + 1_200)]), T0 + 1_200)
        .unwrap();

    assert!(recur.iter().any(|c| matches!(
        c,
        Command::Post(Notice::Proposal { action: ProposedAction::Rollback { target_sha }, .. }) if target_sha.as_str() == PREVIOUS
    )));
}

#[test]
fn schema_changing_deploy_gets_a_manual_notice_instead_of_rollback() {
    for scope in [DeployScope::Full, DeployScope::Migration] {
        let mut incidents = incidents();
        opened(&mut incidents, true, deploy(scope, T0 - 3_600));
        restarted(&mut incidents);

        let recur = incidents
            .handle(Event::AlertsReceived(vec![alert(T0 + 1_200)]), T0 + 1_200)
            .unwrap();

        assert!(
            recur
                .iter()
                .any(|c| matches!(c, Command::Post(Notice::ManualOnly { .. }))),
            "{scope:?}"
        );
        assert!(
            !recur
                .iter()
                .any(|c| matches!(c, Command::Post(Notice::Proposal { .. }))),
            "{scope:?}"
        );
    }
}

#[test]
fn recurrence_without_a_recent_deploy_gets_no_rollback() {
    let mut incidents = incidents();
    opened(
        &mut incidents,
        true,
        deploy(DeployScope::Backend, T0 - 200_000),
    );
    restarted(&mut incidents);

    let recur = incidents
        .handle(Event::AlertsReceived(vec![alert(T0 + 1_200)]), T0 + 1_200)
        .unwrap();

    assert!(
        !recur
            .iter()
            .any(|c| matches!(c, Command::Post(Notice::Proposal { .. })))
    );
}

#[test]
fn approval_from_someone_else_writes_no_request() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);
    incidents.handle(Event::Tick, T0 + 960).unwrap();

    let commands = incidents
        .handle(
            approval(ProposedAction::RestartBackend, "999", T0 + 960),
            T0 + 970,
        )
        .unwrap();

    assert_eq!(requests(&commands), 0);
}

#[test]
fn expired_button_writes_no_request() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);
    incidents.handle(Event::Tick, T0 + 960).unwrap();

    let commands = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 960),
            T0 + 1_561,
        )
        .unwrap();

    assert_eq!(requests(&commands), 0);
}

#[test]
fn the_same_approval_writes_a_request_only_once() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);
    incidents.handle(Event::Tick, T0 + 960).unwrap();

    let first = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 960),
            T0 + 970,
        )
        .unwrap();
    let second = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 960),
            T0 + 975,
        )
        .unwrap();

    assert_eq!((requests(&first), requests(&second)), (1, 0));
}

#[test]
fn unproposed_action_writes_no_request() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);

    let commands = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 100),
            T0 + 110,
        )
        .unwrap();

    assert_eq!(requests(&commands), 0);
}

#[test]
fn verdict_with_the_actual_cause_goes_into_the_document_draft() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);

    let commands = incidents
        .handle(
            Event::VerdictGiven {
                incident: 1,
                by: DiscordUserId::try_from(OWNER.to_owned()).unwrap(),
                verdict: Verdict::Wrong {
                    actual_cause: "Hikari 풀 크기 설정 누락".to_owned(),
                },
            },
            T0 + 300,
        )
        .unwrap();

    assert!(commands.iter().any(|c| matches!(c, Command::SaveDraft { markdown, .. } if markdown.contains("Hikari 풀 크기 설정 누락"))));
}

#[test]
fn execution_result_is_reported() {
    let mut incidents = incidents();
    opened(&mut incidents, true, None);
    incidents.handle(Event::Tick, T0 + 960).unwrap();
    let written = incidents
        .handle(
            approval(ProposedAction::RestartBackend, OWNER, T0 + 960),
            T0 + 970,
        )
        .unwrap();
    let Some(Command::WriteRequest(request)) = written
        .iter()
        .find(|c| matches!(c, Command::WriteRequest(_)))
    else {
        panic!("no request");
    };

    let commands = incidents
        .handle(
            Event::ExecutionResult(contract::ActionResult::new(
                request.request_id().clone(),
                Outcome::Failed {
                    reason: "docker exited 1".to_owned(),
                },
                T0 + 990,
            )),
            T0 + 990,
        )
        .unwrap();

    assert!(commands.iter().any(|c| matches!(
        c,
        Command::Post(Notice::ActionFinished {
            outcome: Outcome::Failed { .. },
            ..
        })
    )));
}
