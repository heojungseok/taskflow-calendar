package com.taskflow.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Gemini REST 호출. 키·모델·타임아웃은 기능별 properties로 받고, 실패는 GeminiCallException으로 분류해 던진다. */
@Component
public class GeminiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gemini429Classifier classifier429;

    public GeminiClient(ObjectMapper objectMapper) {
        this.classifier429 = new Gemini429Classifier(objectMapper);
    }

    /**
     * 요청 본문을 POST하고 응답 본문을 돌려준다.
     *
     * @param modelMethod {@code gemini-2.5-flash:generateContent}처럼 모델과 메서드
     * @return 응답 본문
     * @throws GeminiCallException 4xx·5xx 응답이나 응답 전 실패
     */
    public String post(GeminiProperties properties, String modelMethod, String requestBody) {
        String endpoint = properties.getBaseUrl().replaceAll("/$", "") + "/models/" + modelMethod;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("x-goog-api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw classify(
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        System.currentTimeMillis() - startedAt);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw beforeResponse("Gemini request was interrupted", startedAt);
        } catch (IOException e) {
            throw beforeResponse("Gemini request failed: " + e.getMessage(), startedAt);
        }
    }

    private GeminiCallException beforeResponse(String message, long startedAt) {
        return new GeminiCallException(
                ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                message,
                0,
                System.currentTimeMillis() - startedAt,
                null,
                null,
                null,
                List.of());
    }

    private GeminiCallException classify(
            int statusCode, Map<String, List<String>> headers, String body, long latencyMs) {
        String retryAfter = firstHeaderValue(headers, "Retry-After");
        if (statusCode == 429) {
            Gemini429Classifier.Classified429 c = classifier429.classify(retryAfter, body);
            return new GeminiCallException(
                    c.errorCode(),
                    c.message(),
                    statusCode,
                    latencyMs,
                    c.source(),
                    retryAfter != null ? retryAfter : c.retryDelay(),
                    c.upstreamStatus(),
                    c.hints());
        }
        ErrorCode errorCode;
        String message;
        if (statusCode == 400 || statusCode == 404) {
            errorCode = ErrorCode.LLM_CONFIG_INVALID;
            message = "Gemini configuration is invalid";
        } else if (statusCode >= 500) {
            errorCode = ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE;
            message = "Gemini temporary failure";
        } else {
            errorCode = ErrorCode.LLM_CONFIG_INVALID;
            message = "Gemini request failed";
        }
        return new GeminiCallException(errorCode, message, statusCode, latencyMs, null, retryAfter, null, List.of());
    }

    private static String firstHeaderValue(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return firstNonBlank(entry.getValue().toArray(String[]::new));
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
