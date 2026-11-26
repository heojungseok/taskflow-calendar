use analyzer::{Alert, Commit, Evidence, LogLine, MetricSeries, Missing, Service};

use crate::score::Expect;

pub(crate) const ALERT_AT: u64 = 1_000;
pub(crate) const NOW: u64 = 1_200;

pub(crate) struct Scenario {
    pub(crate) name: &'static str,
    pub(crate) alert: &'static str,
    pub(crate) evidence: Evidence,
    pub(crate) expect: Expect,
}

impl Scenario {
    pub(crate) fn alerts(&self) -> Vec<Alert> {
        vec![Alert {
            name: self.alert.to_owned(),
            service: Service::new("backend"),
            starts_at_unix: ALERT_AT,
        }]
    }
}

fn log(at_unix: u64, text: &str) -> LogLine {
    LogLine {
        service: Service::new("backend"),
        at_unix,
        text: text.to_owned(),
    }
}

fn repeated(from: u64, count: u64, text: &str) -> Vec<LogLine> {
    (0..count).map(|i| log(from + i * 10, text)).collect()
}

fn metric(name: &str, baseline: f64, floor: f64, values: &[f64]) -> MetricSeries {
    MetricSeries {
        name: name.to_owned(),
        baseline,
        floor,
        points: values
            .iter()
            .enumerate()
            .map(|(i, v)| (ALERT_AT - 120 + i as u64 * 60, *v))
            .collect(),
    }
}

fn baseline_noise() -> Vec<LogLine> {
    vec![
        log(100, "WARN c.t.s.SchedulerService : Outbox poll took 1200ms"),
        log(
            200,
            "ERROR c.t.c.GoogleCalendarClient : Calendar event not found for id 4411",
        ),
    ]
}

fn cause(line: &'static str, keywords: &'static [&'static str]) -> Expect {
    Expect::Cause { line, keywords }
}

pub(crate) fn all() -> Vec<Scenario> {
    vec![
        db_pool(),
        deploy_npe(),
        gemini_quota(),
        heap_oom(),
        disk_full(),
        flyway_checksum(),
        broken_pipe_latency(),
        metric_only_cpu(),
    ]
}

fn db_pool() -> Scenario {
    let mut logs = repeated(
        1_010,
        6,
        "ERROR c.t.p.TaskService : HikariPool-1 - Connection is not available, request timed out after 30000ms",
    );
    logs.extend(baseline_noise());
    Scenario {
        name: "db-pool-exhausted",
        alert: "backend error logs",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            metrics: vec![metric(
                "hikaricp_connections_active",
                3.0,
                8.0,
                &[3.0, 4.0, 10.0, 10.0],
            )],
            ..Evidence::default()
        },
        expect: cause("HikariPool", &["풀", "pool", "hikari", "커넥션", "연결"]),
    }
}

fn deploy_npe() -> Scenario {
    let mut logs = repeated(
        1_020,
        4,
        "ERROR c.t.p.TaskSearchService : NullPointerException: Cannot invoke \"String.isBlank()\" because \"keyword\" is null",
    );
    logs.extend(baseline_noise());
    Scenario {
        name: "deploy-npe",
        alert: "backend error logs",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            commits: vec![Commit {
                sha: "4f1c2a9d0b7e6f5a4c3b2a1908f7e6d5c4b3a291".to_owned(),
                files: vec![
                    "src/main/java/com/taskflow/planning/TaskSearchService.java".to_owned(),
                ],
            }],
            ..Evidence::default()
        },
        expect: cause(
            "NullPointerException",
            &["null", "npe", "커밋", "배포", "keyword"],
        ),
    }
}

fn gemini_quota() -> Scenario {
    let mut logs = repeated(
        1_005,
        5,
        "ERROR c.t.a.GeminiClient : Gemini call failed status=429 RESOURCE_EXHAUSTED quota exceeded",
    );
    logs.extend(baseline_noise());
    Scenario {
        name: "gemini-quota",
        alert: "backend error logs",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            ..Evidence::default()
        },
        expect: cause(
            "429",
            &[
                "429",
                "쿼터",
                "quota",
                "한도",
                "resource_exhausted",
                "할당량",
            ],
        ),
    }
}

fn heap_oom() -> Scenario {
    let mut logs = vec![log(
        1_040,
        "ERROR o.a.c.c.C.[.[.[.[dispatcherServlet] : Servlet.service() threw exception java.lang.OutOfMemoryError: Java heap space",
    )];
    logs.extend(baseline_noise());
    Scenario {
        name: "heap-oom",
        alert: "backend down",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            metrics: vec![metric(
                "jvm_memory_used_bytes",
                3.0e8,
                6.0e8,
                &[3.1e8, 5.5e8, 9.8e8, 1.0e9],
            )],
            ..Evidence::default()
        },
        expect: cause(
            "OutOfMemoryError",
            &["메모리", "memory", "heap", "힙", "outofmemory", "oom"],
        ),
    }
}

fn disk_full() -> Scenario {
    let mut logs = repeated(
        1_015,
        3,
        "ERROR o.h.e.j.s.SqlExceptionHelper : ERROR: could not extend file \"base/16384/2619\": No space left on device",
    );
    logs.extend(baseline_noise());
    Scenario {
        name: "disk-full",
        alert: "backend error logs",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            ..Evidence::default()
        },
        expect: cause(
            "No space left",
            &["디스크", "공간", "용량", "no space", "disk"],
        ),
    }
}

fn flyway_checksum() -> Scenario {
    let mut logs = vec![log(
        1_001,
        "ERROR o.s.boot.SpringApplication : Application run failed org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation. Migration checksum mismatch for migration version 5",
    )];
    logs.extend(baseline_noise());
    Scenario {
        name: "flyway-checksum",
        alert: "backend down",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            ..Evidence::default()
        },
        expect: cause(
            "checksum mismatch",
            &["checksum", "체크섬", "마이그레이션", "migration", "flyway"],
        ),
    }
}

fn broken_pipe_latency() -> Scenario {
    let mut logs = vec![log(
        1_030,
        "ERROR c.t.w.GlobalExceptionHandler : Unhandled exception java.io.IOException: Broken pipe",
    )];
    logs.extend(baseline_noise());
    Scenario {
        name: "unknown-latency-broken-pipe",
        alert: "backend latency high",
        evidence: Evidence {
            logs,
            baseline_logs: baseline_noise(),
            metrics: vec![metric(
                "http_server_requests_p95_seconds",
                0.2,
                0.5,
                &[0.21, 0.3, 0.9, 0.85],
            )],
            ..Evidence::default()
        },
        expect: Expect::Unknown,
    }
}

fn metric_only_cpu() -> Scenario {
    Scenario {
        name: "unknown-cpu-no-logs",
        alert: "backend cpu high",
        evidence: Evidence {
            missing: vec![Missing::Logs],
            metrics: vec![metric(
                "process_cpu_usage",
                0.1,
                0.5,
                &[0.12, 0.4, 0.95, 0.97],
            )],
            ..Evidence::default()
        },
        expect: Expect::Unknown,
    }
}
