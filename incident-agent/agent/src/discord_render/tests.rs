use contract::GitSha;

use super::*;
use crate::test_support::{T0, alert, report, report_answering};
use analyzer::{Analysis, ErrorGroup, Missing, Suspect};

const SHA: &str = "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69";

fn sha() -> GitSha {
    GitSha::try_from(SHA.to_owned()).unwrap()
}

fn ids(message: &Message) -> Vec<&CustomId> {
    message.buttons.iter().map(|b| &b.custom_id).collect()
}

#[test]
fn t39_원인_추정_사건은_맞음_틀림_버튼과_근거를_담는다() {
    let report = report(&[alert("backend", T0)], true);
    let message = render(
        &Notice::Incident {
            incident: 7,
            report,
        },
        None,
    );
    assert!(message.text.contains("#7"));
    assert!(message.text.contains("검색 null 처리 누락"));
    assert!(message.text.contains("NPE 반복"));
    assert_eq!(
        ids(&message),
        vec![
            &CustomId::Correct { incident: 7 },
            &CustomId::Wrong { incident: 7 }
        ]
    );
}

#[test]
fn t39_원인을_모르는_사건은_버튼이_없다() {
    let report = report(&[alert("backend", T0)], false);
    let message = render(
        &Notice::Incident {
            incident: 8,
            report,
        },
        None,
    );
    assert!(message.text.contains("#8"));
    assert!(message.buttons.is_empty());
}

#[test]
fn t39_재시작_제안은_승인_버튼_하나다() {
    let notice = Notice::Proposal {
        incident: 7,
        action: ProposedAction::RestartBackend,
        issued_at_unix: T0,
    };
    let message = render(&notice, None);
    assert!(message.text.contains("재시작"));
    let expected = CustomId::Act {
        incident: 7,
        action: ProposedAction::RestartBackend,
        issued_at_unix: T0,
    };
    assert_eq!(ids(&message), vec![&expected]);
    assert!(message.buttons[0].danger);
}

#[test]
fn t39_되돌리기_제안은_짧은_sha를_보여준다() {
    let notice = Notice::Proposal {
        incident: 7,
        action: ProposedAction::Rollback { target_sha: sha() },
        issued_at_unix: T0,
    };
    let message = render(&notice, None);
    assert!(message.text.contains("되돌리기"));
    assert!(message.text.contains(&SHA[..7]));
    assert!(!message.text.contains(SHA));
}

#[test]
fn t39_수동_전용과_한도_초과와_조치_결과는_버튼이_없다() {
    let manual = render(
        &Notice::ManualOnly {
            incident: 3,
            reason: "마이그레이션 배포 직후".to_owned(),
        },
        None,
    );
    assert!(manual.text.contains("마이그레이션 배포 직후"));
    let limit = render(&Notice::LimitExceeded(vec![alert("backend", T0)]), None);
    assert!(limit.text.contains("한도"));
    for (outcome, word) in [
        (Outcome::Executed {}, "실행"),
        (
            Outcome::Rejected {
                reason: "만료".to_owned(),
            },
            "만료",
        ),
        (
            Outcome::Failed {
                reason: "docker".to_owned(),
            },
            "docker",
        ),
    ] {
        let message = render(
            &Notice::ActionFinished {
                incident: 3,
                outcome,
            },
            None,
        );
        assert!(message.text.contains(word), "{}", message.text);
        assert!(message.buttons.is_empty());
    }
    assert!(manual.buttons.is_empty() && limit.buttons.is_empty());
}

#[test]
fn t39_모델이_만든_글과_경보_이름의_마크다운과_멘션은_이스케이프한다() {
    let answer = r#"{"cause":"**굵게** @everyone [x](http://e.test) <@123>","evidence":"a_b `code`","confidence":"high","cited_lines":[1]}"#;
    let mut report = report_answering(&[alert("backend", T0)], Some(answer));
    report.alerts[0].name = "slow_*query".to_owned();
    let message = render(
        &Notice::Incident {
            incident: 7,
            report,
        },
        None,
    );
    assert!(message.text.contains(r"\*\*굵게\*\*"), "{}", message.text);
    assert!(message.text.contains(r"\@everyone"));
    assert!(message.text.contains(r"\[x\]\(http://e.test\)"));
    assert!(message.text.contains(r"\<\@123\>"));
    assert!(message.text.contains(r"a\_b \`code\`"));
    assert!(message.text.contains(r"slow\_\*query"));
}

fn bare_report() -> analyzer::Report {
    let mut report = report(&[alert("backend", T0)], false);
    report.analysis = Analysis::Unknown;
    report
}

#[test]
fn t39_누락된_증거를_짧은_한국어_줄로_보인다() {
    let mut report = bare_report();
    report.missing = vec![Missing::Logs, Missing::Baseline, Missing::Evidence];
    let text = render(
        &Notice::Incident {
            incident: 1,
            report,
        },
        None,
    )
    .text;
    assert!(
        text.contains("누락 증거: 로그, 기준 로그, 증거 전체"),
        "{text}"
    );
    let none = render(
        &Notice::Incident {
            incident: 1,
            report: bare_report(),
        },
        None,
    )
    .text;
    assert!(!none.contains("누락 증거"));
}

#[test]
fn t39_의심_항목을_짧게_보인다() {
    let mut report = bare_report();
    report.suspects = vec![
        Suspect::NewError {
            first: ErrorGroup {
                fingerprint: "f".to_owned(),
                logger: "c.t.TaskSearchService".to_owned(),
                first_seen_unix: T0,
                count: 3,
                trace_ids: Vec::new(),
            },
            most_frequent: None,
        },
        Suspect::FirstChangedMetric {
            name: "pg_connection_ratio".to_owned(),
            changed_at_unix: T0,
        },
        Suspect::RelatedCommit {
            sha: SHA.to_owned(),
        },
    ];
    let text = render(
        &Notice::Incident {
            incident: 1,
            report,
        },
        None,
    )
    .text;
    assert!(text.contains("TaskSearchService"));
    assert!(text.contains("pg\\_connection\\_ratio") || text.contains("pg_connection_ratio"));
    assert!(text.contains(&SHA[..7]));
    assert!(!text.contains(SHA));
}
