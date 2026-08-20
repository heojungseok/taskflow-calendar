package com.taskflow.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskFlowMetricsTest {

    @Test
    void exposesPrometheusNativeDemoTaskCounterNameForRelabeling() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        TaskFlowMetrics metrics = new TaskFlowMetrics(registry);

        metrics.demoTaskCreated();

        assertThat(registry.scrape()).contains("\ndemo_task_creations_total ");
    }

    @Test
    void recordsBoundedCountersAndGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskFlowMetrics metrics = new TaskFlowMetrics(registry);

        metrics.demoSessionStarted();
        metrics.demoTaskCreated();
        metrics.demoUserExpired();
        metrics.demoCleanupFailed();
        metrics.outboxProcessed("skipped", "no_google_link");
        metrics.setOldestExpiredAgeSeconds(42);
        metrics.setOldestProcessableAgeSeconds(84);

        assertThat(registry.get("demo_sessions_started_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_task_creations_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_users_expired_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_cleanup_failures_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox_processed_total")
                .tag("outcome", "skipped").tag("reason", "no_google_link")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_oldest_expired_age_seconds").gauge().value()).isEqualTo(42);
        assertThat(registry.get("outbox_oldest_processable_age_seconds").gauge().value()).isEqualTo(84);
    }
}
