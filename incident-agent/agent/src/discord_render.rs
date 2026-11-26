use analyzer::{Analysis, Confidence, Report, SkipReason};
use contract::Outcome;
use incident::{Notice, ProposedAction};

use crate::custom_id::CustomId;

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Button {
    pub(crate) label: String,
    pub(crate) custom_id: CustomId,
    pub(crate) danger: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Message {
    pub(crate) text: String,
    pub(crate) buttons: Vec<Button>,
}

const MAX_TEXT_CHARS: usize = 1900;

fn truncated(text: String) -> String {
    if text.chars().count() <= MAX_TEXT_CHARS {
        return text;
    }
    text.chars().take(MAX_TEXT_CHARS).collect::<String>() + "…"
}

fn plain(text: String) -> Message {
    Message {
        text: truncated(text),
        buttons: Vec::new(),
    }
}

fn confidence_name(confidence: Confidence) -> &'static str {
    match confidence {
        Confidence::High => "높음",
        Confidence::Medium => "중간",
    }
}

fn skip_reason(reason: SkipReason) -> &'static str {
    match reason {
        SkipReason::ModelTimeout => "모델 응답 시간 초과",
        SkipReason::ModelUnavailable => "모델 사용 불가",
        SkipReason::InvalidAnswer => "모델 답이 형식에 맞지 않음",
        SkipReason::UnsupportedCitation => "모델 답의 근거를 확인하지 못함",
        SkipReason::NoEvidence => "증거 없음",
        SkipReason::KnownIncident => "알려진 장애",
        SkipReason::CallLimit => "분석 호출 한도",
    }
}

fn analysis_text(analysis: &Analysis) -> String {
    match analysis {
        Analysis::Guess(guess) => format!(
            "원인 추정: {}\n근거: {}\n신뢰도: {}",
            guess.cause(),
            guess.evidence(),
            confidence_name(guess.confidence())
        ),
        Analysis::Unknown => "원인을 추정하지 못했다".to_owned(),
        Analysis::Skipped(reason) => format!("분석 건너뜀: {}", skip_reason(*reason)),
    }
}

fn render_incident(incident: u64, report: &Report) -> Message {
    let names: Vec<&str> = report
        .alerts
        .iter()
        .map(|alert| alert.name.as_str())
        .collect();
    let mut text = format!(
        "**장애 #{incident}** {}\n{}",
        names.join(", "),
        analysis_text(&report.analysis)
    );
    if let Some(known) = &report.known_incident {
        text.push_str(&format!("\n알려진 장애: {}", known.source));
    }
    let buttons = if matches!(report.analysis, Analysis::Guess(_)) {
        vec![
            Button {
                label: "맞음".to_owned(),
                custom_id: CustomId::Correct { incident },
                danger: false,
            },
            Button {
                label: "틀림".to_owned(),
                custom_id: CustomId::Wrong { incident },
                danger: false,
            },
        ]
    } else {
        Vec::new()
    };
    Message {
        text: truncated(text),
        buttons,
    }
}

fn render_proposal(incident: u64, action: &ProposedAction, issued_at_unix: u64) -> Message {
    let (summary, label) = match action {
        ProposedAction::RestartBackend => ("backend 재시작".to_owned(), "재시작 승인"),
        ProposedAction::Rollback { target_sha } => (
            format!("{} 로 되돌리기", &target_sha.as_str()[..7]),
            "되돌리기 승인",
        ),
    };
    Message {
        text: format!("**조치 제안 #{incident}** {summary}"),
        buttons: vec![Button {
            label: label.to_owned(),
            custom_id: CustomId::Act {
                incident,
                action: action.clone(),
                issued_at_unix,
            },
            danger: true,
        }],
    }
}

fn outcome_text(outcome: &Outcome) -> String {
    match outcome {
        Outcome::Executed {} => "조치를 실행했다".to_owned(),
        Outcome::Rejected { reason } => format!("조치가 거절됐다: {reason}"),
        Outcome::Failed { reason } => format!("조치가 실패했다: {reason}"),
    }
}

pub(crate) fn render(notice: &Notice) -> Message {
    match notice {
        Notice::Incident { incident, report } => render_incident(*incident, report),
        Notice::Proposal {
            incident,
            action,
            issued_at_unix,
        } => render_proposal(*incident, action, *issued_at_unix),
        Notice::ManualOnly { incident, reason } => {
            plain(format!("**수동 조치 필요 #{incident}** {reason}"))
        }
        Notice::LimitExceeded(alerts) => plain(format!(
            "알림 {}건이 하루 분석 한도를 넘어 분석하지 않았다",
            alerts.len()
        )),
        Notice::ActionFinished { incident, outcome } => plain(format!(
            "**조치 결과 #{incident}** {}",
            outcome_text(outcome)
        )),
    }
}

#[cfg(test)]
mod tests {
    use contract::GitSha;

    use super::*;
    use crate::test_support::{T0, alert, report};

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
        let message = render(&Notice::Incident {
            incident: 7,
            report,
        });
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
        let message = render(&Notice::Incident {
            incident: 8,
            report,
        });
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
        let message = render(&notice);
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
        let message = render(&notice);
        assert!(message.text.contains("되돌리기"));
        assert!(message.text.contains(&SHA[..7]));
        assert!(!message.text.contains(SHA));
    }

    #[test]
    fn t39_수동_전용과_한도_초과와_조치_결과는_버튼이_없다() {
        let manual = render(&Notice::ManualOnly {
            incident: 3,
            reason: "마이그레이션 배포 직후".to_owned(),
        });
        assert!(manual.text.contains("마이그레이션 배포 직후"));
        let limit = render(&Notice::LimitExceeded(vec![alert("backend", T0)]));
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
            let message = render(&Notice::ActionFinished {
                incident: 3,
                outcome,
            });
            assert!(message.text.contains(word), "{}", message.text);
            assert!(message.buttons.is_empty());
        }
        assert!(manual.buttons.is_empty() && limit.buttons.is_empty());
    }
}
