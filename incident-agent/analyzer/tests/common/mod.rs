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

pub fn knowledge_doc(signature: &str, logger: &str, visitor: &str, confirmed: &str) -> String {
    format!(
        "# 문서\n\n- 식별 패턴: `{signature}`\n- 로거: `{logger}`\n- 관련 알림: backend error logs\n- 방문자 유발: {visitor}\n- 확정: {confirmed}\n\n## 증상\nx\n\n## 원인\nx\n\n## 확인할 것\nx\n\n## 조치\nx\n"
    )
}

pub fn knowledge_dir(name: &str, docs: &[(&str, String)]) -> std::path::PathBuf {
    let dir =
        std::env::temp_dir().join(format!("analyzer-knowledge-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (file, text) in docs {
        std::fs::write(dir.join(file), text).unwrap();
    }
    dir
}

pub fn knowledge_from(name: &str, docs: &[(&str, String)]) -> analyzer::Knowledge {
    let (knowledge, issues) = analyzer::load_knowledge(&knowledge_dir(name, docs));
    assert!(issues.is_empty(), "{issues:?}");
    knowledge
}
