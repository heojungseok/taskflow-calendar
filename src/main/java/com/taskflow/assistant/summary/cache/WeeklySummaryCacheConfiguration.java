package com.taskflow.assistant.summary.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 연결은 Spring Boot 자동 설정(spring.data.redis.*)이 만든다. */
@Configuration
@EnableConfigurationProperties(WeeklySummaryCacheProperties.class)
public class WeeklySummaryCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "true")
    public WeeklySummaryCacheService redisWeeklySummaryCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WeeklySummaryCacheProperties properties,
            MeterRegistry meterRegistry) {
        return new RedisWeeklySummaryCacheService(redisTemplate, objectMapper, properties, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    public WeeklySummaryCacheService noopWeeklySummaryCacheService() {
        return new NoopWeeklySummaryCacheService();
    }
}
