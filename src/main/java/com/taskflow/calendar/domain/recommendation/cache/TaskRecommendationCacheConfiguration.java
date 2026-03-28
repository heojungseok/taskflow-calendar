package com.taskflow.calendar.domain.recommendation.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class TaskRecommendationCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "true")
    public TaskRecommendationCacheService redisTaskRecommendationCacheService(StringRedisTemplate redisTemplate,
                                                                             ObjectMapper objectMapper) {
        return new RedisTaskRecommendationCacheService(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public TaskRecommendationCacheService noopTaskRecommendationCacheService() {
        return new NoopTaskRecommendationCacheService();
    }
}
