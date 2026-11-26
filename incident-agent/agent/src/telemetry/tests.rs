use std::sync::Arc;
use std::time::Duration;

use analyzer::{Analysis, Model, ModelError, SkipReason};
use contract::Outcome;
use incident::{Command, Event, Notice, Verdict};

use super::*;
use crate::test_support::{T0, alert, report};

fn after_commands(commands: &[Command]) -> String {
    let telemetry = Telemetry::default();
    for command in commands {
        telemetry.observe_command(command);
    }
    telemetry.render()
}

fn incident(analysis: Analysis) -> Command {
    let mut report = report(&[alert("backend", T0)], false);
    report.analysis = analysis;
    Command::Post(Notice::Incident {
        incident: 1,
        report,
    })
}

fn finished(outcome: Outcome) -> Command {
    Command::Post(Notice::ActionFinished {
        incident: 1,
        outcome,
    })
}

fn save_draft() -> Command {
    Command::SaveDraft {
        incident: 1,
        markdown: String::new(),
    }
}

#[test]
fn t43_카운터를_이름별_타입과_이스케이프한_라벨로_보인다() {
    let telemetry = Telemetry::default();
    telemetry.add("x_total", &[("a", "b\"c\\d\ne")], 2.0);
    telemetry.add("x_total", &[("a", "b\"c\\d\ne")], 2.0);
    let text = telemetry.render();
    assert!(
        text.contains("# TYPE x_total counter\nx_total{a=\"b\\\"c\\\\d\\ne\"} 4\n"),
        "{text}"
    );
}

#[test]
fn t43_모든_라벨_조합을_0으로_미리_보인다() {
    let text = Telemetry::default().render();
    for series in [
        "incident_agent_alerts_received_total 0",
        "incident_agent_analyses_total{result=\"skipped\",reason=\"call_limit\"} 0",
        "incident_agent_verdicts_total{verdict=\"wrong\"} 0",
        "incident_agent_action_requests_total 0",
        "incident_agent_action_results_total{outcome=\"failed\"} 0",
        "incident_agent_limit_exceeded_total 0",
        "incident_agent_model_calls_total{result=\"timeout\"} 0",
        "incident_agent_model_seconds_total{result=\"ok\"} 0",
        "incident_agent_discord_failures_total 0",
    ] {
        assert!(text.contains(series), "{series} not in {text}");
    }
}

#[test]
fn t43_받은_알림을_알림_수만큼_센다() {
    let telemetry = Telemetry::default();
    telemetry.observe_event(&Event::AlertsReceived(vec![
        alert("backend", T0),
        alert("nginx", T0),
    ]));
    let text = telemetry.render();
    assert!(
        text.contains("incident_agent_alerts_received_total 2"),
        "{text}"
    );
}

#[test]
fn t43_사건_알림으로_나간_분석_결과를_종류별로_센다() {
    let text = after_commands(&[
        incident(report(&[alert("backend", T0)], true).analysis),
        incident(Analysis::Unknown),
        incident(Analysis::Skipped(SkipReason::ModelTimeout)),
    ]);
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
fn t43_실행_결과를_결과별로_센다() {
    let text = after_commands(&[
        finished(Outcome::Executed {}),
        finished(Outcome::Rejected {
            reason: "x".to_owned(),
        }),
        finished(Outcome::Failed {
            reason: "y".to_owned(),
        }),
        finished(Outcome::Failed {
            reason: "z".to_owned(),
        }),
    ]);
    for series in [
        "incident_agent_action_results_total{outcome=\"executed\"} 1",
        "incident_agent_action_results_total{outcome=\"rejected\"} 1",
        "incident_agent_action_results_total{outcome=\"failed\"} 2",
    ] {
        assert!(text.contains(series), "{series} not in {text}");
    }
}

#[test]
fn t43_받아들여진_판정만_센다() {
    let telemetry = Telemetry::default();
    telemetry.observe_verdict(Some(&Verdict::Correct), &[save_draft()]);
    telemetry.observe_verdict(
        Some(&Verdict::Wrong {
            actual_cause: "x".to_owned(),
        }),
        &[],
    );
    let text = telemetry.render();
    assert!(
        text.contains("incident_agent_verdicts_total{verdict=\"correct\"} 1"),
        "{text}"
    );
    assert!(
        text.contains("incident_agent_verdicts_total{verdict=\"wrong\"} 0"),
        "{text}"
    );
}

#[test]
fn t43_하루_상한_초과_알림을_센다() {
    let text = after_commands(&[Command::Post(Notice::LimitExceeded(vec![alert(
        "backend", T0,
    )]))]);
    assert!(
        text.contains("incident_agent_limit_exceeded_total 1"),
        "{text}"
    );
}

struct Answers(Result<String, ModelError>, Duration);

impl Model for Answers {
    fn explain(&self, _: &str) -> Result<String, ModelError> {
        std::thread::sleep(self.1);
        self.0.clone()
    }
}

fn series_value(text: &str, series: &str) -> f64 {
    text.lines()
        .find_map(|line| line.strip_prefix(series)?.trim().parse().ok())
        .unwrap_or_else(|| panic!("{series} not in {text}"))
}

#[test]
fn t43_모델_호출을_결과별로_세고_결과별_시간을_더한다() {
    let telemetry = Arc::new(Telemetry::default());
    let slow = Duration::from_millis(30);
    let ok = Timed::new(Answers(Ok("{}".to_owned()), slow), Arc::clone(&telemetry));
    let timeout = Timed::new(
        Answers(Err(ModelError::Timeout), Duration::ZERO),
        Arc::clone(&telemetry),
    );
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
    let ok_seconds = series_value(&text, "incident_agent_model_seconds_total{result=\"ok\"}");
    assert!(ok_seconds >= 0.03, "{text}");
}
