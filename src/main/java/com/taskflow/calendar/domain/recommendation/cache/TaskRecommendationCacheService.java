package com.taskflow.calendar.domain.recommendation.cache;

import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;

import java.util.Optional;

public interface TaskRecommendationCacheService {

    Optional<ProjectTaskRecommendationResponse> find(String key);

    void save(String key, ProjectTaskRecommendationResponse response);

    boolean isEnabled();
}
