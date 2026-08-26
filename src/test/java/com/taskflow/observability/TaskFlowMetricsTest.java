package com.taskflow.observability;

import com.taskflow.calendar.domain.search.exception.TaskSearchGenerationException;
import com.taskflow.common.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskFlowMetricsTest {

    @Test
    void observesEachGeminiCallExactlyOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskFlowMetrics metrics = new TaskFlowMetrics(registry);

        assertThat(metrics.observeGeminiCall("weekly_summary", () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> metrics.observeGeminiCall("embedding", () -> {
            throw new TaskSearchGenerationException(ErrorCode.LLM_QUOTA_EXHAUSTED, "quota");
        })).isInstanceOf(TaskSearchGenerationException.class);

        assertThat(registry.get("gemini_calls_total")
                .tags("feature", "weekly_summary", "outcome", "success", "error_code", "none")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("gemini_calls_total")
                .tags("feature", "embedding", "outcome", "failure", "error_code", "LLM_QUOTA_EXHAUSTED")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("gemini_calls").tag("feature", "weekly_summary").timer().count()).isEqualTo(1);
        assertThat(registry.get("gemini_calls").tag("feature", "embedding").timer().count()).isEqualTo(1);
    }

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
        metrics.setUserCounts(12, 3, 5);

        assertThat(registry.get("demo_sessions_started_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_task_creations_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_users_expired_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_cleanup_failures_total").counter().count()).isEqualTo(1);
        assertThat(registry.get("outbox_processed_total")
                .tag("outcome", "skipped").tag("reason", "no_google_link")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("demo_oldest_expired_age_seconds").gauge().value()).isEqualTo(42);
        assertThat(registry.get("outbox_oldest_processable_age_seconds").gauge().value()).isEqualTo(84);
        assertThat(registry.get("taskflow_google_users_registered").gauge().value()).isEqualTo(12);
        assertThat(registry.get("taskflow_google_users_created_24h").gauge().value()).isEqualTo(3);
        assertThat(registry.get("taskflow_demo_sessions_active").gauge().value()).isEqualTo(5);
    }
}
