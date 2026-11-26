use std::path::PathBuf;
use std::time::Duration;

use analyzer::{
    Commit, Evidence, EvidenceError, EvidenceSource, MetricSeries, Missing, Service, Window,
};

use crate::http::Http;
use crate::metrics::METRICS;
use crate::{github, journal, loki, prometheus};

pub(crate) struct Github {
    pub(crate) api_base: String,
    pub(crate) repo: String,
}

pub(crate) struct Sources {
    pub(crate) loki_url: String,
    pub(crate) prometheus_url: String,
    pub(crate) github: Option<Github>,
    pub(crate) journal: PathBuf,
    pub(crate) http: Http,
}

impl Sources {
    pub(crate) fn new(loki_url: String, prometheus_url: String, journal: PathBuf) -> Self {
        Self {
            loki_url,
            prometheus_url,
            github: None,
            journal,
            http: Http::new(Duration::from_secs(10)),
        }
    }
}

const BASELINE_LOG_SECS: u64 = 24 * 3600;

impl Sources {
    fn metrics(&self, window: &Window) -> Vec<MetricSeries> {
        let span = (window.from_unix, window.to_unix);
        METRICS
            .iter()
            .filter_map(|spec| prometheus::fetch(&self.http, &self.prometheus_url, spec, span).ok())
            .collect()
    }

    fn commits(&self) -> Vec<Commit> {
        let (Some(github), Some(deploy)) = (&self.github, journal::last_deploy(&self.journal))
        else {
            return Vec::new();
        };
        github::changed_files(&self.http, github, &deploy)
            .map(|commit| vec![commit])
            .unwrap_or_default()
    }
}

impl EvidenceSource for Sources {
    fn collect(&self, window: &Window, services: &[Service]) -> Result<Evidence, EvidenceError> {
        let logs = loki::fetch(
            &self.http,
            &self.loki_url,
            services,
            (window.from_unix, window.to_unix),
        );
        let before = (
            window.from_unix.saturating_sub(BASELINE_LOG_SECS),
            window.from_unix,
        );
        let baseline = loki::fetch(&self.http, &self.loki_url, services, before);
        let metrics = self.metrics(window);
        if logs.is_err() && metrics.is_empty() {
            return Err(EvidenceError::Other(
                "loki and prometheus failed".to_owned(),
            ));
        }
        let mut missing = Vec::new();
        if logs.is_err() {
            missing.push(Missing::Logs);
        }
        if baseline.is_err() {
            missing.push(Missing::Baseline);
        }
        Ok(Evidence {
            logs: logs.unwrap_or_default(),
            baseline_logs: baseline.unwrap_or_default(),
            commits: self.commits(),
            metrics,
            missing,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_server::{Reply, Request, TestServer, serve};

    const FROM: u64 = 1_764_115_200;
    const TO: u64 = FROM + 900;
    const LOGS: &str = r#"{"data":{"result":[{"stream":{"service":"backend"},"values":[["1764115300000000000","ERROR now"]]}]}}"#;
    const BASELINE: &str = r#"{"data":{"result":[{"stream":{"service":"backend"},"values":[["1764100000000000000","ERROR old"],["1764100100000000000","WARN old"]]}]}}"#;
    const MATRIX: &str =
        r#"{"data":{"result":[{"metric":{},"values":[[1764115300,"1"],[1764115330,"2"]]}]}}"#;

    fn query_param<'a>(request: &'a Request, key: &str) -> Option<&'a str> {
        let query = request.target.split_once('?')?.1;
        query
            .split('&')
            .find_map(|pair| pair.strip_prefix(key)?.strip_prefix('='))
    }

    fn loki(logs_ok: bool, baseline_ok: bool) -> TestServer {
        serve(move |request| {
            let is_logs = query_param(request, "start") == Some("1764115200000000000");
            match (is_logs, logs_ok, baseline_ok) {
                (true, true, _) => Reply::ok(LOGS),
                (false, _, true) => Reply::ok(BASELINE),
                _ => Reply::status(500, "boom"),
            }
        })
    }

    fn prometheus(ok: bool) -> TestServer {
        serve(move |_| {
            if ok {
                Reply::ok(MATRIX)
            } else {
                Reply::status(500, "boom")
            }
        })
    }

    fn collect(loki: &TestServer, prom: &TestServer) -> Result<Evidence, EvidenceError> {
        let sources = Sources::new(loki.url(), prom.url(), PathBuf::from("/nonexistent"));
        sources.collect(
            &Window {
                from_unix: FROM,
                to_unix: TO,
            },
            &[Service::new("backend")],
        )
    }

    #[test]
    fn t32_모두_성공하면_증거가_채워진다() {
        let evidence = collect(&loki(true, true), &prometheus(true)).unwrap();
        assert_eq!(evidence.logs.len(), 1);
        assert_eq!(evidence.baseline_logs.len(), 2);
        assert_eq!(evidence.metrics.len(), crate::metrics::METRICS.len());
        assert!(evidence.missing.is_empty());
        assert!(evidence.commits.is_empty());
    }

    #[test]
    fn t32_로그가_실패하면_logs_누락이다() {
        let evidence = collect(&loki(false, true), &prometheus(true)).unwrap();
        assert!(evidence.logs.is_empty());
        assert_eq!(evidence.missing, vec![Missing::Logs]);
    }

    #[test]
    fn t32_기준_로그가_실패하면_baseline_누락이다() {
        let evidence = collect(&loki(true, false), &prometheus(true)).unwrap();
        assert_eq!(evidence.logs.len(), 1);
        assert_eq!(evidence.missing, vec![Missing::Baseline]);
    }

    #[test]
    fn t32_loki와_prometheus가_모두_실패하면_오류다() {
        assert!(collect(&loki(false, false), &prometheus(false)).is_err());
    }

    #[test]
    fn t32_지표만_실패하면_지표_없이_진행한다() {
        let evidence = collect(&loki(true, true), &prometheus(false)).unwrap();
        assert!(evidence.metrics.is_empty());
        assert!(evidence.missing.is_empty());
    }

    #[test]
    fn t32_service_선택자는_안전한_이름만_담는다() {
        let server = loki(true, true);
        let prom = prometheus(true);
        let sources = Sources::new(server.url(), prom.url(), PathBuf::from("/nonexistent"));
        let services = [Service::new("backend"), Service::new(r#"bad"} or {x=~".*"#)];
        let window = Window {
            from_unix: FROM,
            to_unix: TO,
        };
        sources.collect(&window, &services).unwrap();
        let seen = server.requests.lock().unwrap();
        let target = &seen[0].target;
        assert!(target.contains("service%3D~%22backend%22") || target.contains("backend"));
        assert!(!target.contains("bad"));
    }

    #[test]
    fn 배포_기록과_github_설정이_있으면_바뀐_파일을_커밋으로_담는다() {
        let dir = tempfile::tempdir().unwrap();
        let journal = dir.path().join("journal.tsv");
        let (a, b) = (
            "1f2a9320000000000000000000000000000000ab",
            "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69",
        );
        std::fs::write(
            &journal,
            format!("2025-11-26T00:00:00Z\t{a}\tsuccess\tfull\t1\th\n2025-11-26T01:00:00Z\t{b}\tsuccess\tbackend\t2\th\n"),
        )
        .unwrap();
        let github = serve(|_| Reply::ok(r#"{"files":[{"filename":"src/A.java"}]}"#));
        let (loki, prom) = (loki(true, true), prometheus(true));
        let mut sources = Sources::new(loki.url(), prom.url(), journal);
        sources.github = Some(Github {
            api_base: github.url(),
            repo: "o/r".to_owned(),
        });
        let window = Window {
            from_unix: FROM,
            to_unix: TO,
        };
        let evidence = sources
            .collect(&window, &[Service::new("backend")])
            .unwrap();
        assert_eq!(evidence.commits[0].sha, b);
        assert_eq!(evidence.commits[0].files, vec!["src/A.java".to_owned()]);
        let target = github.requests.lock().unwrap()[0].target.clone();
        assert_eq!(target, format!("/repos/o/r/compare/{a}...{b}"));
    }
}
