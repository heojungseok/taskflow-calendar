package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import tools.jackson.databind.json.JsonMapper;

class GeminiLlmProviderTest {

    private static final LlmRequest REQUEST =
            new LlmRequest(LlmFeature.WEEKLY_SUMMARY, "지시", "본문", Map.of("type", "object"), 1.0, 20, 0.8);
    private static final String OK_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":1}"}],"role":"model"},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":3,"totalTokenCount":14},
             "modelVersion":"gemini-3.5-flash-lite"}
            """;

    private FakeUpstreamServer server;
    private GeminiSummaryProperties summary;
    private GeminiLlmProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeUpstreamServer();
        summary = summaryProperties("key");
        summary.setBaseUrl(server.baseUrl() + "/v1beta");
        summary.setTimeoutSeconds(5);
        provider = providerFor(summary);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

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
        GeminiLlmProvider noKey = providerFor(summaryProperties(""));

        LlmCallException e = catchThrowableOfType(LlmCallException.class, () -> noKey.generate(REQUEST));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_API_KEY_MISSING);
        assertThat(server.hits()).isZero();
    }

    @Test
    @DisplayName("설정한 baseUrl과 API 키로 호출하고 응답을 읽는다")
    void callsConfiguredTarget() {
        server.respond(200, OK_BODY);

        LlmResponse response = provider.generate(REQUEST);

        assertThat(response.text()).isEqualTo("{\"a\":1}");
        assertThat(response.totalTokens()).isEqualTo(14);
        assertThat(server.lastPath()).isEqualTo("/v1beta/models/gemini-3.5-flash-lite:generateContent");
        assertThat(server.lastApiKey()).isEqualTo("key");
    }

    @Nested
    @DisplayName("재시도")
    class Retry {

        @ParameterizedTest
        @ValueSource(ints = {429, 503})
        @DisplayName("SDK가 재시도하지 않아 서버를 한 번만 부른다")
        void callsOnce(int status) {
            server.respond(status, "{\"error\":{\"message\":\"x\"}}");

            LlmCallException e = catchThrowableOfType(LlmCallException.class, () -> provider.generate(REQUEST));

            assertThat(e).isNotNull();
            assertThat(server.hits()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("오류 분류")
    class Classify {

        @Test
        @DisplayName("분당 quotaId 429는 일시 제한이고 retryDelay를 남긴다")
        void perMinute() {
            server.respond(429, quotaError("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "37s"));

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY);
            assertThat(e.getClassificationSource()).isEqualTo("QUOTA_ID");
            assertThat(e.getRetryAfter()).isEqualTo("37s");
            assertThat(e.getStatusCode()).isEqualTo(429);
            assertThat(e.getReasonHints()).contains("GenerateRequestsPerMinutePerProjectPerModel-FreeTier");
        }

        @Test
        @DisplayName("일일 quotaId 429는 retryDelay가 짧아도 한도 소진이다")
        void perDay() {
            server.respond(429, quotaError("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "1s"));

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_QUOTA_EXHAUSTED);
            assertThat(e.getClassificationSource()).isEqualTo("QUOTA_ID");
        }

        @Test
        @DisplayName("Retry-After 헤더가 있는 429는 헤더로 일시 제한이다")
        void retryAfterHeader() {
            server.respond(429, Map.of("Retry-After", "120"), "{\"error\":{\"message\":\"Too many requests\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY);
            assertThat(e.getClassificationSource()).isEqualTo("HEADER");
            assertThat(e.getRetryAfter()).isEqualTo("120");
        }

        @Test
        @DisplayName("404는 상태 코드를 지닌 설정 문제다")
        void notFound() {
            server.respond(404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"message\":\"no model\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
            assertThat(e.getStatusCode()).isEqualTo(404);
            assertThat(e.isTemporary()).isFalse();
        }

        @Test
        @DisplayName("503은 상태 코드를 지닌 일시 장애다")
        void unavailable() {
            server.respond(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isEqualTo(503);
            assertThat(e.isTemporary()).isTrue();
        }

        @Test
        @DisplayName("응답이 타임아웃보다 늦으면 타임아웃 안팎에서 상태 코드 0의 일시 장애다")
        void timeout() {
            summary.setTimeoutSeconds(1);
            GeminiLlmProvider quick = providerFor(summary);
            server.respondAfter(6000, 200, OK_BODY);

            long startedAt = System.currentTimeMillis();
            LlmCallException e = catchThrowableOfType(LlmCallException.class, () -> quick.generate(REQUEST));
            long elapsed = System.currentTimeMillis() - startedAt;

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isZero();
            assertThat(elapsed).isLessThan(2500);
        }

        @Test
        @DisplayName("모델이 받지 못하는 옵션으로 호출 전에 실패하면 설정 문제다")
        void unsupportedOption() {
            summary.setModel("gemini-2.5-flash");
            GeminiLlmProvider unsupported = providerFor(summary);
            server.respond(200, OK_BODY);

            LlmCallException e = catchThrowableOfType(LlmCallException.class, () -> unsupported.generate(REQUEST));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
            assertThat(e.isTemporary()).isFalse();
            assertThat(server.hits()).isZero();
        }

        @Test
        @DisplayName("연결할 수 없으면 상태 코드 0의 일시 장애다")
        void connectionRefused() {
            server.close();

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isZero();
        }

        private LlmCallException fail() {
            return catchThrowableOfType(LlmCallException.class, () -> provider.generate(REQUEST));
        }
    }

    private static String quotaError(String quotaId, String retryDelay) {
        return """
                {"error":{"code":429,
                 "message":"You exceeded your current quota, please check your plan and billing details.",
                 "status":"RESOURCE_EXHAUSTED",
                 "details":[
                  {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{
                    "quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                    "quotaId":"%s","quotaDimensions":{"location":"global","model":"gemini-2.5-flash"},"quotaValue":"10"}]},
                  {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"%s"}]}}
                """.formatted(quotaId, retryDelay);
    }

    private static GeminiLlmProvider providerFor(GeminiSummaryProperties properties) {
        return new GeminiLlmProvider(
                Map.of(LlmFeature.WEEKLY_SUMMARY, properties),
                JsonMapper.builder().build());
    }

    private static GeminiSummaryProperties summaryProperties(String apiKey) {
        GeminiSummaryProperties properties = new GeminiSummaryProperties();
        properties.setApiKey(apiKey);
        properties.setModel("gemini-3.5-flash-lite");
        return properties;
    }
}
