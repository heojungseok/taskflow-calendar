package com.taskflow.planning.task.dto;

import com.taskflow.common.TimeZones;
import com.taskflow.planning.task.TaskChangeType;
import com.taskflow.planning.task.TaskHistory;
import java.time.Instant;
import lombok.Getter;

/**
 * TaskHistory 조회 응답 DTO
 */
@Getter
public class TaskHistoryResponse {

    private TaskChangeType changeType;
    private String beforeValue;
    private String afterValue;
    private String changedByUserName; // User의 이름
    private Instant createdAt;

    private TaskHistoryResponse(
            TaskChangeType changeType,
            String beforeValue,
            String afterValue,
            String changedByUserName,
            Instant createdAt) {
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
                TimeZones.toUtcInstant(history.getCreatedAt()));
    }
}
