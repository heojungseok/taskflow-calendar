use crate::model::{Evidence, Knowledge};

pub(crate) fn match_known(knowledge: &Knowledge, evidence: &Evidence) -> Option<String> {
    knowledge
        .incidents
        .iter()
        .find(|known| {
            evidence
                .logs
                .iter()
                .any(|line| line.text.contains(&known.signature))
        })
        .map(|known| known.source.clone())
}
