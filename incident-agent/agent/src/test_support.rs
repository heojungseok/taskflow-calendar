use analyzer::{
    Alert, Clock, Evidence, EvidenceError, EvidenceSource, Knowledge, LogLine, Model, ModelError,
    Report, Service, Window, triage,
};

pub(crate) const T0: u64 = 1_764_120_000;

struct Fixed(Evidence);

impl EvidenceSource for Fixed {
    fn collect(&self, _: &Window, _: &[Service]) -> Result<Evidence, EvidenceError> {
        Ok(self.0.clone())
    }
}

struct At(u64);

impl Clock for At {
    fn now_unix(&self) -> u64 {
        self.0
    }
}

struct Answer(&'static str);

impl Model for Answer {
    fn explain(&self, _: &str) -> Result<String, ModelError> {
        Ok(self.0.to_owned())
    }
}

pub(crate) fn alert(service: &str, at: u64) -> Alert {
    Alert {
        name: "error logs".to_owned(),
        service: Service::new(service),
        starts_at_unix: at,
    }
}

pub(crate) fn report(alerts: &[Alert], grounded: bool) -> Report {
    let logs = alerts
        .iter()
        .map(|a| LogLine {
            service: a.service.clone(),
            at_unix: a.starts_at_unix + 5,
            text: "ERROR c.t.p.TaskSearchService : NullPointerException".to_owned(),
        })
        .collect();
    let answer = if grounded {
        Answer(
            r#"{"cause":"검색 null 처리 누락","evidence":"NPE 반복","confidence":"high","cited_lines":[1]}"#,
        )
    } else {
        Answer(r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#)
    };
    let now = alerts.iter().map(|a| a.starts_at_unix).max().unwrap_or(T0) + 60;
    let evidence = Fixed(Evidence {
        logs,
        ..Evidence::default()
    });
    let mut reports = triage(alerts, &Knowledge::default(), &evidence, &At(now), &answer);
    reports.remove(0)
}
