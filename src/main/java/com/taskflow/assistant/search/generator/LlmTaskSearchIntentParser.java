package com.taskflow.assistant.search.generator;

import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmClient;
import com.taskflow.assistant.llm.LlmJsonReader;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.llm.LlmResponse;
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
public class LlmTaskSearchIntentParser implements TaskSearchIntentParser {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskSearchIntentParser.class);

    private final GeminiSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskFlowMetrics metrics;
    private final LlmClient llmClient;

    @Override
    public SearchIntent parse(String query) {
        return metrics.observeGeminiCall("search_intent", () -> parseUnobserved(query));
    }

    private SearchIntent parseUnobserved(String query) {
        LlmRequest request = SearchIntentRequest.build(properties, query);

        long startedAt = System.currentTimeMillis();
        LlmResponse response;
        try {
            response = llmClient.generate(request);
        } catch (LlmCallException e) {
            log.warn(
                    "Gemini search intent request failed. statusCode={}, errorCode={}, latencyMs={}",
                    e.getStatusCode(),
                    e.getErrorCode().getCode(),
                    e.getLatencyMs());
            throw new TaskSearchGenerationException(
                    e.getErrorCode(), "Gemini search intent request failed: " + e.getMessage());
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        if (response.text() == null || response.text().isBlank()) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini search intent response did not contain text");
        }
        JsonNode payload;
        try {
            payload = new LlmJsonReader(objectMapper).read(response.text());
        } catch (JacksonException e) {
            throw new TaskSearchGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini search intent payload was not valid JSON");
        }

        log.info(
                "Gemini search intent parsed. model={}, latencyMs={}, requestBodyLength={}",
                response.model(),
                latencyMs,
                request.systemInstruction().length() + request.userPrompt().length());

        return SearchIntentPayloadNormalizer.normalize(query, payload);
    }
}
