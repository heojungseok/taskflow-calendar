use crate::fingerprint::fingerprint;
use crate::model::{Evidence, Knowledge, KnownMatch};

pub(crate) fn match_known(knowledge: &Knowledge, evidence: &Evidence) -> Option<KnownMatch> {
    let fingerprints: Vec<String> = evidence
        .logs
        .iter()
        .filter_map(|line| fingerprint(&line.text))
        .map(|f| f.text)
        .collect();
    knowledge
        .incidents()
        .iter()
        .find(|known| {
            fingerprints
                .iter()
                .any(|text| text.starts_with(known.signature()))
        })
        .map(|known| KnownMatch {
            source: known.source().to_owned(),
            visitor_triggerable: known.visitor_triggerable(),
        })
}
