package com.taskflow.calendar.domain.summary;

import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.CalendarOutboxService;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.task.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskSyncStateResolver {

    private final CalendarOutboxService calendarOutboxService;

    public SummaryTaskSnapshot resolve(Task task) {
        CalendarOutbox latestOutbox = calendarOutboxService.findLatestByTaskId(task.getId())
                .orElse(null);

        return SummaryTaskSnapshot.of(
                task,
                classify(task, latestOutbox),
                latestOutbox != null ? latestOutbox.getStatus() : null,
                latestOutbox != null ? latestOutbox.getOpType() : null,
                latestOutbox != null ? latestOutbox.getLastError() : null
        );
    }

    private TaskSyncState classify(Task task, CalendarOutbox latestOutbox) {
        boolean syncEnabled = Boolean.TRUE.equals(task.getCalendarSyncEnabled());
        boolean hasEventId = task.getCalendarEventId() != null && !task.getCalendarEventId().isBlank();

        if (latestOutbox != null) {
            if (latestOutbox.getOpType() == OutboxOpType.UPSERT) {
                return classifyUpsert(syncEnabled, hasEventId, latestOutbox.getStatus());
            }
            return classifyDelete(syncEnabled, latestOutbox.getStatus());
        }

        if (!syncEnabled) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (!hasEventId) {
            return TaskSyncState.PENDING_SYNC;
        }

        return TaskSyncState.SYNCED;
    }

    private TaskSyncState classifyUpsert(boolean syncEnabled, boolean hasEventId, OutboxStatus status) {
        if (!syncEnabled) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (status == OutboxStatus.SUCCESS && hasEventId) {
            return TaskSyncState.SYNCED;
        }

        if (status == OutboxStatus.FAILED) {
            return TaskSyncState.FAILED_SYNC;
        }

        return TaskSyncState.PENDING_SYNC;
    }

    private TaskSyncState classifyDelete(boolean syncEnabled, OutboxStatus status) {
        if (syncEnabled) {
            if (status == OutboxStatus.FAILED) {
                return TaskSyncState.FAILED_SYNC;
            }
            return TaskSyncState.PENDING_SYNC;
        }

        if (status == OutboxStatus.SUCCESS) {
            return TaskSyncState.SYNC_DISABLED;
        }

        if (status == OutboxStatus.FAILED) {
            return TaskSyncState.DELETE_FAILED;
        }

        return TaskSyncState.DELETE_PENDING;
    }
}
