package com.taskflow.assistant.recommendation.cache;

import com.taskflow.assistant.recommendation.dto.ProjectTaskRecommendationResponse;
import java.util.Optional;

public interface TaskRecommendationCacheService {

    Optional<ProjectTaskRecommendationResponse> find(String key);

    void save(String key, ProjectTaskRecommendationResponse response);

    boolean isEnabled();
}
