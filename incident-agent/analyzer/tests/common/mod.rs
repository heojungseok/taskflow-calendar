#![allow(dead_code, unreachable_pub)]

use analyzer::{
    Alert, Clock, Evidence, EvidenceError, EvidenceSource, Model, ModelError, Service, Window,
};

pub struct FixedClock(pub u64);

impl Clock for FixedClock {
    fn now_unix(&self) -> u64 {
        self.0
    }
}

pub struct FixedEvidence(pub Evidence);

impl EvidenceSource for FixedEvidence {
    fn collect(&self, _window: &Window, _services: &[Service]) -> Result<Evidence, EvidenceError> {
        Ok(self.0.clone())
    }
}

pub fn alert(name: &str, service: &str, starts_at_unix: u64) -> Alert {
    Alert {
        name: name.to_owned(),
        service: Service::new(service),
        starts_at_unix,
    }
}

pub struct ScriptedModel {
    answer: Result<String, ModelError>,
    pub prompts: std::cell::RefCell<Vec<String>>,
}

impl ScriptedModel {
    pub fn answering(json: &str) -> Self {
        Self {
            answer: Ok(json.to_owned()),
            prompts: Default::default(),
        }
    }

    pub fn failing(error: ModelError) -> Self {
        Self {
            answer: Err(error),
            prompts: Default::default(),
        }
    }

    pub fn unknown() -> Self {
        Self::answering(r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#)
    }
}

impl Model for ScriptedModel {
    fn explain(&self, prompt: &str) -> Result<String, ModelError> {
        self.prompts.borrow_mut().push(prompt.to_owned());
        self.answer.clone()
    }
}
