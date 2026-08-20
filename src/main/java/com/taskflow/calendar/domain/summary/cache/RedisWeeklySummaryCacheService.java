package com.taskflow.calendar.domain.summary.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryCacheHealthResponse;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class RedisWeeklySummaryCacheService implements WeeklySummaryCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WeeklySummaryCacheProperties properties;
    private final MeterRegistry meterRegistry;

    @Override
    public Optional<WeeklySummaryResponse> find(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                record("miss");
                return Optional.empty();
            }
            WeeklySummaryResponse response = objectMapper.readValue(json, WeeklySummaryResponse.class);
            record("hit");
            return Optional.of(response);
        } catch (RuntimeException | JsonProcessingException e) {
            record("error");
            log.warn("Weekly summary cache read failed; continuing without cache. errorType={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, String latestKey, WeeklySummaryResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            Duration ttl = Duration.ofSeconds(properties.getTtlSeconds());
            redisTemplate.opsForValue().set(key, json, ttl);
            redisTemplate.opsForValue().set(latestKey, json, ttl);
            record("write");
        } catch (RuntimeException | JsonProcessingException e) {
            record("error");
            log.warn("Weekly summary cache write failed; continuing without cache. errorType={}",
                    e.getClass().getSimpleName());
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

    private void record(String outcome) {
        meterRegistry.counter("taskflow_cache_operations_total",
                "feature", "weekly_summary", "outcome", outcome).increment();
    }
}
