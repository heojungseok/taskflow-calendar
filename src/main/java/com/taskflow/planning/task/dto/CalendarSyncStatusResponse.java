package com.taskflow.planning.task.dto;

import com.taskflow.calendarsync.outbox.CalendarOutbox;
import com.taskflow.calendarsync.outbox.OutboxOpType;
import com.taskflow.calendarsync.outbox.OutboxStatus;
import com.taskflow.common.TimeZones;
import com.taskflow.planning.task.Task;
import java.time.Instant;
import lombok.Getter;

/**
 * Task 캘린더 동기화 상태 응답 DTO
 * - Task의 캘린더 동기화 설정 + 최신 Outbox 상태 제공
 */
@Getter
public class CalendarSyncStatusResponse {

    private final Long taskId;
    private final Boolean calendarSyncEnabled;
    private final String calendarEventId;
    private final OutboxStatus lastOutboxStatus;
    private final OutboxOpType lastOutboxOpType;
    private final String lastOutboxError;
    private final Instant lastSyncedAt;
    private final Instant lastOutboxCreatedAt;

    private CalendarSyncStatusResponse(
            Long taskId,
            Boolean calendarSyncEnabled,
            String calendarEventId,
            OutboxStatus lastOutboxStatus,
            OutboxOpType lastOutboxOpType,
            String lastOutboxError,
            Instant lastSyncedAt,
            Instant lastOutboxCreatedAt) {
        this.taskId = taskId;
        this.calendarSyncEnabled = calendarSyncEnabled;
        this.calendarEventId = calendarEventId;
        this.lastOutboxStatus = lastOutboxStatus;
        this.lastOutboxOpType = lastOutboxOpType;
        this.lastOutboxError = lastOutboxError;
        this.lastSyncedAt = lastSyncedAt;
        this.lastOutboxCreatedAt = lastOutboxCreatedAt;
    }

    /**
     * Task + 최신 Outbox -> DTO 변환
     * @param task Task 엔티티
     * @param latestOutbox 최신 Outbox (nullable)
     * @param lastSuccessOutbox 마지막 성공 Outbox (nullable)
     */
    public static CalendarSyncStatusResponse of(
            Task task, CalendarOutbox latestOutbox, CalendarOutbox lastSuccessOutbox) {
        return new CalendarSyncStatusResponse(
                task.getId(),
                task.getCalendarSyncEnabled(),
                task.getCalendarEventId(),
                latestOutbox != null ? latestOutbox.getStatus() : null,
                latestOutbox != null ? latestOutbox.getOpType() : null,
                latestOutbox != null ? latestOutbox.getLastError() : null,
                lastSuccessOutbox != null ? TimeZones.toUtcInstant(lastSuccessOutbox.getUpdatedAt()) : null,
                latestOutbox != null ? TimeZones.toUtcInstant(latestOutbox.getCreatedAt()) : null);
    }
}
