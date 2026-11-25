mod common;

use analyzer::{
    Analysis, Confidence, Evidence, Knowledge, LogLine, MetricSeries, Missing, ModelError, Report,
    Service, SkipReason, Suspect, triage,
};
use common::{FixedClock, FixedEvidence, ScriptedModel, alert};

fn log(at_unix: u64, text: &str) -> LogLine {
    LogLine {
        service: Service::new("backend"),
        at_unix,
        text: text.to_owned(),
    }
}

fn evidence() -> Evidence {
    Evidence {
        logs: vec![log(
            1_010,
            "ERROR c.t.p.TaskSearchService : NullPointerException for task 101",
        )],
        ..Evidence::default()
    }
}

fn run(evidence: Evidence, model: &ScriptedModel) -> Report {
    triage(
        &[alert("backend error logs", "backend", 1_000)],
        &Knowledge::default(),
        &FixedEvidence(evidence),
        &FixedClock(1_200),
        model,
    )
    .remove(0)
}

fn answer(confidence: &str, cited_lines: &str) -> String {
    format!(
        r#"{{"cause":"검색 서비스 null 처리 누락","evidence":"14:01부터 NPE 반복","confidence":"{confidence}","cited_lines":{cited_lines}}}"#
    )
}

fn has_new_error(report: &Report) -> bool {
    report
        .suspects
        .iter()
        .any(|s| matches!(s, Suspect::NewError { .. }))
}

#[test]
fn grounded_answer_becomes_a_guess_citing_evidence_lines() {
    let report = run(
        evidence(),
        &ScriptedModel::answering(&answer("high", "[1]")),
    );

    assert_eq!(
        report.analysis,
        Analysis::Guess {
            cause: "검색 서비스 null 처리 누락".to_owned(),
            evidence: "14:01부터 NPE 반복".to_owned(),
            confidence: Confidence::High,
            cited_lines: vec![1],
        }
    );
}

#[test]
fn prompt_does_not_carry_emails_tokens_or_long_visitor_text() {
    let model = ScriptedModel::unknown();
    let evidence = Evidence {
        logs: vec![log(
            1_010,
            r#"ERROR c.t.p.TaskService : save failed user=visitor@gmail.com token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl title="병원 예약 김철수 내일 오후 세시""#,
        )],
        ..Evidence::default()
    };

    run(evidence, &model);

    let prompt = model.prompts.borrow().join("\n");
    for secret in ["visitor@gmail.com", "eyJhbGci", "김철수"] {
        assert!(!prompt.contains(secret), "{secret} leaked");
    }
    assert!(prompt.contains("TaskService"));
}

#[test]
fn model_timeout_keeps_only_the_suspects() {
    let report = run(evidence(), &ScriptedModel::failing(ModelError::Timeout));

    assert_eq!(report.analysis, Analysis::Skipped(SkipReason::ModelTimeout));
    assert!(has_new_error(&report));
}

#[test]
fn answer_citing_lines_that_are_not_in_the_evidence_is_dropped() {
    for cited in ["[99]", "[]"] {
        let report = run(
            evidence(),
            &ScriptedModel::answering(&answer("high", cited)),
        );

        assert_eq!(
            report.analysis,
            Analysis::Skipped(SkipReason::UnsupportedCitation),
            "{cited}"
        );
    }
}

#[test]
fn answer_that_breaks_the_json_shape_is_dropped() {
    for broken in [
        "the cause is a null pointer",
        r#"{"cause":"x","confidence":"sure","cited_lines":[1]}"#,
    ] {
        let report = run(evidence(), &ScriptedModel::answering(broken));

        assert_eq!(
            report.analysis,
            Analysis::Skipped(SkipReason::InvalidAnswer),
            "{broken}"
        );
    }
}

#[test]
fn low_confidence_says_unknown_and_still_hands_over_the_suspects() {
    let report = run(evidence(), &ScriptedModel::answering(&answer("low", "[1]")));

    assert_eq!(report.analysis, Analysis::Unknown);
    assert!(has_new_error(&report));
}

#[test]
fn unreadable_logs_still_get_a_metric_only_analysis_marked_as_such() {
    let model = ScriptedModel::answering(&answer("medium", "[1]"));
    let evidence = Evidence {
        metrics: vec![MetricSeries {
            name: "jvm heap".to_owned(),
            baseline: 0.4,
            floor: 0.0,
            points: vec![(1_010, 0.9)],
        }],
        missing: vec![Missing::Logs],
        ..Evidence::default()
    };

    let report = run(evidence, &model);

    assert!(matches!(
        report.analysis,
        Analysis::Guess {
            confidence: Confidence::Medium,
            ..
        }
    ));
    assert!(model.prompts.borrow()[0].contains("로그를 읽지 못함"));
}
