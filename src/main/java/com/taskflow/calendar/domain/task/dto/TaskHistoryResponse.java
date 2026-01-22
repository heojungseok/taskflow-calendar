package com.taskflow.calendar.domain.task.dto;

import com.taskflow.calendar.domain.task.TaskChangeType;
import com.taskflow.calendar.domain.task.TaskHistory;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * TaskHistory 조회 응답 DTO
 */
@Getter
public class TaskHistoryResponse {

    private TaskChangeType changeType;
    private String beforeValue;
    private String afterValue;
    private String changedByUserName;  // User의 이름
    private LocalDateTime createdAt;

    private TaskHistoryResponse(TaskChangeType changeType, String beforeValue, String afterValue, String changedByUserName, LocalDateTime createdAt) {
        this.changeType = changeType;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.changedByUserName = changedByUserName;
        this.createdAt = createdAt;

    }

    public static TaskHistoryResponse from(TaskHistory history) {
        return new TaskHistoryResponse(
                history.getChangeType(),
                history.getBeforeValue(),
                history.getAfterValue(),
                history.getChangedByUser().getName(),
                history.getCreatedAt()
        );
    }
}