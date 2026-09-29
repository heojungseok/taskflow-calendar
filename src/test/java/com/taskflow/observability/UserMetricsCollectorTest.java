package com.taskflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UserMetricsCollectorTest {

    @Test
    void refreshesThreeUserGaugesFromOneAggregateQuery() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForMap(UserMetricsCollector.USER_COUNTS_SQL))
                .thenReturn(Map.of(
                        "google_registered", 12L,
                        "google_created_24h", 3L,
                        "demo_active", 5L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskFlowMetrics metrics = new TaskFlowMetrics(registry);

        new UserMetricsCollector(jdbcTemplate, metrics).refresh();

        assertThat(registry.get("taskflow_google_users_registered").gauge().value())
                .isEqualTo(12);
        assertThat(registry.get("taskflow_google_users_created_24h").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("taskflow_demo_sessions_active").gauge().value())
                .isEqualTo(5);
    }
}
