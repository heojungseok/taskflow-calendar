use serde::Deserialize;

use crate::evidence_lines::EvidenceLine;
use crate::model::{Analysis, Confidence, Guess, SkipReason};
use crate::ports::{Model, ModelError};
use crate::prompt::Prompt;

const MAX_FIELD_CHARS: usize = 300;

pub const ANSWER_SCHEMA: &str = r#"{"type":"object","properties":{"cause":{"type":"string"},"evidence":{"type":"string"},"confidence":{"type":"string","enum":["high","medium","low"]},"cited_lines":{"type":"array","items":{"type":"integer","minimum":1}}},"required":["cause","evidence","confidence","cited_lines"],"additionalProperties":false}"#;

#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
enum RawConfidence {
    High,
    Medium,
    Low,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawAnswer {
    cause: String,
    evidence: String,
    confidence: RawConfidence,
    cited_lines: Vec<usize>,
}

pub(crate) fn analyze(model: &impl Model, prompt: &Prompt) -> Analysis {
    match model.explain(&prompt.text) {
        Ok(answer) => interpret(&answer, &prompt.lines),
        Err(ModelError::Timeout) => Analysis::Skipped(SkipReason::ModelTimeout),
        Err(ModelError::Unavailable(_)) => Analysis::Skipped(SkipReason::ModelUnavailable),
    }
}

fn interpret(answer: &str, lines: &[EvidenceLine]) -> Analysis {
    let Ok(raw) = serde_json::from_str::<RawAnswer>(answer) else {
        return Analysis::Skipped(SkipReason::InvalidAnswer);
    };
    let confidence = match raw.confidence {
        RawConfidence::Low => return Analysis::Unknown,
        RawConfidence::High => Confidence::High,
        RawConfidence::Medium => Confidence::Medium,
    };
    let (Some(cause), Some(evidence)) = (usable(&raw.cause), usable(&raw.evidence)) else {
        return Analysis::Skipped(SkipReason::InvalidAnswer);
    };
    let grounded = !raw.cited_lines.is_empty()
        && raw
            .cited_lines
            .iter()
            .all(|n| (1..=lines.len()).contains(n));
    if !grounded {
        return Analysis::Skipped(SkipReason::UnsupportedCitation);
    }
    let mut cited: Vec<usize> = Vec::new();
    for n in raw.cited_lines {
        if !cited.contains(&n) {
            cited.push(n);
        }
    }
    let cited_lines_of =
        |pred: fn(&EvidenceLine) -> bool| cited.iter().any(|n| pred(&lines[n - 1]));
    if !cited_lines_of(|line| line.picked) {
        return Analysis::Unknown;
    }
    let confidence = if cited_lines_of(|line| line.is_log) {
        confidence
    } else {
        Confidence::Medium
    };
    Analysis::Guess(Guess::new(cause, evidence, confidence, cited))
}

fn usable(text: &str) -> Option<String> {
    let trimmed = text.trim();
    (!trimmed.is_empty() && trimmed != "..." && trimmed.chars().count() <= MAX_FIELD_CHARS)
        .then(|| trimmed.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schema_is_valid_json_listing_every_answer_field() {
        let schema: serde_json::Value = serde_json::from_str(ANSWER_SCHEMA).unwrap();
        let required: Vec<&str> = schema["required"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|v| v.as_str())
            .collect();

        assert_eq!(required, ["cause", "evidence", "confidence", "cited_lines"]);
        assert_eq!(schema["additionalProperties"], false);
    }
}
