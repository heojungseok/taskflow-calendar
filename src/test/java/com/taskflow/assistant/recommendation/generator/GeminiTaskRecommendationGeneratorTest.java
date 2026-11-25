package com.taskflow.assistant.recommendation.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.assistant.GeminiClient;
import com.taskflow.assistant.recommendation.GeminiRecommendationProperties;
import com.taskflow.observability.TaskFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeminiTaskRecommendationGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("Gemini 3 계열이 받는 thinkingLevel minimal을 보내고 거부되는 thinkingBudget은 보내지 않는다")
    void requestsMinimalThinkingLevel() throws Exception {
        GeminiRecommendationProperties properties = new GeminiRecommendationProperties();
        properties.setApiKey("test-key");
        TaskFlowMetrics metrics = new TaskFlowMetrics(new SimpleMeterRegistry());
        GeminiClient geminiClient = mock(GeminiClient.class);
        when(geminiClient.post(any(), anyString(), anyString())).thenReturn("{}");
        GeminiTaskRecommendationGenerator generator =
                new GeminiTaskRecommendationGenerator(properties, objectMapper, metrics, geminiClient);

        try {
            generator.generate(null, List.of(), 1, LocalDate.of(2026, 11, 25));
        } catch (RuntimeException ignored) {
            // 응답 "{}"는 해석에 실패한다. 이 테스트는 보낸 요청만 본다.
        }

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(geminiClient).post(eq(properties), anyString(), body.capture());
        JsonNode thinkingConfig =
                objectMapper.readTree(body.getValue()).path("generationConfig").path("thinkingConfig");
        assertEquals("minimal", thinkingConfig.path("thinkingLevel").asText());
        assertTrue(thinkingConfig.path("thinkingBudget").isMissingNode());
    }
}
