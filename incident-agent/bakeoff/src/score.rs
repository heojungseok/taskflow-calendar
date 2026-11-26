use analyzer::{Analysis, SkipReason};

pub(crate) enum Expect {
    Cause {
        line: &'static str,
        keywords: &'static [&'static str],
    },
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Outcome {
    Correct,
    WrongCause,
    Missed,
    SaidUnknown,
    Fabricated,
    InvalidAnswer,
    UnsupportedCitation,
    Timeout,
    Unavailable,
    NoModelCall,
}

impl Outcome {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::Correct => "정답",
            Self::WrongCause => "오답",
            Self::Missed => "놓침(모르겠다)",
            Self::SaidUnknown => "정답(모르겠다)",
            Self::Fabricated => "지어냄",
            Self::InvalidAnswer => "형식 실패",
            Self::UnsupportedCitation => "근거 없음",
            Self::Timeout => "시간 초과",
            Self::Unavailable => "호출 실패",
            Self::NoModelCall => "모델 미호출",
        }
    }

    pub(crate) fn is_right(self) -> bool {
        matches!(self, Self::Correct | Self::SaidUnknown)
    }
}

pub(crate) fn score(expect: &Expect, analysis: &Analysis, prompt: &str) -> Outcome {
    let guess = match analysis {
        Analysis::Guess(guess) => guess,
        Analysis::Unknown => {
            return match expect {
                Expect::Cause { .. } => Outcome::Missed,
                Expect::Unknown => Outcome::SaidUnknown,
            };
        }
        Analysis::Skipped(reason) => return skipped(*reason),
    };
    let Expect::Cause { line, keywords } = expect else {
        return Outcome::Fabricated;
    };
    let cites_cause = guess
        .cited_lines()
        .iter()
        .filter_map(|n| cited_line(prompt, *n))
        .any(|text| text.contains(line));
    let cause = guess.cause().to_lowercase();
    let names_cause = keywords.iter().any(|keyword| cause.contains(keyword));
    if cites_cause && names_cause {
        Outcome::Correct
    } else {
        Outcome::WrongCause
    }
}

fn skipped(reason: SkipReason) -> Outcome {
    match reason {
        SkipReason::InvalidAnswer => Outcome::InvalidAnswer,
        SkipReason::UnsupportedCitation => Outcome::UnsupportedCitation,
        SkipReason::ModelTimeout => Outcome::Timeout,
        SkipReason::ModelUnavailable => Outcome::Unavailable,
        SkipReason::NoEvidence | SkipReason::KnownIncident | SkipReason::CallLimit => {
            Outcome::NoModelCall
        }
    }
}

fn cited_line(prompt: &str, n: usize) -> Option<&str> {
    let prefix = format!("L{n}: ");
    prompt
        .lines()
        .find_map(|line| line.strip_prefix(prefix.as_str()))
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;

    use analyzer::{
        Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, Model, ModelError, Service,
        Window, triage,
    };

    use super::*;
    use crate::scenarios::{NOW, Scenario, all};

    struct Fixed(Evidence);

    impl EvidenceSource for Fixed {
        fn collect(&self, _: &Window, _: &[Service]) -> Result<Evidence, EvidenceError> {
            Ok(self.0.clone())
        }
    }

    struct At;

    impl Clock for At {
        fn now_unix(&self) -> u64 {
            NOW
        }
    }

    struct Answering {
        answer: String,
        prompt: RefCell<String>,
    }

    impl Model for Answering {
        fn explain(&self, prompt: &str) -> Result<String, ModelError> {
            *self.prompt.borrow_mut() = prompt.to_owned();
            Ok(self.answer.clone())
        }
    }

    fn scenario(name: &str) -> Scenario {
        all().into_iter().find(|s| s.name == name).unwrap()
    }

    fn outcome(scenario: &Scenario, answer: &str) -> Outcome {
        let model = Answering {
            answer: answer.to_owned(),
            prompt: RefCell::default(),
        };
        let report = triage(
            &scenario.alerts(),
            &Knowledge::default(),
            &Fixed(scenario.evidence.clone()),
            &At,
            &model,
        )
        .remove(0);
        score(&scenario.expect, &report.analysis, &model.prompt.borrow())
    }

    fn guess(cause: &str) -> String {
        format!(
            r#"{{"cause":"{cause}","evidence":"L1 반복","confidence":"high","cited_lines":[1]}}"#
        )
    }

    #[test]
    fn citing_the_cause_line_and_naming_the_cause_is_correct() {
        assert_eq!(
            outcome(&scenario("db-pool-exhausted"), &guess("DB 연결 풀 고갈")),
            Outcome::Correct
        );
    }

    #[test]
    fn citing_the_cause_line_with_a_wrong_cause_is_wrong() {
        assert_eq!(
            outcome(&scenario("db-pool-exhausted"), &guess("네트워크 장비 고장")),
            Outcome::WrongCause
        );
    }

    #[test]
    fn guessing_where_the_cause_is_unknowable_is_fabricated() {
        let scenario = scenario("unknown-latency-broken-pipe");
        assert_eq!(
            outcome(&scenario, &guess("클라이언트 연결 끊김")),
            Outcome::Fabricated
        );
        let low = r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#;
        assert_eq!(outcome(&scenario, low), Outcome::SaidUnknown);
    }

    #[test]
    fn answer_breaking_the_json_shape_is_an_invalid_answer() {
        assert_eq!(
            outcome(&scenario("disk-full"), "```json\n{}\n```"),
            Outcome::InvalidAnswer
        );
    }

    #[test]
    fn every_scenario_reaches_the_model_and_shows_its_cause_line_first() {
        let low = r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#;
        for scenario in all() {
            let result = outcome(&scenario, low);
            assert_ne!(result, Outcome::NoModelCall, "{}", scenario.name);
            if let Expect::Cause { line, .. } = scenario.expect {
                assert_eq!(
                    outcome(&scenario, &guess(line)),
                    Outcome::Correct,
                    "{}",
                    scenario.name
                );
            }
        }
    }
}
