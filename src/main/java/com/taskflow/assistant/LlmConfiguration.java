package com.taskflow.assistant;

import com.taskflow.assistant.llm.GeminiLlmProvider;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmRoutingProperties;
import com.taskflow.assistant.llm.OllamaLlmProperties;
import com.taskflow.assistant.llm.OllamaLlmProvider;
import com.taskflow.assistant.recommendation.GeminiRecommendationProperties;
import com.taskflow.assistant.search.GeminiSearchProperties;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties({LlmRoutingProperties.class, OllamaLlmProperties.class})
class LlmConfiguration {

    @Bean
    GeminiLlmProvider geminiLlmProvider(
            GeminiSummaryProperties summary,
            GeminiRecommendationProperties recommendation,
            GeminiSearchProperties search,
            ObjectMapper objectMapper) {
        return new GeminiLlmProvider(
                Map.of(
                        LlmFeature.WEEKLY_SUMMARY, summary,
                        LlmFeature.RECOMMENDATION, recommendation,
                        LlmFeature.SEARCH_INTENT, search),
                objectMapper);
    }

    @Bean
    OllamaLlmProvider ollamaLlmProvider(OllamaLlmProperties properties, ObjectMapper objectMapper) {
        return new OllamaLlmProvider(properties, objectMapper);
    }
}
