package com.taskflow.calendar.domain.summary.cache;

import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;

import java.util.Optional;

public class NoopWeeklySummaryCacheService implements WeeklySummaryCacheService {

    @Override
    public Optional<WeeklySummaryResponse> find(String key) {
        return Optional.empty();
    }

    @Override
    public void save(String key, String latestKey, WeeklySummaryResponse response) {
        // Cache disabled.
    }

    @Override
    public WeeklySummaryCacheHealthResponse healthCheck() {
        return WeeklySummaryCacheHealthResponse.disabled();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
