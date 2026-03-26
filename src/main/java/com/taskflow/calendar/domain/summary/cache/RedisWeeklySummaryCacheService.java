package com.taskflow.calendar.domain.summary.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class RedisWeeklySummaryCacheService implements WeeklySummaryCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WeeklySummaryCacheProperties properties;

    @Override
    public Optional<WeeklySummaryResponse> find(String key) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(json, WeeklySummaryResponse.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read weekly summary cache", e);
        }
    }

    @Override
    public void save(String key, String latestKey, WeeklySummaryResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofSeconds(properties.getTtlSeconds());
            redisTemplate.opsForValue().set(key, json, ttl);
            redisTemplate.opsForValue().set(latestKey, json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write weekly summary cache", e);
        }
    }

    @Override
    public WeeklySummaryCacheHealthResponse healthCheck() {
        String key = "weekly-summary:health:" + UUID.randomUUID();
        String value = "ok:" + UUID.randomUUID();

        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(30));
            String stored = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);

            if (!value.equals(stored)) {
                return WeeklySummaryCacheHealthResponse.unhealthy("Redis read/write mismatch");
            }

            return WeeklySummaryCacheHealthResponse.healthy(key, stored);
        } catch (RuntimeException e) {
            return WeeklySummaryCacheHealthResponse.unhealthy(e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
