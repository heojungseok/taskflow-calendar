package com.taskflow.planning.task;

import com.taskflow.common.ErrorCode;
import com.taskflow.common.exception.ValidationException;
import java.time.LocalDateTime;

/** Task 일정 규칙: 시작이 마감보다 늦을 수 없고, 캘린더 동기화에는 마감이 필요하다. */
final class TaskScheduleRules {

    private static final int DEFAULT_SYNC_EVENT_DURATION_HOURS = 1;

    private TaskScheduleRules() {}

    /**
     * 일정 검증: startAt <= dueAt
     */
    static void validateSchedule(LocalDateTime startAt, LocalDateTime dueAt) {
        if (startAt != null && dueAt != null && startAt.isAfter(dueAt)) {
            throw new ValidationException(ErrorCode.SCHEDULE_INVALID);
        }
    }

    /**
     * 캘린더 동기화 검증: calendarSyncEnabled=true면 dueAt 필수
     */
    static void validateCalendarSync(Boolean calendarSyncEnabled, LocalDateTime dueAt) {
        if (Boolean.TRUE.equals(calendarSyncEnabled) && dueAt == null) {
            throw new ValidationException(ErrorCode.CALENDAR_SYNC_REQUIRES_DUE_AT);
        }
    }

    static LocalDateTime normalizeSyncStartAt(Boolean calendarSyncEnabled, LocalDateTime startAt, LocalDateTime dueAt) {
        if (!Boolean.TRUE.equals(calendarSyncEnabled)) {
            return startAt;
        }
        if (startAt != null) {
            return startAt;
        }
        if (dueAt == null) {
            return null;
        }
        return dueAt.minusHours(DEFAULT_SYNC_EVENT_DURATION_HOURS);
    }
}
