package com.taskflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gemini 429를 한도 소진(QUOTA)과 일시적 제한(RATE)으로 가른다.
 *
 * <p>Retry-After 헤더 → QuotaFailure의 quotaId(PerDay/PerMinute) → 본문 status·reason → 메시지 순으로 본다.
 */
final class Gemini429Classifier {

    private final ObjectMapper objectMapper;

    Gemini429Classifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Classified429 classify(String retryAfter, String body) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    "HEADER",
                    null,
                    List.of("retry-after"),
                    null);
        }

        ParsedUpstream parsed = parseUpstream(body);
        // 분당 제한과 일일 한도는 status·메시지가 같다(RESOURCE_EXHAUSTED, "current quota"). quotaId만 다르다.
        if (parsed.quotaIds().stream().anyMatch(id -> id.contains("PerDay"))) {
            return Classified429.quota("QUOTA_ID", parsed);
        }
        if (parsed.quotaIds().stream().anyMatch(id -> id.contains("PerMinute") || id.contains("PerSecond"))) {
            return Classified429.rateLimit("QUOTA_ID", parsed);
        }
        if (anyHint(parsed, Gemini429Classifier::isQuota)) {
            return Classified429.quota("BODY", parsed);
        }
        if (anyHint(parsed, Gemini429Classifier::isRateLimit)) {
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
                parsed.hints(),
                parsed.retryDelay());
    }

    private ParsedUpstream parseUpstream(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedUpstream("", null, List.of(), List.of(), null);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            String message = GeminiClient.firstNonBlank(
                    error.path("message").asText(null), root.path("message").asText(null), body);
            String status = GeminiClient.firstNonBlank(
                    error.path("status").asText(null), root.path("status").asText(null));
            JsonNode retryDelay = error.findValue("retryDelay");
            return new ParsedUpstream(
                    message == null ? "" : message,
                    status,
                    reasonHints(error),
                    error.findValuesAsText("quotaId"),
                    retryDelay == null ? null : retryDelay.asText(null));
        } catch (IOException e) {
            return new ParsedUpstream(body, null, List.of(), List.of(), null);
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
        for (String field : List.of("reason", "errorType", "@type", "quotaId")) {
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

    private record ParsedUpstream(
            String message, String upstreamStatus, List<String> hints, List<String> quotaIds, String retryDelay) {}

    record Classified429(
            ErrorCode errorCode,
            String message,
            String source,
            String upstreamStatus,
            List<String> hints,
            String retryDelay) {

        static Classified429 quota(String source, ParsedUpstream parsed) {
            return new Classified429(
                    ErrorCode.LLM_QUOTA_EXHAUSTED,
                    "Gemini quota exhausted",
                    source,
                    parsed.upstreamStatus(),
                    parsed.hints(),
                    parsed.retryDelay());
        }

        static Classified429 rateLimit(String source, ParsedUpstream parsed) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    source,
                    parsed.upstreamStatus(),
                    parsed.hints(),
                    parsed.retryDelay());
        }
    }
}
