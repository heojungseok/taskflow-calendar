use contract::GitSha;
use incident::DeployScope;

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
    let sources = Sources::new(loki.url(), prom.url(), None);
    sources.for_batch(None).collect(
        &Window {
            from_unix: FROM,
            to_unix: TO,
        },
        &[Service::new("backend")],
    )
}

#[test]
fn t32_http_오류와_지연_db_대기_heap_backend_메모리도_지표로_읽는다() {
    let evidence = collect(&loki(true, true), &prometheus(true)).unwrap();
    let names: Vec<&str> = evidence.metrics.iter().map(|m| m.name.as_str()).collect();
    for wanted in [
        "http_5xx_per_second",
        "http_p95_seconds",
        "hikari_pending",
        "jvm_heap_ratio",
        "backend_memory_bytes",
    ] {
        assert!(names.contains(&wanted), "{wanted} not in {names:?}");
    }
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
    let sources = Sources::new(server.url(), prom.url(), None);
    let services = [Service::new("backend"), Service::new(r#"bad"} or {x=~".*"#)];
    let window = Window {
        from_unix: FROM,
        to_unix: TO,
    };
    sources.for_batch(None).collect(&window, &services).unwrap();
    let seen = server.requests.lock().unwrap();
    let target = &seen[0].target;
    assert!(target.contains("service%3D~%22backend%22") || target.contains("backend"));
    assert!(!target.contains("bad"));
}

#[test]
fn 배포_기록과_github_설정이_있으면_바뀐_파일을_커밋으로_담는다() {
    let (a, b) = (
        "1f2a9320000000000000000000000000000000ab",
        "e48a8e1a0ec361934fd097afaa9ddd0e48cb0c69",
    );
    let deploy = Deploy {
        sha: GitSha::try_from(b.to_owned()).unwrap(),
        previous_sha: GitSha::try_from(a.to_owned()).unwrap(),
        scope: DeployScope::Backend,
        at_unix: FROM,
    };
    let github = serve(|_| Reply::ok(r#"{"files":[{"filename":"src/A.java"}]}"#));
    let (loki, prom) = (loki(true, true), prometheus(true));
    let github_source = Github {
        api_base: github.url(),
        repo: "o/r".to_owned(),
    };
    let sources = Sources::new(loki.url(), prom.url(), Some(github_source));
    let window = Window {
        from_unix: FROM,
        to_unix: TO,
    };
    let evidence = sources
        .for_batch(Some(deploy))
        .collect(&window, &[Service::new("backend")])
        .unwrap();
    assert_eq!(evidence.commits[0].sha, b);
    assert_eq!(evidence.commits[0].files, vec!["src/A.java".to_owned()]);
    let target = github.requests.lock().unwrap()[0].target.clone();
    assert_eq!(target, format!("/repos/o/r/compare/{a}...{b}"));
}

#[test]
fn t32_alert_service는_loki_service로_옮겨_중복_없이_묻는다() {
    let server = loki(true, true);
    let prom = prometheus(true);
    let sources = Sources::new(server.url(), prom.url(), None);
    let services = [
        Service::new("worker"),
        Service::new("gateway"),
        Service::new("backend"),
        Service::new("postgres"),
    ];
    let window = Window {
        from_unix: FROM,
        to_unix: TO,
    };
    sources.for_batch(None).collect(&window, &services).unwrap();
    let seen = server.requests.lock().unwrap();
    let query = query_param(&seen[0], "query").unwrap().to_owned();
    assert!(
        query.contains("backend%7Cnginx") || query.contains("backend|nginx"),
        "{query}"
    );
    assert!(!query.contains("worker") && !query.contains("postgres"));
}

#[test]
fn t32_prometheus가_빈_결과면_지표_없이_진행하고_실패로_보지_않는다() {
    let empty = serve(|_| Reply::ok(r#"{"data":{"result":[]}}"#));
    let evidence = collect(&loki(false, true), &empty).unwrap();
    assert!(evidence.metrics.is_empty());
    assert_eq!(evidence.missing, vec![Missing::Logs]);
}
