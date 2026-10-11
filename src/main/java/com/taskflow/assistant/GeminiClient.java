package com.taskflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Gemini REST 호출과 실패 분류. 키·모델·타임아웃은 기능별 properties로 받는다.
 *
 * <p>429는 한도 소진(QUOTA)과 일시적 제한(RATE)을 Retry-After 헤더, 응답 본문의 status·reason, 메시지 순으로 가린다.
 */
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

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
            Classified429 c = classify429(retryAfter, body);
            return new GeminiCallException(
                    c.errorCode(),
                    c.message(),
                    statusCode,
                    latencyMs,
                    c.source(),
                    retryAfter,
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

    private Classified429 classify429(String retryAfter, String body) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    "HEADER",
                    null,
                    List.of("retry-after"));
        }

        ParsedUpstream parsed = parseUpstream(body);
        if (anyHint(parsed, GeminiClient::isQuota)) {
            return Classified429.quota("BODY", parsed);
        }
        if (anyHint(parsed, GeminiClient::isRateLimit)) {
            return Classified429.rateLimit("BODY", parsed);
        }

        String message = normalize(parsed.message().isBlank() ? body : parsed.message());
        if (isQuota(message)) {
            return Classified429.quota("MESSAGE", parsed);
        }
        if (isRateLimit(message)) {
            return Classified429.rateLimit("MESSAGE", parsed);
        }
        return new Classified429(
                ErrorCode.LLM_429_UNKNOWN,
                "Gemini 429 classification unknown",
                "UNKNOWN",
                parsed.upstreamStatus(),
                parsed.hints());
    }

    private ParsedUpstream parseUpstream(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedUpstream("", null, List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            String message = firstNonBlank(
                    error.path("message").asText(null), root.path("message").asText(null), body);
            String status = firstNonBlank(
                    error.path("status").asText(null), root.path("status").asText(null));
            return new ParsedUpstream(message == null ? "" : message, status, reasonHints(error));
        } catch (IOException e) {
            return new ParsedUpstream(body, null, List.of());
        }
    }

    private List<String> reasonHints(JsonNode error) {
        if (error.isMissingNode() || error.isNull()) {
            return List.of();
        }
        Set<String> hints = new LinkedHashSet<>();
        collectHints(error.path("details"), hints);
        collectHints(error.path("errors"), hints);
        collectHints(error.path("violations"), hints);
        return List.copyOf(hints);
    }

    private void collectHints(JsonNode node, Set<String> hints) {
        if (node.isArray()) {
            node.forEach(item -> collectHints(item, hints));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String field : List.of("reason", "errorType", "@type")) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                hints.add(value);
            }
        }
        collectHints(node.path("metadata"), hints);
        collectHints(node.path("violations"), hints);
    }

    private static boolean anyHint(ParsedUpstream parsed, Predicate<String> matcher) {
        if (matcher.test(normalize(parsed.upstreamStatus()))) {
            return true;
        }
        for (String hint : parsed.hints()) {
            if (matcher.test(normalize(hint))) {
                return true;
            }
        }
        return matcher.test(normalize(parsed.message()));
    }

    private static boolean isQuota(String normalized) {
        return containsAny(
                normalized,
                "quotaexceeded",
                "dailylimitexceeded",
                "daily limit",
                "daily quota",
                "current quota",
                "quota exhausted",
                "insufficient quota",
                "billing",
                "billable",
                "resource exhausted");
    }

    private static boolean isRateLimit(String normalized) {
        return containsAny(
                normalized,
                "ratelimitexceeded",
                "rate limit",
                "too many requests",
                "retry later",
                "try again later",
                "per minute",
                "retrydelay",
                "retry delay",
                "retry after");
    }

    private static boolean containsAny(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim();
    }

    private static String firstHeaderValue(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return firstNonBlank(entry.getValue().toArray(String[]::new));
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ParsedUpstream(String message, String upstreamStatus, List<String> hints) {}

    private record Classified429(
            ErrorCode errorCode, String message, String source, String upstreamStatus, List<String> hints) {

        static Classified429 quota(String source, ParsedUpstream parsed) {
            return new Classified429(
                    ErrorCode.LLM_QUOTA_EXHAUSTED,
                    "Gemini quota exhausted",
                    source,
                    parsed.upstreamStatus(),
                    parsed.hints());
        }

        static Classified429 rateLimit(String source, ParsedUpstream parsed) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    source,
                    parsed.upstreamStatus(),
                    parsed.hints());
        }
    }
}
