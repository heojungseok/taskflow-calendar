package com.taskflow.assistant.summary.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "summary.cache")
public class WeeklySummaryCacheProperties {

    private long ttlSeconds = 604800;
}
