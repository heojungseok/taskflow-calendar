package com.taskflow.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 HTTP 응답으로 Gemini 호출 실패 분류를 확인한다. 네 기능(요약·추천·검색 의도·임베딩)이 이 분류를 같이 쓴다. */
class GeminiClientTest {

    private final GeminiClient client = new GeminiClient(new ObjectMapper());
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private HttpServer server;
    private GeminiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            Reply r = reply.get();
            r.headers().forEach(exchange.getResponseHeaders()::add);
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        properties = new GeminiProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
        properties.setTimeoutSeconds(5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsBodyAndSendsKeyToModelMethod() {
        respond(200, Map.of(), "{\"ok\":true}");

        String body = client.post(properties, "gemini-2.5-flash:generateContent", "{}");

        assertThat(body).isEqualTo("{\"ok\":true}");
        assertThat(seenPath.get()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(seenKey.get()).isEqualTo("test-key");
    }

    @Test
    void retryAfterHeaderMeansTemporaryRateLimit() {
        respond(429, Map.of("Retry-After", "120"), "{\"error\":{\"message\":\"Too many requests\"}}");

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY);
        assertThat(e.getClassificationSource()).isEqualTo("HEADER");
        assertThat(e.getRetryAfter()).isEqualTo("120");
        assertThat(e.isTemporary()).isTrue();
    }

    @Test
    void dailyLimitReasonMeansQuotaExhausted() {
        respond(
                429,
                Map.of(),
                "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"Daily limit reached\","
                        + "\"details\":[{\"reason\":\"dailyLimitExceeded\"}]}}");

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_QUOTA_EXHAUSTED);
        assertThat(e.getClassificationSource()).isEqualTo("BODY");
        assertThat(e.getUpstreamStatus()).isEqualTo("RESOURCE_EXHAUSTED");
        assertThat(e.getReasonHints()).contains("dailyLimitExceeded");
    }

    @Test
    void perMinuteQuotaIdMeansTemporaryRateLimitEvenWithQuotaWording() {
        // 실제 Gemini 분당 제한 응답. status와 메시지는 일일 한도와 똑같이 RESOURCE_EXHAUSTED·"current quota"다.
        respond(429, Map.of(), geminiQuotaError("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "37s"));

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_RATE_LIMITED_TEMPORARY);
        assertThat(e.getClassificationSource()).isEqualTo("QUOTA_ID");
        assertThat(e.getRetryAfter()).isEqualTo("37s");
        assertThat(e.getReasonHints()).contains("GenerateRequestsPerMinutePerProjectPerModel-FreeTier");
    }

    @Test
    void perDayQuotaIdMeansQuotaExhaustedEvenWithShortRetryDelay() {
        // 일일 한도도 retryDelay를 1s처럼 짧게 준다. 기다려도 풀리지 않는다.
        respond(429, Map.of(), geminiQuotaError("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "1s"));

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_QUOTA_EXHAUSTED);
        assertThat(e.getClassificationSource()).isEqualTo("QUOTA_ID");
    }

    private static String geminiQuotaError(String quotaId, String retryDelay) {
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

    @Test
    void generateInBodyIsNotMistakenForRateLimit() {
        // 예전 추천·검색 분류는 본문에 "rate"만 있으면 일시 제한으로 봤다. "generateContent"에도 들어 있다.
        respond(429, Map.of(), "{\"error\":{\"message\":\"models/gemini-2.5-flash:generateContent rejected\"}}");

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_429_UNKNOWN);
        assertThat(e.getClassificationSource()).isEqualTo("UNKNOWN");
    }

    @Test
    void serverErrorIsTemporaryButAuthErrorIsConfiguration() {
        respond(503, Map.of(), "");
        GeminiCallException unavailable = fail();
        assertThat(unavailable.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
        assertThat(unavailable.isTemporary()).isTrue();

        respond(403, Map.of(), "");
        GeminiCallException forbidden = fail();
        assertThat(forbidden.getErrorCode()).isEqualTo(ErrorCode.LLM_CONFIG_INVALID);
        assertThat(forbidden.isTemporary()).isFalse();
    }

    @Test
    void connectionFailureIsTemporaryWithoutStatus() {
        server.stop(0);

        GeminiCallException e = fail();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE);
        assertThat(e.getStatusCode()).isZero();
        assertThat(e.isTemporary()).isTrue();
    }

    private GeminiCallException fail() {
        return catchThrowableOfType(
                GeminiCallException.class, () -> client.post(properties, "gemini-2.5-flash:generateContent", "{}"));
    }

    private void respond(int status, Map<String, String> headers, String body) {
        reply.set(new Reply(status, headers, body));
    }

    private record Reply(int status, Map<String, String> headers, String body) {}
}
