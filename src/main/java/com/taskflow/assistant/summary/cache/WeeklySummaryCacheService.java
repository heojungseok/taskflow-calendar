package com.taskflow.assistant.summary.cache;

import com.taskflow.assistant.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.assistant.summary.dto.WeeklySummaryResponse;
import java.util.Optional;

public interface WeeklySummaryCacheService {

    Optional<WeeklySummaryResponse> find(String key);

    void save(String key, String latestKey, WeeklySummaryResponse response);

    WeeklySummaryCacheHealthResponse healthCheck();

    boolean isEnabled();
}
