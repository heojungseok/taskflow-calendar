package com.taskflow.calendar.domain.summary.cache;

import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;

import java.util.Optional;

public interface WeeklySummaryCacheService {

    Optional<WeeklySummaryResponse> find(String key);

    void save(String key, String latestKey, WeeklySummaryResponse response);

    WeeklySummaryCacheHealthResponse healthCheck();

    boolean isEnabled();
}
