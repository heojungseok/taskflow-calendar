package com.taskflow.assistant.search.generator;

import com.taskflow.assistant.GeminiCallException;
import com.taskflow.assistant.GeminiClient;
import com.taskflow.assistant.GeminiPayloadReader;
import com.taskflow.assistant.search.*;
import com.taskflow.assistant.search.GeminiSearchProperties;
import com.taskflow.assistant.search.exception.TaskSearchGenerationException;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class GeminiTaskSearchIntentParser implements TaskSearchIntentParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiTaskSearchIntentParser.class);

    private final GeminiSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskFlowMetrics metrics;
    private final GeminiClient geminiClient;

    @Override
    public SearchIntent parse(String query) {
        return metrics.observeGeminiCall("search_intent", () -> parseUnobserved(query));
    }

    private SearchIntent parseUnobserved(String query) {
        validateConfiguration();

        String requestBody = SearchIntentRequest.buildRequestBody(properties, objectMapper, query);

        long startedAt = System.currentTimeMillis();
        String responseBody;
        try {
            responseBody = geminiClient.post(properties, properties.getModel() + ":generateContent", requestBody);
        } catch (GeminiCallException e) {
            log.warn(
                    "Gemini search intent request failed. statusCode={}, errorCode={}, latencyMs={}",
                    e.getStatusCode(),
                    e.getErrorCode().getCode(),
                    e.getLatencyMs());
            throw new TaskSearchGenerationException(
                    e.getErrorCode(), "Gemini search intent request failed: " + e.getMessage());
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        JsonNode payload;
        try {
            JsonNode textNode = objectMapper.readTree(responseBody).at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode() || textNode.asString("").isBlank()) {
                throw new TaskSearchGenerationException(
                        ErrorCode.LLM_INVALID_RESPONSE, "Gemini search intent response did not contain text");
            }
            payload = new GeminiPayloadReader(objectMapper).read(textNode.asString(""));
        } catch (JacksonException e) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini search intent payload was not valid JSON");
        }

        log.info(
                "Gemini search intent parsed. model={}, latencyMs={}, requestBodyLength={}",
                properties.getModel(),
                latencyMs,
                requestBody.length());

        return SearchIntentPayloadNormalizer.normalize(query, payload);
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_API_KEY_MISSING, "GEMINI_SEARCH_API_KEY is not configured");
        }
    }
}
