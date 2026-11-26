use std::time::Duration;

use analyzer::{
    Commit, Evidence, EvidenceError, EvidenceSource, MetricSeries, Missing, Service, Window,
};
use incident::Deploy;

use crate::http::Http;
use crate::loki::{self, Direction};
use crate::metrics::METRICS;
use crate::{github, prometheus};

const BASELINE_LOG_SECS: u64 = 24 * 3600;
const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone)]
pub(crate) struct Github {
    pub(crate) api_base: String,
    pub(crate) repo: String,
}

#[derive(Clone)]
pub(crate) struct Sources {
    loki_url: String,
    prometheus_url: String,
    github: Option<Github>,
    http: Http,
}

pub(crate) struct ForBatch {
    sources: Sources,
    deploy: Option<Deploy>,
}

impl Sources {
    pub(crate) fn new(loki_url: String, prometheus_url: String, github: Option<Github>) -> Self {
        Self {
            loki_url,
            prometheus_url,
            github,
            http: Http::new(HTTP_TIMEOUT),
        }
    }

    pub(crate) fn for_batch(&self, deploy: Option<Deploy>) -> ForBatch {
        ForBatch {
            sources: self.clone(),
            deploy,
        }
    }
}

struct Metrics {
    series: Vec<MetricSeries>,
    unreachable: bool,
}

impl ForBatch {
    fn metrics(&self, window: &Window) -> Metrics {
        let sources = &self.sources;
        let span = (window.from_unix, window.to_unix);
        let mut series = Vec::new();
        let mut answered = false;
        let mut first_failure = None;
        for spec in &METRICS {
            match prometheus::fetch(&sources.http, &sources.prometheus_url, spec, span) {
                Ok(found) => {
                    answered = true;
                    series.extend(found);
                }
                Err(failure) => first_failure = first_failure.or(Some(failure)),
            }
        }
        if let Some(failure) = first_failure {
            crate::syslog::line(&format!("evidence prometheus failed: {failure}"));
        }
        Metrics {
            series,
            unreachable: !answered,
        }
    }

    fn commits(&self) -> Vec<Commit> {
        let (Some(github), Some(deploy)) = (&self.sources.github, &self.deploy) else {
            return Vec::new();
        };
        match github::changed_files(&self.sources.http, github, deploy) {
            Ok(commit) => vec![commit],
            Err(failure) => {
                crate::syslog::line(&format!("evidence github failed: {failure}"));
                Vec::new()
            }
        }
    }

    fn logs(
        &self,
        services: &[Service],
        span: (u64, u64),
        direction: Direction,
        what: &str,
    ) -> Option<Vec<analyzer::LogLine>> {
        let sources = &self.sources;
        match loki::fetch(&sources.http, &sources.loki_url, services, span, direction) {
            Ok(lines) => Some(lines),
            Err(failure) => {
                crate::syslog::line(&format!("evidence loki {what} failed: {failure}"));
                None
            }
        }
    }
}

impl EvidenceSource for ForBatch {
    fn collect(&self, window: &Window, services: &[Service]) -> Result<Evidence, EvidenceError> {
        let span = (window.from_unix, window.to_unix);
        let logs = self.logs(services, span, Direction::Forward, "logs");
        let before = (
            window.from_unix.saturating_sub(BASELINE_LOG_SECS),
            window.from_unix,
        );
        let baseline = self.logs(services, before, Direction::Backward, "baseline");
        let metrics = self.metrics(window);
        if logs.is_none() && metrics.unreachable {
            return Err(EvidenceError::Other(
                "loki and prometheus failed".to_owned(),
            ));
        }
        let mut missing = Vec::new();
        if logs.is_none() {
            missing.push(Missing::Logs);
        }
        if baseline.is_none() {
            missing.push(Missing::Baseline);
        }
        Ok(Evidence {
            logs: logs.unwrap_or_default(),
            baseline_logs: baseline.unwrap_or_default(),
            commits: self.commits(),
            metrics: metrics.series,
            missing,
        })
    }
}

#[cfg(test)]
mod tests;
