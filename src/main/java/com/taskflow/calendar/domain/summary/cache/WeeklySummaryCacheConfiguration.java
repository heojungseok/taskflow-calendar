package com.taskflow.calendar.domain.summary.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(WeeklySummaryCacheProperties.class)
public class WeeklySummaryCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "summary.cache", name = "enabled", havingValue = "true")
    public LettuceConnectionFactory weeklySummaryRedisConnectionFactory(WeeklySummaryCacheProperties properties) {
        if (!StringUtils.hasText(properties.getRedisUrl())) {
            throw new IllegalStateException("REDIS_URL must be configured when weekly summary cache is enabled");
        }

        URI uri = URI.create(properties.getRedisUrl());
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalStateException("REDIS_URL must include a host");
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(uri.getHost(), port);
        if (StringUtils.hasText(uri.getUserInfo())) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            if (userInfo.length > 0 && StringUtils.hasText(userInfo[0])) {
                configuration.setUsername(userInfo[0]);
            }
            if (userInfo.length == 2 && StringUtils.hasText(userInfo[1])) {
                configuration.setPassword(RedisPassword.of(userInfo[1]));
            }
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfiguration =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(5))
                        .clientOptions(ClientOptions.builder()
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(Duration.ofSeconds(5))
                                        .build())
                                .build());

        if ("rediss".equalsIgnoreCase(uri.getScheme())) {
            clientConfiguration.useSsl();
        }

        return new LettuceConnectionFactory(configuration, clientConfiguration.build());
    }

    @Bean
    @ConditionalOnBean(LettuceConnectionFactory.class)
    public StringRedisTemplate weeklySummaryRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public WeeklySummaryCacheService redisWeeklySummaryCacheService(StringRedisTemplate redisTemplate,
                                                                   ObjectMapper objectMapper,
                                                                   WeeklySummaryCacheProperties properties) {
        return new RedisWeeklySummaryCacheService(redisTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(WeeklySummaryCacheService.class)
    public WeeklySummaryCacheService noopWeeklySummaryCacheService() {
        return new NoopWeeklySummaryCacheService();
    }
}
