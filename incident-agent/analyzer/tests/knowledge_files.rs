mod common;

use std::path::Path;

use analyzer::{IssueKind, KnowledgeIssue, load_knowledge};
use common::{knowledge_dir, knowledge_doc};

const OUTBOX: &str = "com.taskflow.calendarsync.worker.CalendarOutboxWorker";
const REFRESH: &str =
    "ERROR c.t.c.worker.CalendarOutboxWorker : [Worker] Token refresh failed for Outbox #:";
const CONFIRMED: &str = "2026-11-26 정석님";

fn fixtures() -> (analyzer::Knowledge, Vec<KnowledgeIssue>) {
    load_knowledge(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/knowledge")
            .as_path(),
    )
}

fn issue_for<'a>(issues: &'a [KnowledgeIssue], file: &str) -> &'a KnowledgeIssue {
    issues
        .iter()
        .find(|i| i.file.ends_with(file))
        .unwrap_or_else(|| panic!("no issue for {file}: {issues:?}"))
}

fn single(name: &str, text: String) -> (analyzer::Knowledge, Vec<KnowledgeIssue>) {
    load_knowledge(&knowledge_dir(name, &[("doc.md", text)]))
}

#[test]
fn confirmed_document_is_loaded_with_its_signature_and_file_name() {
    let (knowledge, _) = fixtures();

    assert_eq!(knowledge.incidents().len(), 1);
    let incident = &knowledge.incidents()[0];
    assert_eq!(
        incident.signature(),
        "ERROR c.taskflow.demo.DemoCleanupScheduler : Demo cleanup failed. errorType="
    );
    assert_eq!(incident.source(), "confirmed.md");
    assert!(!incident.visitor_triggerable());
}

#[test]
fn unconfirmed_draft_is_not_used_and_is_reported_as_a_draft() {
    let (_, issues) = fixtures();

    assert_eq!(issue_for(&issues, "draft.md").kind, IssueKind::Draft);
}

#[test]
fn documents_with_missing_sections_or_unsafe_signatures_are_rejected_with_a_reason() {
    let (_, issues) = fixtures();

    for (file, keyword) in [
        ("missing-cause.md", "원인"),
        ("broad-signature.md", "식별 패턴"),
        ("unnormalized.md", "식별 패턴"),
    ] {
        let issue = issue_for(&issues, file);
        assert_eq!(issue.kind, IssueKind::Malformed, "{file}");
        assert!(issue.reason.contains(keyword), "{file}: {}", issue.reason);
    }
}

#[test]
fn signature_with_too_short_a_message_prefix_is_rejected() {
    for signature in [
        "ERROR c.t.c.worker.CalendarOutboxWorker : [",
        "ERROR c.t.c.worker.CalendarOutboxWorker : [Worker] Token",
    ] {
        let (knowledge, issues) =
            single("short", knowledge_doc(signature, OUTBOX, "불가", CONFIRMED));

        assert!(knowledge.incidents().is_empty(), "{signature}");
        assert!(
            issues[0].reason.contains("식별 패턴"),
            "{signature}: {}",
            issues[0].reason
        );
    }
}

#[test]
fn only_the_exact_word_waiting_is_a_draft_and_other_confirmation_lines_are_malformed() {
    for confirmed in ["2026.11.26 정석님", "9999-99-99 x", "2026-11-26", "확인함"] {
        let (_, issues) = single("confirm", knowledge_doc(REFRESH, OUTBOX, "불가", confirmed));

        assert_eq!(issues[0].kind, IssueKind::Malformed, "{confirmed}");
    }
}

#[test]
fn visitor_flag_is_required_and_read() {
    let (knowledge, _) = single("visitor", knowledge_doc(REFRESH, OUTBOX, "가능", CONFIRMED));
    assert!(knowledge.incidents()[0].visitor_triggerable());

    let (_, issues) = single(
        "visitor-bad",
        knowledge_doc(REFRESH, OUTBOX, "모름", CONFIRMED),
    );
    assert!(
        issues[0].reason.contains("방문자 유발"),
        "{}",
        issues[0].reason
    );
}

#[test]
fn duplicate_header_items_and_overlapping_signatures_are_rejected() {
    let duplicated = knowledge_doc(REFRESH, OUTBOX, "불가", CONFIRMED)
        .replace("- 확정:", "- 방문자 유발: 가능\n- 확정:");
    let (_, issues) = single("duplicate", duplicated);
    assert_eq!(issues[0].kind, IssueKind::Malformed);

    let dir = knowledge_dir(
        "overlap",
        &[
            ("a.md", knowledge_doc(REFRESH, OUTBOX, "불가", CONFIRMED)),
            (
                "b.md",
                knowledge_doc(
                    &format!("{REFRESH} Refresh token"),
                    OUTBOX,
                    "불가",
                    CONFIRMED,
                ),
            ),
        ],
    );
    let (knowledge, issues) = load_knowledge(&dir);
    assert_eq!(knowledge.incidents().len(), 1);
    assert!(issues.iter().any(|i| i.reason.contains("겹")), "{issues:?}");
}

#[test]
fn file_with_a_byte_order_mark_is_read_normally() {
    let (knowledge, issues) = single(
        "bom",
        format!(
            "\u{feff}{}",
            knowledge_doc(REFRESH, OUTBOX, "불가", CONFIRMED)
        ),
    );

    assert!(issues.is_empty(), "{issues:?}");
    assert_eq!(knowledge.incidents().len(), 1);
}

#[test]
fn every_document_in_the_repository_knowledge_folder_is_well_formed() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../knowledge");
    let (_, issues) = load_knowledge(&dir);

    let malformed: Vec<_> = issues
        .iter()
        .filter(|i| i.kind != IssueKind::Draft)
        .collect();
    assert!(malformed.is_empty(), "{malformed:?}");
}
