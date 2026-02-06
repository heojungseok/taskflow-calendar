package com.taskflow.calendar.domain.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.outbox.CalendarOutbox;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.task.Task;
import lombok.Getter;

import java.time.LocalDateTime;

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

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime lastSyncedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime lastOutboxCreatedAt;

    private CalendarSyncStatusResponse(Long taskId, Boolean calendarSyncEnabled,
                                       String calendarEventId, OutboxStatus lastOutboxStatus,
                                       OutboxOpType lastOutboxOpType, String lastOutboxError,
                                       LocalDateTime lastSyncedAt, LocalDateTime lastOutboxCreatedAt) {
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
    public static CalendarSyncStatusResponse of(Task task, CalendarOutbox latestOutbox,
                                                CalendarOutbox lastSuccessOutbox) {
        return new CalendarSyncStatusResponse(
                task.getId(),
                task.getCalendarSyncEnabled(),
                task.getCalendarEventId(),
                latestOutbox != null ? latestOutbox.getStatus() : null,
                latestOutbox != null ? latestOutbox.getOpType() : null,
                latestOutbox != null ? latestOutbox.getLastError() : null,
                lastSuccessOutbox != null ? lastSuccessOutbox.getUpdatedAt() : null,
                latestOutbox != null ? latestOutbox.getCreatedAt() : null
        );
    }
}
