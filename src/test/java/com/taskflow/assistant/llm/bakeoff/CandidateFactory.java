package com.taskflow.assistant.llm.bakeoff;

import com.taskflow.assistant.llm.GeminiLlmProvider;
import com.taskflow.assistant.llm.GeminiProperties;
import com.taskflow.assistant.llm.LlmClient;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmProvider;
import com.taskflow.assistant.llm.LlmRoutingProperties;
import com.taskflow.assistant.llm.OllamaLlmProperties;
import com.taskflow.assistant.llm.OllamaLlmProvider;
import com.taskflow.assistant.recommendation.GeminiRecommendationProperties;
import com.taskflow.assistant.recommendation.generator.LlmTaskRecommendationGenerator;
import com.taskflow.assistant.search.GeminiSearchProperties;
import com.taskflow.assistant.search.generator.LlmTaskSearchIntentParser;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.assistant.summary.generator.LlmWeeklySummaryGenerator;
import com.taskflow.common.TimeZones;
import com.taskflow.observability.TaskFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class CandidateFactory {

    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");
    static final int OLLAMA_TIMEOUT_SECONDS = new OllamaLlmProperties().getTimeoutSeconds();
    static final int OLLAMA_NUM_CTX = 8192;
    static final String OLLAMA_KEEP_ALIVE = "10m";

    private final ObjectMapper objectMapper;

    CandidateFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Candidate ollama(BakeoffConfig config, String model) {
        OllamaLlmProperties properties = new OllamaLlmProperties();
        properties.setBaseUrl(config.ollamaBaseUrl());
        properties.setModel(model);
        properties.setKeepAlive(OLLAMA_KEEP_ALIVE);
        properties.setNumCtx(OLLAMA_NUM_CTX);
        properties.setTimeoutSeconds(OLLAMA_TIMEOUT_SECONDS);
        LlmProvider provider = new OllamaLlmProvider(properties, objectMapper);
        Generators generators = generators(provider, new FeatureProperties(null, null));
        return new Candidate("ollama:" + model, "ollama", model, generators);
    }

    Candidate gemini(BakeoffConfig config) {
        FeatureProperties properties = new FeatureProperties(config.geminiApiKey(), config.geminiModel());
        Map<LlmFeature, GeminiProperties> settings = new EnumMap<>(LlmFeature.class);
        settings.put(LlmFeature.WEEKLY_SUMMARY, properties.summary());
        settings.put(LlmFeature.RECOMMENDATION, properties.recommendation());
        settings.put(LlmFeature.SEARCH_INTENT, properties.search());
        LlmProvider provider = new GeminiLlmProvider(settings, objectMapper);
        return new Candidate(
                "gemini:" + config.geminiModel(), "gemini", config.geminiModel(), generators(provider, properties));
    }

    private Generators generators(LlmProvider provider, FeatureProperties properties) {
        LlmProvider metered = new MeteredLlmProvider(provider);
        Map<LlmFeature, String> routing = new EnumMap<>(LlmFeature.class);
        for (LlmFeature feature : LlmFeature.values()) {
            routing.put(feature, metered.name());
        }
        LlmClient client = new LlmClient(List.of(metered), new LlmRoutingProperties(routing));
        TaskFlowMetrics metrics = new TaskFlowMetrics(new SimpleMeterRegistry());
        return new Generators(
                new LlmTaskSearchIntentParser(properties.search(), objectMapper, metrics, client),
                new LlmTaskRecommendationGenerator(properties.recommendation(), objectMapper, metrics, client),
                new LlmWeeklySummaryGenerator(
                        properties.summary(), objectMapper, metrics, Clock.fixed(NOW, TimeZones.SERVICE_ZONE), client));
    }

    private record FeatureProperties(
            GeminiSummaryProperties summary,
            GeminiRecommendationProperties recommendation,
            GeminiSearchProperties search) {

        FeatureProperties(String apiKey, String model) {
            this(summaryDefaults(apiKey, model), recommendationDefaults(apiKey, model), searchDefaults(apiKey, model));
        }

        private static GeminiSummaryProperties summaryDefaults(String apiKey, String model) {
            GeminiSummaryProperties properties = new GeminiSummaryProperties();
            apply(properties, apiKey, model, 1.0);
            properties.setTopK(20);
            properties.setTopP(0.8);
            return properties;
        }

        private static GeminiRecommendationProperties recommendationDefaults(String apiKey, String model) {
            GeminiRecommendationProperties properties = new GeminiRecommendationProperties();
            apply(properties, apiKey, model, 0.2);
            return properties;
        }

        private static GeminiSearchProperties searchDefaults(String apiKey, String model) {
            GeminiSearchProperties properties = new GeminiSearchProperties();
            apply(properties, apiKey, model, 0.2);
            return properties;
        }

        private static void apply(GeminiProperties properties, String apiKey, String model, double temperature) {
            if (apiKey != null) {
                properties.setApiKey(apiKey);
            }
            if (model != null) {
                properties.setModel(model);
            }
            properties.setTemperature(temperature);
            properties.setTimeoutSeconds(20);
        }
    }
}
