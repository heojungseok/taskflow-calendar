package com.taskflow.assistant.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class GeminiEmbeddingModelTest {

    private static final String MODEL = "gemini-embedding-001";
    private static final String OK_BODY = """
            {"embeddings":[{"values":[0.25,-0.5,1.0]},{"values":[2.0,4.0,8.0]}]}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private FakeUpstreamServer server;
    private GeminiProperties properties;
    private GeminiEmbeddingModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeUpstreamServer();
        properties = new GeminiProperties();
        properties.setApiKey("embedding-key");
        properties.setBaseUrl(server.baseUrl() + "/v1beta");
        properties.setTimeoutSeconds(5);
        model = new GeminiEmbeddingModel(properties, MODEL, json);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Nested
    @DisplayName("요청")
    class Request {

        @Test
        @DisplayName("batchEmbedContents에 RETRIEVAL_DOCUMENT를 담고 outputDimensionality는 보내지 않는다")
        void sendsRetrievalDocumentWithoutOutputDimensionality() throws Exception {
            server.respond(200, OK_BODY);

            model.call(new EmbeddingRequest(List.of("첫 문서", "둘째 문서"), null));

            assertThat(server.hits()).isEqualTo(1);
            assertThat(server.lastPath()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
            assertThat(server.lastApiKey()).isEqualTo("embedding-key");
            JsonNode requests = json.readTree(server.lastBody()).path("requests");
            assertThat(requests.size()).isEqualTo(2);
            String[] texts = {"첫 문서", "둘째 문서"};
            for (int i = 0; i < 2; i++) {
                JsonNode request = requests.get(i);
                assertThat(request.path("model").asString()).isEqualTo("models/gemini-embedding-001");
                assertThat(request.path("taskType").asString()).isEqualTo("RETRIEVAL_DOCUMENT");
                assertThat(request.path("content")
                                .path("parts")
                                .get(0)
                                .path("text")
                                .asString())
                        .isEqualTo(texts[i]);
                assertThat(request.has("outputDimensionality")).isFalse();
                assertThat(request.has("title")).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        @DisplayName("벡터 값과 순서를 그대로 돌려준다")
        void keepsValuesAndOrder() {
            server.respond(200, OK_BODY);

            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("a", "b"), null));

            assertThat(response.getResults()).hasSize(2);
            assertThat(response.getResults().get(0).getOutput()).containsExactly(0.25f, -0.5f, 1.0f);
            assertThat(response.getResults().get(1).getOutput()).containsExactly(2.0f, 4.0f, 8.0f);
            assertThat(response.getResults().get(1).getIndex()).isEqualTo(1);
        }

        @Test
        @DisplayName("임베딩이 없거나 값이 빠져 있으면 빈 결과로 돌려 호출한 쪽이 검증하게 한다")
        void missingValuesBecomeEmpty() {
            server.respond(200, "{\"embeddings\":[{}]}");

            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("a"), null));

            assertThat(response.getResults().get(0).getOutput()).isEmpty();
        }

        @Test
        @DisplayName("값에 null이 있으면 잘못된 응답이다")
        void nullValueIsInvalidResponse() {
            server.respond(200, "{\"embeddings\":[{\"values\":[0.5,null]}]}");

            LlmCallException e = catchThrowableOfType(
                    LlmCallException.class, () -> model.call(new EmbeddingRequest(List.of("a"), null)));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_INVALID_RESPONSE);
        }

        @Test
        @DisplayName("embeddings 필드가 없으면 결과가 비어 있다")
        void missingEmbeddings() {
            server.respond(200, "{}");

            assertThat(model.call(new EmbeddingRequest(List.of("a"), null)).getResults())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("실패 분류")
    class Failure {

        @Test
        @DisplayName("Retry-After 헤더가 있는 429는 일시 제한이다")
        void retryAfterHeader() {
            server.respond(429, Map.of("Retry-After", "120"), "{\"error\":{\"message\":\"Too many requests\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY);
            assertThat(e.getClassificationSource()).isEqualTo("HEADER");
            assertThat(e.getRetryAfter()).isEqualTo("120");
            assertThat(server.hits()).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({
            "GenerateRequestsPerMinutePerProjectPerModel-FreeTier, LLM_RATE_LIMITED_TEMPORARY",
            "GenerateRequestsPerDayPerProjectPerModel-FreeTier, LLM_QUOTA_EXHAUSTED"
        })
        @DisplayName("429는 quotaId로 분당 제한과 일일 소진을 가르고 서버를 한 번만 부른다")
        void quotaId(String quotaId, ErrorCode expected) {
            server.respond(429, quotaError(quotaId));

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(expected);
            assertThat(e.getClassificationSource()).isEqualTo("QUOTA_ID");
            assertThat(e.getRetryAfter()).isEqualTo("37s");
            assertThat(server.hits()).isEqualTo(1);
        }

        @Test
        @DisplayName("본문의 generateContent를 분당 제한(rate)으로 오인하지 않는다")
        void generateContentInBodyIsNotRateLimit() {
            server.respond(429, "{\"error\":{\"message\":\"models/gemini-2.5-flash:generateContent rejected\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_429_UNKNOWN);
            assertThat(e.getClassificationSource()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("403은 설정 오류이고 일시 실패가 아니다")
        void forbiddenIsConfiguration() {
            server.respond(403, "");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
            assertThat(e.isTemporary()).isFalse();
        }

        @Test
        @DisplayName("일일 한도 reason이 있는 429는 쿼터 소진이다")
        void dailyLimitReason() {
            server.respond(
                    429,
                    "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"Daily limit reached\","
                            + "\"details\":[{\"reason\":\"dailyLimitExceeded\"}]}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_QUOTA_EXHAUSTED);
            assertThat(e.getClassificationSource()).isEqualTo("BODY");
            assertThat(e.getReasonHints()).contains("dailyLimitExceeded");
            assertThat(e.getUpstreamStatus()).isEqualTo("RESOURCE_EXHAUSTED");
        }

        @Test
        @DisplayName("404는 설정 오류이고 일시 실패가 아니다")
        void notFoundIsConfiguration() {
            server.respond(404, "{\"error\":{\"message\":\"model not found\"}}");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
            assertThat(e.isTemporary()).isFalse();
            assertThat(server.hits()).isEqualTo(1);
        }

        @Test
        @DisplayName("503은 일시 실패이고 SDK가 다시 시도하지 않는다")
        void unavailableIsTemporaryWithoutRetry() {
            server.respond(503, "");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.isTemporary()).isTrue();
            assertThat(server.hits()).isEqualTo(1);
        }

        @Test
        @DisplayName("연결 실패는 상태 코드 없는 일시 실패다")
        void connectionFailure() {
            server.close();

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
            assertThat(e.getStatusCode()).isZero();
        }

        @Test
        @DisplayName("API 키가 없으면 요청을 보내지 않는다")
        void missingKey() {
            properties.setApiKey(" ");

            LlmCallException e = fail();

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_API_KEY_MISSING);
            assertThat(server.hits()).isZero();
        }

        private LlmCallException fail() {
            return catchThrowableOfType(
                    LlmCallException.class, () -> model.call(new EmbeddingRequest(List.of("문서"), null)));
        }
    }

    private static String quotaError(String quotaId) {
        return """
                {"error":{"code":429,
                 "message":"You exceeded your current quota, please check your plan and billing details.",
                 "status":"RESOURCE_EXHAUSTED",
                 "details":[
                  {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{
                    "quotaMetric":"generativelanguage.googleapis.com/embed_content_free_tier_requests",
                    "quotaId":"%s","quotaDimensions":{"location":"global","model":"gemini-embedding-001"},"quotaValue":"100"}]},
                  {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}
                """.formatted(quotaId);
    }
}
