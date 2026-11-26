use crate::prometheus::MetricSpec;

pub(crate) const METRICS: [MetricSpec; 10] = [
    MetricSpec {
        name: "backend_up",
        query: r#"min(up{job="taskflow"})"#,
        floor: 1.0,
    },
    MetricSpec {
        name: "outbox_oldest_age_seconds",
        query: "max(outbox_oldest_processable_age_seconds)",
        floor: 60.0,
    },
    MetricSpec {
        name: "pg_connection_ratio",
        query: "sum(pg_stat_activity_count) / max(pg_settings_max_connections)",
        floor: 0.1,
    },
    MetricSpec {
        name: "gemini_failures_5m",
        query: r#"sum(increase(gemini_calls_total{outcome="failure"}[5m]))"#,
        floor: 3.0,
    },
    MetricSpec {
        name: "demo_cleanup_failures_15m",
        query: "sum(increase(demo_cleanup_failures_total[15m]))",
        floor: 1.0,
    },
    MetricSpec {
        name: "http_5xx_per_second",
        query: r#"sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))"#,
        floor: 0.05,
    },
    MetricSpec {
        name: "http_p95_seconds",
        query: "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))",
        floor: 1.0,
    },
    MetricSpec {
        name: "hikari_pending",
        query: "max(hikaricp_connections_pending)",
        floor: 1.0,
    },
    MetricSpec {
        name: "jvm_heap_ratio",
        query: r#"sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})"#,
        floor: 0.8,
    },
    MetricSpec {
        name: "backend_memory_bytes",
        query: r#"max(taskflow_container_memory_bytes{service="backend"})"#,
        floor: 1.0e9,
    },
];
