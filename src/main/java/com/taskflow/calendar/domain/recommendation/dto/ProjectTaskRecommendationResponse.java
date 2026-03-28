package com.taskflow.calendar.domain.recommendation.dto;

import com.taskflow.calendar.domain.project.Project;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectTaskRecommendationResponse {

    private final Long projectId;
    private final String projectName;
    private final LocalDateTime generatedAt;
    private final TaskRecommendationCacheStatus cacheStatus;
    private final int totalEligibleTaskCount;
    private final int candidateCount;
    private final int recommendedCount;
    private final List<ProjectTaskRecommendationItemResponse> items;

    private ProjectTaskRecommendationResponse(Long projectId,
                                              String projectName,
                                              LocalDateTime generatedAt,
                                              TaskRecommendationCacheStatus cacheStatus,
                                              int totalEligibleTaskCount,
                                              int candidateCount,
                                              int recommendedCount,
                                              List<ProjectTaskRecommendationItemResponse> items) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.generatedAt = generatedAt;
        this.cacheStatus = cacheStatus;
        this.totalEligibleTaskCount = totalEligibleTaskCount;
        this.candidateCount = candidateCount;
        this.recommendedCount = recommendedCount;
        this.items = List.copyOf(items);
    }

    public static ProjectTaskRecommendationResponse of(Project project,
                                                       LocalDateTime generatedAt,
                                                       TaskRecommendationCacheStatus cacheStatus,
                                                       int totalEligibleTaskCount,
                                                       int candidateCount,
                                                       List<ProjectTaskRecommendationItemResponse> items) {
        return new ProjectTaskRecommendationResponse(
                project.getId(),
                project.getName(),
                generatedAt,
                cacheStatus,
                totalEligibleTaskCount,
                candidateCount,
                items.size(),
                items
        );
    }

    public ProjectTaskRecommendationResponse withCacheStatus(TaskRecommendationCacheStatus cacheStatus) {
        return new ProjectTaskRecommendationResponse(
                projectId,
                projectName,
                generatedAt,
                cacheStatus,
                totalEligibleTaskCount,
                candidateCount,
                recommendedCount,
                items
        );
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public TaskRecommendationCacheStatus getCacheStatus() {
        return cacheStatus;
    }

    public int getTotalEligibleTaskCount() {
        return totalEligibleTaskCount;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getRecommendedCount() {
        return recommendedCount;
    }

    public List<ProjectTaskRecommendationItemResponse> getItems() {
        return items;
    }
}
