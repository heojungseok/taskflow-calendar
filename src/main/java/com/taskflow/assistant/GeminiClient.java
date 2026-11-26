package com.taskflow.assistant;

import com.taskflow.assistant.llm.GeminiErrorClassifier;
import com.taskflow.assistant.llm.GeminiProperties;
import com.taskflow.assistant.llm.LlmCallException;
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
import tools.jackson.databind.ObjectMapper;

/** Gemini REST 호출. 키·모델·타임아웃은 기능별 properties로 받고, 실패는 LlmCallException으로 분류해 던진다. */
@Component
public class GeminiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final GeminiErrorClassifier errorClassifier;

    public GeminiClient(ObjectMapper objectMapper) {
        this.errorClassifier = new GeminiErrorClassifier(objectMapper);
    }

    /**
     * 요청 본문을 POST하고 응답 본문을 돌려준다.
     *
     * @param modelMethod {@code gemini-3.5-flash-lite:generateContent}처럼 모델과 메서드
     * @return 응답 본문
     * @throws LlmCallException 4xx·5xx 응답이나 응답 전 실패
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

    private LlmCallException beforeResponse(String message, long startedAt) {
        return LlmCallException.of(
                ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE, message, 0, System.currentTimeMillis() - startedAt);
    }

    private LlmCallException classify(int statusCode, Map<String, List<String>> headers, String body, long latencyMs) {
        return errorClassifier.classify(statusCode, firstHeaderValue(headers, "Retry-After"), body, latencyMs);
    }

    private static String firstHeaderValue(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue().stream()
                        .filter(value -> !value.isBlank())
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }
}
