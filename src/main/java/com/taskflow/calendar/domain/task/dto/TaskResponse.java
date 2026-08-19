package com.taskflow.calendar.domain.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Task 조회 응답 DTO
 * - Entity를 직접 노출하지 않고 DTO로 변환
 * - deleted/deletedAt 필드는 노출하지 않음
 */
@Getter
public class TaskResponse {

    private final Long id;
    private final Long projectId;
    private final String title;
    private final String description;
    private final TaskStatus status;
    private final Long assigneeUserId;
    private final String assigneeName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime startAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime dueAt;

    private final Boolean calendarSyncEnabled;
    private final String calendarEventId;

    /**
     * Outbox까지 반영한 동기화 상태. 목록에서 점 하나로 표시한다.
     * 단건 조회처럼 Outbox를 안 읽은 경로에서는 null이다.
     */
    private final TaskSyncState syncState;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime updatedAt;

    private TaskResponse(Long id, Long projectId, String title, String description,
                         TaskStatus status, Long assigneeUserId, String assigneeName,
                         LocalDateTime startAt, LocalDateTime dueAt,
                         Boolean calendarSyncEnabled, String calendarEventId,
                         TaskSyncState syncState,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.assigneeUserId = assigneeUserId;
        this.assigneeName = assigneeName;
        this.startAt = startAt;
        this.dueAt = dueAt;
        this.calendarSyncEnabled = calendarSyncEnabled;
        this.calendarEventId = calendarEventId;
        this.syncState = syncState;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Entity -> DTO 변환 (Static Factory Method)
     * syncState는 Outbox를 읽어야 알 수 있어 여기서는 비운다.
     */
    public static TaskResponse from(Task task) {
        return from(task, null);
    }

    public static TaskResponse from(Task task, TaskSyncState syncState) {
        boolean ownedAssignee = task.getAssignee() != null
                && Objects.equals(task.getAssignee().getId(), task.getProject().getOwnerUserId());
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                ownedAssignee ? task.getAssignee().getId() : null,
                ownedAssignee ? task.getAssignee().getName() : null,
                task.getStartAt(),
                task.getDueAt(),
                task.getCalendarSyncEnabled(),
                task.getCalendarEventId(),
                syncState,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
