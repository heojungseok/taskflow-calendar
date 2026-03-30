package com.taskflow.calendar.domain.search.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.task.TaskStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class TaskSearchResultItemResponse {

    private final Long taskId;
    private final Long projectId;
    private final String projectName;
    private final String title;
    private final TaskStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime dueAt;

    private final Boolean calendarSyncEnabled;
    private final String calendarEventId;
    private final TaskSyncState syncState;
    private final int score;

    private TaskSearchResultItemResponse(Long taskId,
                                         Long projectId,
                                         String projectName,
                                         String title,
                                         TaskStatus status,
                                         LocalDateTime dueAt,
                                         Boolean calendarSyncEnabled,
                                         String calendarEventId,
                                         TaskSyncState syncState,
                                         int score) {
        this.taskId = taskId;
        this.projectId = projectId;
        this.projectName = projectName;
        this.title = title;
        this.status = status;
        this.dueAt = dueAt;
        this.calendarSyncEnabled = calendarSyncEnabled;
        this.calendarEventId = calendarEventId;
        this.syncState = syncState;
        this.score = score;
    }

    public static TaskSearchResultItemResponse of(Long taskId,
                                                  Long projectId,
                                                  String projectName,
                                                  String title,
                                                  TaskStatus status,
                                                  LocalDateTime dueAt,
                                                  Boolean calendarSyncEnabled,
                                                  String calendarEventId,
                                                  TaskSyncState syncState,
                                                  int score) {
        return new TaskSearchResultItemResponse(
                taskId,
                projectId,
                projectName,
                title,
                status,
                dueAt,
                calendarSyncEnabled,
                calendarEventId,
                syncState,
                score
        );
    }
}
