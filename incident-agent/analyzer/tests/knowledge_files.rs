use std::path::Path;

use analyzer::{KnowledgeIssue, load_knowledge};

fn fixtures() -> (analyzer::Knowledge, Vec<KnowledgeIssue>) {
    load_knowledge(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/knowledge")
            .as_path(),
    )
}

fn issue_for(issues: &[KnowledgeIssue], file: &str) -> Option<String> {
    issues
        .iter()
        .find(|i| i.file.ends_with(file))
        .map(|i| i.reason.clone())
}

#[test]
fn confirmed_document_is_loaded_with_its_signature_and_source() {
    let (knowledge, _) = fixtures();

    assert_eq!(knowledge.incidents.len(), 1);
    assert_eq!(
        knowledge.incidents[0].signature,
        "ERROR c.taskflow.demo.DemoCleanupScheduler : Demo cleanup failed. errorType="
    );
    assert!(knowledge.incidents[0].source.ends_with("confirmed.md"));
}

#[test]
fn unconfirmed_draft_is_not_used_and_says_why() {
    let (_, issues) = fixtures();

    assert!(
        issue_for(&issues, "draft.md")
            .expect("draft issue")
            .contains("확정")
    );
}

#[test]
fn documents_with_missing_sections_or_unsafe_signatures_are_rejected_with_a_reason() {
    let (_, issues) = fixtures();

    assert!(
        issue_for(&issues, "missing-cause.md")
            .expect("missing")
            .contains("원인")
    );
    assert!(
        issue_for(&issues, "broad-signature.md")
            .expect("broad")
            .contains("식별 패턴")
    );
    assert!(
        issue_for(&issues, "unnormalized.md")
            .expect("unnormalized")
            .contains("식별 패턴")
    );
}

#[test]
fn every_document_in_the_repository_knowledge_folder_is_well_formed() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../knowledge");
    let (_, issues) = load_knowledge(&dir);

    let malformed: Vec<_> = issues
        .iter()
        .filter(|i| !i.reason.contains("확정 대기"))
        .collect();
    assert!(malformed.is_empty(), "{malformed:?}");
}
