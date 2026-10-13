package com.taskflow.assistant.summary.generator;

import com.taskflow.calendarsync.outbox.OutboxStatus;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.planning.task.Task;
import com.taskflow.planning.task.TaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SummaryPromptTaskSupport {

    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;

    public Map<String, Object> toPromptTaskPayload(
            TaskSyncSnapshot snapshot, LocalDate weekStart, LocalDate weekEnd, LocalDate today) {
        Task task = snapshot.getTask();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getId());
        item.put("title", task.getTitle());
        item.put("status", task.getStatus().name());

        putIfPresent(item, "startAt", task.getStartAt());
        putIfPresent(item, "dueAt", task.getDueAt());

        boolean recentlyUpdated = isRecentlyUpdated(task);
        if (recentlyUpdated) {
            item.put("recentlyUpdated", true);
            putIfPresent(item, "updatedAt", task.getUpdatedAt());
        }

        if (Boolean.TRUE.equals(task.getCalendarSyncEnabled())) {
            item.put("calendarSyncEnabled", true);
        }

        item.put("syncState", snapshot.getSyncState().name());
        putIfPresent(item, "lastOutboxStatus", enumName(snapshot.getLatestOutboxStatus()));
        if (snapshot.getLatestOutboxStatus() == OutboxStatus.FAILED) {
            putIfPresent(
                    item, "lastOutboxError", TaskDescriptionBrief.normalizeWhitespace(snapshot.getLatestOutboxError()));
        }

        putIfTrue(item, "isOverdue", isOverdue(task, today));
        putIfTrue(item, "isDeadlineThisWeek", isDeadlineThisWeek(task, weekStart, weekEnd));
        putIfTrue(item, "isEventOverlappingThisWeek", isEventOverlappingThisWeek(task, weekStart, weekEnd));

        String descBrief = TaskDescriptionBrief.compressDescription(task);
        putIfPresent(item, "descBrief", descBrief);

        List<String> signals = TaskDescriptionBrief.descriptionSignals(task.getDescription());
        if (!signals.isEmpty()) {
            item.put("descSignals", signals);
        }

        return item;
    }

    public int descBriefChars(Task task) {
        String descBrief = TaskDescriptionBrief.compressDescription(task);
        return descBrief == null ? 0 : descBrief.length();
    }

    private boolean isRecentlyUpdated(Task task) {
        if (task.getUpdatedAt() == null) {
            return false;
        }
        return task.getUpdatedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusHours(RECENT_UPDATE_HOURS));
    }

    private boolean isOverdue(Task task, LocalDate today) {
        return task.getDueAt() != null
                && task.getDueAt().toLocalDate().isBefore(today)
                && task.getStatus() != TaskStatus.DONE;
    }

    private boolean isDeadlineThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        LocalDate dueDate = task.getDueAt().toLocalDate();
        return !dueDate.isBefore(weekStart) && !dueDate.isAfter(weekEnd);
    }

    private boolean isEventOverlappingThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        if (task.getStartAt() != null && task.getStartAt().isAfter(task.getDueAt())) {
            return false;
        }

        LocalDate eventStartDate = task.getStartAt() != null
                ? task.getStartAt().toLocalDate()
                : task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS).toLocalDate();
        LocalDate eventEndDate = task.getDueAt().toLocalDate();
        return !eventStartDate.isAfter(weekEnd) && !eventEndDate.isBefore(weekStart);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String string && string.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private void putIfTrue(Map<String, Object> target, String key, boolean value) {
        if (value) {
            target.put(key, true);
        }
    }

    private String enumName(OutboxStatus status) {
        return status != null ? status.name() : null;
    }
}
