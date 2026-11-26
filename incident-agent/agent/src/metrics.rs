use crate::prometheus::MetricSpec;

pub(crate) const METRICS: [MetricSpec; 5] = [
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
];
