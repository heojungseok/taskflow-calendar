mod common;

use common::*;
use contract::{DiscordUserId, Outcome};
use incident::{Command, Event, Incidents, Notice, ProposedAction, Verdict};

fn verdict(incidents: &mut Incidents, incident: u64, by: &str, verdict: Verdict) -> Vec<Command> {
    incidents
        .handle(
            Event::VerdictGiven {
                incident,
                by: DiscordUserId::try_from(by.to_owned()).unwrap(),
                verdict,
            },
            T0 + 300,
        )
        .unwrap()
}

fn draft(commands: &[Command]) -> Option<String> {
    commands.iter().find_map(|c| match c {
        Command::SaveDraft { markdown, .. } => Some(markdown.clone()),
        _ => None,
    })
}

#[test]
fn verdict_with_the_actual_cause_goes_into_the_document_draft_and_marks_model_text() {
    let mut incidents = incidents();
    let incident = open_backend(&mut incidents, true, None);

    let markdown = draft(&verdict(
        &mut incidents,
        incident,
        OWNER,
        Verdict::Wrong {
            actual_cause: "Hikari 풀 크기 설정 누락".to_owned(),
        },
    ))
    .expect("draft");

    assert!(markdown.contains("Hikari 풀 크기 설정 누락"));
    assert!(
        markdown.contains("> "),
        "model text should be quoted: {markdown}"
    );
}

#[test]
fn verdict_from_someone_else_is_ignored() {
    let mut incidents = incidents();
    let incident = open_backend(&mut incidents, true, None);

    assert!(verdict(&mut incidents, incident, "999", Verdict::Correct).is_empty());
}

#[test]
fn execution_result_is_reported_once() {
    let mut incidents = incidents();
    let incident = open_backend(&mut incidents, true, None);
    let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();
    let (_, _, issued) = proposals(&tick).remove(0);
    let commands = incidents
        .handle(
            approval(
                incident,
                ProposedAction::RestartBackend,
                OWNER,
                CODE,
                issued,
            ),
            issued + 10,
        )
        .unwrap();
    let request = written(&commands).remove(0);

    let first = finish(&mut incidents, &request, Outcome::Executed {}, issued + 20);
    let duplicate = finish(&mut incidents, &request, Outcome::Executed {}, issued + 21);

    assert!(first.iter().any(|c| matches!(
        c,
        Command::Post(Notice::ActionFinished {
            outcome: Outcome::Executed {},
            ..
        })
    )));
    assert!(duplicate.is_empty());
}

#[test]
fn audit_log_keeps_who_did_what_without_totp_codes_and_survives_reopening() {
    let path = std::env::temp_dir().join(format!("incident-audit-{}.db", std::process::id()));
    let _ = std::fs::remove_file(&path);
    {
        let mut incidents = Incidents::open(&path, settings()).unwrap();
        let incident = open_backend(&mut incidents, true, None);
        let tick = incidents.handle(Event::Tick, T0 + 960).unwrap();
        let (_, _, issued) = proposals(&tick).remove(0);
        incidents
            .handle(
                approval(
                    incident,
                    ProposedAction::RestartBackend,
                    "999",
                    "314159",
                    issued,
                ),
                issued + 5,
            )
            .unwrap();
        incidents
            .handle(
                approval(
                    incident,
                    ProposedAction::RestartBackend,
                    OWNER,
                    CODE,
                    issued,
                ),
                issued + 10,
            )
            .unwrap();
    }
    let reopened_tick = {
        let mut incidents = Incidents::open(&path, settings()).unwrap();
        incidents.handle(Event::Tick, T0 + 1_020).unwrap()
    };

    let bytes = std::fs::read(&path).unwrap();
    let text = String::from_utf8_lossy(&bytes);
    let _ = std::fs::remove_file(&path);
    assert!(text.contains("999"), "foreign presser should be recorded");
    assert!(
        !text.contains("314159") && !text.contains(CODE),
        "TOTP codes must not be stored"
    );
    assert!(
        proposals(&reopened_tick).is_empty(),
        "state must survive reopening"
    );
}
