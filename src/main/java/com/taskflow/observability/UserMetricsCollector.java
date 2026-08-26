package com.taskflow.observability;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class UserMetricsCollector {

    static final String USER_COUNTS_SQL = """
            select
              count(*) filter (where provider = 'GOOGLE') as google_registered,
              count(*) filter (where provider = 'GOOGLE' and created_at >= localtimestamp - interval '24 hours') as google_created_24h,
              count(*) filter (where provider = 'DEMO' and expires_at > current_timestamp) as demo_active
            from users
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TaskFlowMetrics metrics;

    @Scheduled(fixedDelay = 300_000, initialDelay = 0)
    void refresh() {
        Map<String, Object> counts = jdbcTemplate.queryForMap(USER_COUNTS_SQL);
        metrics.setUserCounts(
                value(counts, "google_registered"),
                value(counts, "google_created_24h"),
                value(counts, "demo_active")
        );
    }

    private long value(Map<String, Object> counts, String key) {
        return ((Number) counts.get(key)).longValue();
    }
}
