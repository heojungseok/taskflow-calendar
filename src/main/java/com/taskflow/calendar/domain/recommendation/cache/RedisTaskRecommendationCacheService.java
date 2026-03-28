package com.taskflow.calendar.domain.recommendation.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
public class RedisTaskRecommendationCacheService implements TaskRecommendationCacheService {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<ProjectTaskRecommendationResponse> find(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(json, ProjectTaskRecommendationResponse.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read task recommendation cache", e);
        }
    }

    @Override
    public void save(String key, ProjectTaskRecommendationResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write task recommendation cache", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
