package com.taskflow.assistant.recommendation.cache;

import com.taskflow.assistant.recommendation.dto.ProjectTaskRecommendationResponse;
import java.util.Optional;

public class NoopTaskRecommendationCacheService implements TaskRecommendationCacheService {

    @Override
    public Optional<ProjectTaskRecommendationResponse> find(String key) {
        return Optional.empty();
    }

    @Override
    public void save(String key, ProjectTaskRecommendationResponse response) {
        // Cache disabled.
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
