package com.taskflow.calendar.domain.recommendation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class TaskRecommendationCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "true")
    public TaskRecommendationCacheService redisTaskRecommendationCacheService(StringRedisTemplate redisTemplate,
                                                                             ObjectMapper objectMapper,
                                                                             MeterRegistry meterRegistry) {
        return new RedisTaskRecommendationCacheService(redisTemplate, objectMapper, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public TaskRecommendationCacheService noopTaskRecommendationCacheService() {
        return new NoopTaskRecommendationCacheService();
    }
}
