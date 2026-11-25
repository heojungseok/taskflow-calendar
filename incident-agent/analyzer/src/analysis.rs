use serde::Deserialize;

use crate::model::{Analysis, Confidence, SkipReason};
use crate::ports::{Model, ModelError};
use crate::prompt::Prompt;

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
        Ok(answer) => interpret(&answer, prompt.line_count),
        Err(ModelError::Timeout) => Analysis::Skipped(SkipReason::ModelTimeout),
        Err(ModelError::Unavailable(_)) => Analysis::Skipped(SkipReason::ModelUnavailable),
    }
}

fn interpret(answer: &str, line_count: usize) -> Analysis {
    let Ok(raw) = serde_json::from_str::<RawAnswer>(answer) else {
        return Analysis::Skipped(SkipReason::InvalidAnswer);
    };
    let confidence = match raw.confidence {
        RawConfidence::Low => return Analysis::Unknown,
        RawConfidence::High => Confidence::High,
        RawConfidence::Medium => Confidence::Medium,
    };
    let grounded =
        !raw.cited_lines.is_empty() && raw.cited_lines.iter().all(|n| (1..=line_count).contains(n));
    if !grounded {
        return Analysis::Skipped(SkipReason::UnsupportedCitation);
    }
    Analysis::Guess {
        cause: raw.cause,
        evidence: raw.evidence,
        confidence,
        cited_lines: raw.cited_lines,
    }
}
