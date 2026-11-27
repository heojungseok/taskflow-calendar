package com.taskflow.assistant.llm;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("llm")
public record LlmRoutingProperties(Map<LlmFeature, String> provider) {

    private static final String DEFAULT_PROVIDER = "gemini";

    public LlmRoutingProperties {
        provider = provider == null ? Map.of() : Map.copyOf(provider);
    }

    public String providerFor(LlmFeature feature) {
        return provider.getOrDefault(feature, DEFAULT_PROVIDER);
    }
}
