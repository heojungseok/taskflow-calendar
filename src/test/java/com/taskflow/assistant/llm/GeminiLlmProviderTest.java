package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.common.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import tools.jackson.databind.json.JsonMapper;

class GeminiLlmProviderTest {

    private final GeminiSummaryProperties summary = summaryProperties("key");
    private final GeminiLlmProvider provider = new GeminiLlmProvider(
            Map.of(LlmFeature.WEEKLY_SUMMARY, summary), JsonMapper.builder().build());

    private static final LlmRequest REQUEST =
            new LlmRequest(LlmFeature.WEEKLY_SUMMARY, "지시", "본문", Map.of("type", "object"), 1.0, 20, 0.8);

    @Test
    @DisplayName("기능의 Gemini 모델로 JSON 스키마를 강제하고 생각 단계를 최소로 줄인다")
    void options() {
        GoogleGenAiChatOptions options = provider.options(REQUEST);

        assertThat(options.getModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getOutputSchema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(options.getThinkingLevel()).isEqualTo(GoogleGenAiThinkingLevel.MINIMAL);
        assertThat(options.getTemperature()).isEqualTo(1.0);
        assertThat(options.getTopK()).isEqualTo(20);
        assertThat(options.getTopP()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("API 키가 없으면 호출하지 않고 키 누락으로 실패한다")
    void missingApiKey() {
        GeminiLlmProvider noKey = new GeminiLlmProvider(
                Map.of(LlmFeature.WEEKLY_SUMMARY, summaryProperties("")),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> noKey.generate(REQUEST))
                .isInstanceOfSatisfying(
                        LlmCallException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_API_KEY_MISSING));
    }

    @Nested
    @DisplayName("오류 분류")
    class Classify {

        @ParameterizedTest
        @CsvSource({
            "400, INVALID_ARGUMENT, LLM_CONFIG_INVALID",
            "404, NOT_FOUND, LLM_CONFIG_INVALID",
        })
        @DisplayName("요청·설정 오류는 설정 문제로 본다")
        void clientErrors(int code, String status, ErrorCode expected) {
            LlmCallException e = provider.classify(new ClientException(code, status, "bad"), 10);

            assertThat(e.getErrorCode()).isEqualTo(expected);
            assertThat(e.getStatusCode()).isEqualTo(code);
            assertThat(e.isTemporary()).isFalse();
        }

        @Test
        @DisplayName("429는 메시지로 한도 소진과 일시 제한을 가른다")
        void quotaExhausted() {
            LlmCallException e = provider.classify(
                    new ClientException(429, "RESOURCE_EXHAUSTED", "Daily limit exceeded for this project"), 10);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_QUOTA_EXHAUSTED);
            assertThat(e.isTemporary()).isTrue();
        }

        @Test
        @DisplayName("5xx와 Spring AI가 감싼 SDK 예외는 일시 장애로 본다")
        void serverErrorWrapped() {
            LlmCallException e = provider.classify(
                    new RuntimeException("wrapped", new ServerException(503, "UNAVAILABLE", "overloaded")), 10);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("응답 전 실패는 상태 코드 0의 일시 장애다")
        void beforeResponse() {
            LlmCallException e = provider.classify(new IllegalStateException("timeout"), 10);

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isZero();
        }
    }

    private static GeminiSummaryProperties summaryProperties(String apiKey) {
        GeminiSummaryProperties properties = new GeminiSummaryProperties();
        properties.setApiKey(apiKey);
        properties.setModel("gemini-3.5-flash-lite");
        return properties;
    }
}
