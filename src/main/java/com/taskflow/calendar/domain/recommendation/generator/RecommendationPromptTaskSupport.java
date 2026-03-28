package com.taskflow.calendar.domain.recommendation.generator;

import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.generator.SummaryPromptTaskSupport;
import com.taskflow.calendar.domain.task.Task;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecommendationPromptTaskSupport {

    private static final int RECENT_UPDATE_HOURS = 24;

    private final SummaryPromptTaskSupport summarySupport = new SummaryPromptTaskSupport();

    public Map<String, Object> toPromptTaskPayload(SummaryTaskSnapshot snapshot, int seed) {
        Task task = snapshot.getTask();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", task.getId());
        payload.put("title", task.getTitle());
        payload.put("status", task.getStatus().name());
        payload.put("seed", seed);

        putIfPresent(payload, "due", task.getDueAt());

        if (isRecentlyUpdated(task)) {
            payload.put("recent", true);
        }

        if (snapshot.getSyncState() != null) {
            payload.put("sync", snapshot.getSyncState().name());
        }
        putIfPresent(payload, "outbox", snapshot.getLatestOutboxStatus() != null ? snapshot.getLatestOutboxStatus().name() : null);
        putIfPresent(payload, "desc", summarySupport.compressDescription(task));

        List<String> descSignals = summarySupport.descriptionSignals(task.getDescription());
        if (!descSignals.isEmpty()) {
            payload.put("signals", descSignals);
        }
        return payload;
    }

    private boolean isRecentlyUpdated(Task task) {
        if (task.getUpdatedAt() == null) {
            return false;
        }
        return task.getUpdatedAt().isAfter(LocalDateTime.now().minusHours(RECENT_UPDATE_HOURS));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).isBlank()) {
            return;
        }
        target.put(key, value);
    }
}
