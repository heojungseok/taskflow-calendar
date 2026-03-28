package com.taskflow.calendar.domain.recommendation.generator;

import java.util.List;

public class TaskRecommendationGenerationResult {

    private final List<TaskRecommendationItemResult> items;

    private TaskRecommendationGenerationResult(List<TaskRecommendationItemResult> items) {
        this.items = List.copyOf(items);
    }

    public static TaskRecommendationGenerationResult of(List<TaskRecommendationItemResult> items) {
        return new TaskRecommendationGenerationResult(items);
    }

    public List<TaskRecommendationItemResult> getItems() {
        return items;
    }
}
