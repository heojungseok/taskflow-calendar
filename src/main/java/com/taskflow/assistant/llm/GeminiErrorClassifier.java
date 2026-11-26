package com.taskflow.assistant.llm;

import com.taskflow.common.ErrorCode;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

public final class GeminiErrorClassifier {

    private final Gemini429Classifier classifier429;

    public GeminiErrorClassifier(ObjectMapper objectMapper) {
        this.classifier429 = new Gemini429Classifier(objectMapper);
    }

    public LlmCallException classify(int statusCode, String retryAfter, String body, long latencyMs) {
        if (statusCode == 429) {
            Gemini429Classifier.Classified429 c = classifier429.classify(retryAfter, body);
            return new LlmCallException(
                    c.errorCode(),
                    c.message(),
                    statusCode,
                    latencyMs,
                    c.source(),
                    retryAfter != null ? retryAfter : c.retryDelay(),
                    c.upstreamStatus(),
                    c.hints());
        }
        if (statusCode == 400 || statusCode == 404) {
            return failure(
                    ErrorCode.LLM_CONFIG_INVALID, "Gemini configuration is invalid", statusCode, retryAfter, latencyMs);
        }
        if (statusCode >= 500) {
            return failure(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini temporary failure",
                    statusCode,
                    retryAfter,
                    latencyMs);
        }
        return failure(ErrorCode.LLM_CONFIG_INVALID, "Gemini request failed", statusCode, retryAfter, latencyMs);
    }

    private static LlmCallException failure(
            ErrorCode errorCode, String message, int statusCode, String retryAfter, long latencyMs) {
        return new LlmCallException(errorCode, message, statusCode, latencyMs, null, retryAfter, null, List.of());
    }
}
