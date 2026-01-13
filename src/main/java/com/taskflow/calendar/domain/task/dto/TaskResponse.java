package com.taskflow.calendar.domain.task.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.Getter;

import java.time.LocalDateTime;

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

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime updatedAt;

    private TaskResponse(Long id, Long projectId, String title, String description,
                         TaskStatus status, Long assigneeUserId, String assigneeName,
                         LocalDateTime startAt, LocalDateTime dueAt,
                         Boolean calendarSyncEnabled, String calendarEventId,
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
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Entity -> DTO 변환 (Static Factory Method)
     */
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getAssignee() != null ? task.getAssignee().getId() : null,
                task.getAssignee() != null ? task.getAssignee().getName() : null,
                task.getStartAt(),
                task.getDueAt(),
                task.getCalendarSyncEnabled(),
                task.getCalendarEventId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}