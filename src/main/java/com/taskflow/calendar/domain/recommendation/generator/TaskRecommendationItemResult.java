package com.taskflow.calendar.domain.recommendation.generator;

public class TaskRecommendationItemResult {

    private final Long taskId;
    private final String primaryTag;
    private final String secondaryTag;
    private final String reason;

    private TaskRecommendationItemResult(Long taskId,
                                         String primaryTag,
                                         String secondaryTag,
                                         String reason) {
        this.taskId = taskId;
        this.primaryTag = primaryTag;
        this.secondaryTag = secondaryTag;
        this.reason = reason;
    }

    public static TaskRecommendationItemResult of(Long taskId,
                                                  String primaryTag,
                                                  String secondaryTag,
                                                  String reason) {
        return new TaskRecommendationItemResult(taskId, primaryTag, secondaryTag, reason);
    }

    public Long getTaskId() {
        return taskId;
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
