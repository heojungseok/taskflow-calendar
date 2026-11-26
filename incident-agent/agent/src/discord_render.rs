use analyzer::{Analysis, Report};
use contract::Outcome;
use incident::{Notice, ProposedAction};

use crate::custom_id::CustomId;
use crate::discord_text::{analysis_text, missing_text, safe, suspect_text};

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
const MAX_SUSPECTS: usize = 3;
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

fn render_incident(incident: u64, report: &Report) -> Message {
    let names: Vec<String> = report
        .alerts
        .iter()
        .map(|alert| safe(&alert.name))
        .collect();
    let mut text = format!(
        "**장애 #{incident}** {}\n{}",
        names.join(", "),
        analysis_text(&report.analysis)
    );
    if let Some(known) = &report.known_incident {
        text.push_str(&format!("\n알려진 장애: {}", safe(&known.source)));
    }
    let suspects = report.suspects.iter().take(MAX_SUSPECTS).map(suspect_text);
    for line in missing_text(&report.missing).into_iter().chain(suspects) {
        text.push('\n');
        text.push_str(&line);
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
        Outcome::Rejected { reason } => format!("조치가 거절됐다: {}", safe(reason)),
        Outcome::Failed { reason } => format!("조치가 실패했다: {}", safe(reason)),
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
            plain(format!("**수동 조치 필요 #{incident}** {}", safe(reason)))
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
mod tests;
