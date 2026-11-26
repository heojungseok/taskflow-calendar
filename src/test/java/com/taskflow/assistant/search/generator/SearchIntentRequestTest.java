package com.taskflow.assistant.search.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.taskflow.assistant.search.GeminiSearchProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SearchIntentRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Gemini 3 계열이 받는 thinkingLevel minimal을 보내고 거부되는 thinkingBudget은 보내지 않는다")
    void requestsMinimalThinkingLevel() throws Exception {
        String body = SearchIntentRequest.buildRequestBody(new GeminiSearchProperties(), objectMapper, "이번 주 배포");

        JsonNode thinkingConfig =
                objectMapper.readTree(body).path("generationConfig").path("thinkingConfig");
        assertEquals("minimal", thinkingConfig.path("thinkingLevel").asString());
        assertTrue(thinkingConfig.path("thinkingBudget").isMissingNode());
    }
}
