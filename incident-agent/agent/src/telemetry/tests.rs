use std::sync::Arc;

use analyzer::{Analysis, Model, ModelError, SkipReason};
use contract::{ActionResult, DiscordUserId, Outcome, RequestId};
use incident::{Approval, Command, Event, Notice, ProposedAction, Verdict};

use super::*;
use crate::test_support::{T0, alert, report};

fn rendered(events: &[Event]) -> String {
    let telemetry = Telemetry::default();
    for event in events {
        telemetry.observe_event(event);
    }
    telemetry.render()
}

fn owner() -> DiscordUserId {
    DiscordUserId::try_from("1234567890".to_owned()).unwrap()
}

#[test]
fn t43_카운터를_이름별_타입과_이스케이프한_라벨로_보인다() {
    let telemetry = Telemetry::default();
    telemetry.add("x_total", &[("a", "b\"c")], 2.0);
    telemetry.add("x_total", &[("a", "b\"c")], 2.0);
    telemetry.add("y_total", &[], 1.0);
    let text = telemetry.render();
    assert!(
        text.contains("# TYPE x_total counter\nx_total{a=\"b\\\"c\"} 4\n"),
        "{text}"
    );
    assert!(
        text.contains("# TYPE y_total counter\ny_total 1\n"),
        "{text}"
    );
}

#[test]
fn t43_받은_알림을_알림_수만큼_센다() {
    let text = rendered(&[Event::AlertsReceived(vec![
        alert("backend", T0),
        alert("nginx", T0),
    ])]);
    assert!(
        text.contains("incident_agent_alerts_received_total 2"),
        "{text}"
    );
}

#[test]
fn t43_분석_결과를_보고서마다_종류별로_센다() {
    let mut skipped = report(&[alert("backend", T0)], false);
    skipped.analysis = Analysis::Skipped(SkipReason::ModelTimeout);
    let text = rendered(&[Event::Analyzed {
        batch: 1,
        reports: vec![
            report(&[alert("backend", T0)], true),
            report(&[alert("backend", T0)], false),
            skipped,
        ],
        last_deploy: None,
    }]);
    assert!(
        text.contains("incident_agent_analyses_total{result=\"guess\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("incident_agent_analyses_total{result=\"unknown\"} 1"),
        "{text}"
    );
    assert!(
        text.contains(
            "incident_agent_analyses_total{result=\"skipped\",reason=\"model_timeout\"} 1"
        ),
        "{text}"
    );
}

#[test]
fn t43_판정_승인_실행_결과를_센다() {
    let request = RequestId::try_from("r-1".to_owned()).unwrap();
    let text = rendered(&[
        Event::VerdictGiven {
            incident: 1,
            by: owner(),
            verdict: Verdict::Correct,
        },
        Event::VerdictGiven {
            incident: 2,
            by: owner(),
            verdict: Verdict::Wrong {
                actual_cause: "x".to_owned(),
            },
        },
        Event::ApprovalRequested(Approval {
            incident: 1,
            action: ProposedAction::RestartBackend,
            by: owner(),
            code: contract::TotpCode::try_from("123456".to_owned()).unwrap(),
            button_issued_at_unix: T0,
        }),
        Event::ExecutionResult(ActionResult::new(request, Outcome::Executed {}, T0)),
    ]);
    assert!(
        text.contains("incident_agent_verdicts_total{verdict=\"correct\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("incident_agent_verdicts_total{verdict=\"wrong\"} 1"),
        "{text}"
    );
    assert!(text.contains("incident_agent_approvals_total 1"), "{text}");
    assert!(
        text.contains("incident_agent_action_results_total{outcome=\"executed\"} 1"),
        "{text}"
    );
}

#[test]
fn t43_하루_상한_초과_알림을_센다() {
    let telemetry = Telemetry::default();
    telemetry.observe_command(&Command::Post(Notice::LimitExceeded(vec![alert(
        "backend", T0,
    )])));
    assert!(
        telemetry
            .render()
            .contains("incident_agent_limit_exceeded_total 1"),
        "{}",
        telemetry.render()
    );
}

struct Answers(Result<String, ModelError>);

impl Model for Answers {
    fn explain(&self, _: &str) -> Result<String, ModelError> {
        self.0.clone()
    }
}

#[test]
fn t43_모델_호출을_결과별로_세고_시간을_더한다() {
    let telemetry = Arc::new(Telemetry::default());
    let ok = Timed::new(Answers(Ok("{}".to_owned())), Arc::clone(&telemetry));
    let timeout = Timed::new(Answers(Err(ModelError::Timeout)), Arc::clone(&telemetry));
    assert_eq!(ok.explain("p"), Ok("{}".to_owned()));
    assert_eq!(timeout.explain("p"), Err(ModelError::Timeout));
    let text = telemetry.render();
    assert!(
        text.contains("incident_agent_model_calls_total{result=\"ok\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("incident_agent_model_calls_total{result=\"timeout\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("# TYPE incident_agent_model_seconds_total counter"),
        "{text}"
    );
}
