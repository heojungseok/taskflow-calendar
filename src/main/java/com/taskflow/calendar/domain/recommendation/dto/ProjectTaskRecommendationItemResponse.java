package com.taskflow.calendar.domain.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.summary.TaskSyncState;
import com.taskflow.calendar.domain.task.TaskStatus;

import java.time.LocalDateTime;

public class ProjectTaskRecommendationItemResponse {

    private final Long taskId;
    private final int rank;
    private final int score;
    private final String title;
    private final TaskStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime dueAt;

    private final Boolean calendarSyncEnabled;
    private final String calendarEventId;
    private final TaskSyncState syncState;
    private final String primaryTag;
    private final String secondaryTag;
    private final String reason;

    private ProjectTaskRecommendationItemResponse(Long taskId,
                                                  int rank,
                                                  int score,
                                                  String title,
                                                  TaskStatus status,
                                                  LocalDateTime dueAt,
                                                  Boolean calendarSyncEnabled,
                                                  String calendarEventId,
                                                  TaskSyncState syncState,
                                                  String primaryTag,
                                                  String secondaryTag,
                                                  String reason) {
        this.taskId = taskId;
        this.rank = rank;
        this.score = score;
        this.title = title;
        this.status = status;
        this.dueAt = dueAt;
        this.calendarSyncEnabled = calendarSyncEnabled;
        this.calendarEventId = calendarEventId;
        this.syncState = syncState;
        this.primaryTag = primaryTag;
        this.secondaryTag = secondaryTag;
        this.reason = reason;
    }

    public static ProjectTaskRecommendationItemResponse of(Long taskId,
                                                           int rank,
                                                           int score,
                                                           String title,
                                                           TaskStatus status,
                                                           LocalDateTime dueAt,
                                                           Boolean calendarSyncEnabled,
                                                           String calendarEventId,
                                                           TaskSyncState syncState,
                                                           String primaryTag,
                                                           String secondaryTag,
                                                           String reason) {
        return new ProjectTaskRecommendationItemResponse(
                taskId,
                rank,
                score,
                title,
                status,
                dueAt,
                calendarSyncEnabled,
                calendarEventId,
                syncState,
                primaryTag,
                secondaryTag,
                reason
        );
    }

    public Long getTaskId() {
        return taskId;
    }

    public int getRank() {
        return rank;
    }

    public int getScore() {
        return score;
    }

    public String getTitle() {
        return title;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public Boolean getCalendarSyncEnabled() {
        return calendarSyncEnabled;
    }

    public String getCalendarEventId() {
        return calendarEventId;
    }

    public TaskSyncState getSyncState() {
        return syncState;
    }

    public String getPrimaryTag() {
        return primaryTag;
    }

    public String getSecondaryTag() {
        return secondaryTag;
    }

    public String getReason() {
        return reason;
    }
}
