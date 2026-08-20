package com.taskflow.calendar.domain.recommendation.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.recommendation.dto.ProjectTaskRecommendationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class RedisTaskRecommendationCacheService implements TaskRecommendationCacheService {

    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public Optional<ProjectTaskRecommendationResponse> find(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                record("miss");
                return Optional.empty();
            }
            ProjectTaskRecommendationResponse response = objectMapper.readValue(
                    json, ProjectTaskRecommendationResponse.class);
            record("hit");
            return Optional.of(response);
        } catch (RuntimeException | JsonProcessingException e) {
            record("error");
            log.warn("Recommendation cache read failed; continuing without cache. errorType={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, ProjectTaskRecommendationResponse response) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), TTL);
            record("write");
        } catch (RuntimeException | JsonProcessingException e) {
            record("error");
            log.warn("Recommendation cache write failed; continuing without cache. errorType={}",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void record(String outcome) {
        meterRegistry.counter("taskflow_cache_operations_total",
                "feature", "recommendation", "outcome", outcome).increment();
    }
}
