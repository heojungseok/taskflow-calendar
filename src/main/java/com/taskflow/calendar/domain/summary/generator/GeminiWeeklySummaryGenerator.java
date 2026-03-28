package com.taskflow.calendar.domain.summary.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.SummaryBucket;
import com.taskflow.calendar.domain.summary.SummaryTaskSnapshot;
import com.taskflow.calendar.domain.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.config.GeminiSummaryProperties;
import com.taskflow.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiWeeklySummaryGenerator implements WeeklySummaryGenerator {

    private final GeminiSummaryProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SummaryPromptTaskSupport promptTaskSupport = new SummaryPromptTaskSupport();

    @Override
    public WeeklySummarySectionsResult generate(Project project,
                                                List<SummaryTaskSnapshot> syncedTasks,
                                                int syncedTotalTaskCount,
                                                List<SummaryTaskSnapshot> unsyncedTasks,
                                                int unsyncedTotalTaskCount,
                                                LocalDate weekStart,
                                                LocalDate weekEnd) {
        validateConfiguration();

        PreparedRequest preparedRequest = prepareRequest(
                project,
                syncedTasks,
                syncedTotalTaskCount,
                unsyncedTasks,
                unsyncedTotalTaskCount,
                weekStart,
                weekEnd
        );
        String endpoint = properties.getBaseUrl().replaceAll("/$", "")
                + "/models/" + properties.getModel() + ":generateContent";

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("x-goog-api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(preparedRequest.getRequestBody(), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (response.statusCode() >= 400) {
                throw classifyUpstreamFailure(
                        project,
                        weekStart,
                        weekEnd,
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        latencyMs,
                        preparedRequest.getMetrics()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            logUsage(project, weekStart, weekEnd, root, latencyMs, preparedRequest.getMetrics());
            return parseResponse(root, syncedTotalTaskCount, unsyncedTotalTaskCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini summary request interrupted. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, errorCode={}, latencyMs={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, message={}",
                    project.getId(),
                    properties.getModel(),
                    weekStart,
                    weekEnd,
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE.getCode(),
                    System.currentTimeMillis() - startedAt,
                    preparedRequest.getMetrics().getRequestBodyLength(),
                    preparedRequest.getMetrics().getPromptInputFingerprint(),
                    preparedRequest.getMetrics().getSyncedIncludedTaskCount(),
                    preparedRequest.getMetrics().getUnsyncedIncludedTaskCount(),
                    preparedRequest.getMetrics().getSyncedDescBriefChars(),
                    preparedRequest.getMetrics().getUnsyncedDescBriefChars(),
                    abbreviate(e.getMessage()));
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini API request was interrupted",
                    true
            );
        } catch (IOException e) {
            log.warn("Gemini summary request failed before response. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, errorCode={}, latencyMs={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, message={}",
                    project.getId(),
                    properties.getModel(),
                    weekStart,
                    weekEnd,
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE.getCode(),
                    System.currentTimeMillis() - startedAt,
                    preparedRequest.getMetrics().getRequestBodyLength(),
                    preparedRequest.getMetrics().getPromptInputFingerprint(),
                    preparedRequest.getMetrics().getSyncedIncludedTaskCount(),
                    preparedRequest.getMetrics().getUnsyncedIncludedTaskCount(),
                    preparedRequest.getMetrics().getSyncedDescBriefChars(),
                    preparedRequest.getMetrics().getUnsyncedDescBriefChars(),
                    abbreviate(e.getMessage()));
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini API request failed: " + e.getMessage(),
                    true
            );
        }
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_API_KEY_MISSING,
                    "GEMINI_SUMMARY_API_KEY is not configured",
                    false
            );
        }
    }

    private PreparedRequest prepareRequest(Project project,
                                           List<SummaryTaskSnapshot> syncedTasks,
                                           int syncedTotalTaskCount,
                                           List<SummaryTaskSnapshot> unsyncedTasks,
                                           int unsyncedTotalTaskCount,
                                           LocalDate weekStart,
                                           LocalDate weekEnd) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("system_instruction", createContent(systemInstruction()));
            requestBody.put("contents", List.of(createContent(userPrompt(
                    project,
                    syncedTasks,
                    syncedTotalTaskCount,
                    unsyncedTasks,
                    unsyncedTotalTaskCount,
                    weekStart,
                    weekEnd
            ))));

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", properties.getTemperature());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", responseJsonSchema());
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
            requestBody.put("generationConfig", generationConfig);

            String serializedRequestBody = objectMapper.writeValueAsString(requestBody);
            return new PreparedRequest(
                    serializedRequestBody,
                    buildPromptMetrics(serializedRequestBody, syncedTasks, unsyncedTasks)
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Gemini request body", e);
        }
    }

    private Map<String, Object> createContent(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private String systemInstruction() {
        return "당신은 프로젝트 매니저를 돕는 업무 요약 보조자다.\n"
                + "응답은 반드시 한국어 JSON으로만 작성하라.\n"
                + "제공된 Task 데이터만 사용하고 없는 사실은 추측하지 마라.\n"
                + "synced와 unsynced는 각자 주어진 데이터만 기준으로 작성하라.";
    }

    private String userPrompt(Project project,
                              List<SummaryTaskSnapshot> syncedTasks,
                              int syncedTotalTaskCount,
                              List<SummaryTaskSnapshot> unsyncedTasks,
                              int unsyncedTotalTaskCount,
                              LocalDate weekStart,
                              LocalDate weekEnd) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectName", project.getName());
        payload.put("today", LocalDate.now());
        payload.put("weekStart", weekStart);
        payload.put("weekEnd", weekEnd);
        payload.put("instructions", promptInstructions(
                syncedTasks,
                syncedTotalTaskCount,
                unsyncedTasks,
                unsyncedTotalTaskCount
        ));
        payload.put("synced", sectionPayload(SummaryBucket.SYNCED, syncedTasks, syncedTotalTaskCount, weekStart, weekEnd));
        payload.put("unsynced", sectionPayload(SummaryBucket.UNSYNCED, unsyncedTasks, unsyncedTotalTaskCount, weekStart, weekEnd));

        return "입력 JSON을 바탕으로 synced와 unsynced 두 섹션만 반환하라.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private List<String> promptInstructions(List<SummaryTaskSnapshot> syncedTasks,
                                            int syncedTotalTaskCount,
                                            List<SummaryTaskSnapshot> unsyncedTasks,
                                            int unsyncedTotalTaskCount) {
        List<String> instructions = new ArrayList<>();
        instructions.add("이번 주 기준으로만 요약한다.");
        instructions.add("synced는 일정 흐름과 일정상 리스크 중심으로 쓴다.");
        instructions.add("unsynced는 누락 위험과 반영 필요성 중심으로 쓴다.");
        instructions.add("summary는 2~4문장, highlights/risks/nextActions는 각 0~3개만 작성한다.");
        instructions.add("리스크와 차단 상태는 risks에, 바로 할 일은 nextActions에 적는다.");
        instructions.add("summary는 status·syncState·outbox 결과만 사실로 쓰고, 완료·성공적·순조·문제없음·위험없음은 추측하지 않는다.");

        if (hasPartialCoverage(syncedTasks, syncedTotalTaskCount)
                || hasPartialCoverage(unsyncedTasks, unsyncedTotalTaskCount)) {
            instructions.add("includedTaskCount가 totalTaskCount보다 작으면, 제공된 tasks는 상태, 일정, 리스크 신호로 추린 우선순위 대표 업무로 이해하고 그 범위 안에서만 요약한다.");
            instructions.add("partial coverage 섹션에서는 첫 문장부터 '우선순위 대표 업무 기준'처럼 범위를 한정하고 섹션 전체를 단정하지 않는다.");
        } else {
            instructions.add("summary의 첫 문장은 섹션 전체 경향을 먼저 요약하고, 다음 문장에서 대표 task를 연결한다.");
        }

        return instructions;
    }

    private Map<String, Object> sectionPayload(SummaryBucket bucket,
                                               List<SummaryTaskSnapshot> tasks,
                                               int totalTaskCount,
                                               LocalDate weekStart,
                                               LocalDate weekEnd) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("focus", bucket.getPromptFocus());
        section.put("totalTaskCount", totalTaskCount);
        section.put("includedTaskCount", tasks.size());
        section.put("summaryLeadHint", summaryLeadHint(bucket, tasks, totalTaskCount));
        if (hasPartialCoverage(tasks, totalTaskCount)) {
            section.put("coverageHint", "세부 요약은 우선순위 대표 업무만 근거로 삼고, 포함되지 않은 task까지 확장하지 않는다.");
        }
        section.put("tasks", taskPayload(tasks, weekStart, weekEnd));
        return section;
    }

    private String summaryLeadHint(SummaryBucket bucket,
                                   List<SummaryTaskSnapshot> tasks,
                                   int totalTaskCount) {
        boolean partialCoverage = hasPartialCoverage(tasks, totalTaskCount);
        if (bucket == SummaryBucket.UNSYNCED) {
            if (partialCoverage) {
                return "첫 문장부터 우선순위 대표 업무 기준으로 누락 위험과 반영 필요 사항을 요약하고, 이후 문장에서 대표 task를 연결한다.";
            }
            return "첫 문장은 미동기화 업무 전반의 상태를 요약하고, 이후 문장에서 주요 task를 연결한다.";
        }
        if (partialCoverage) {
            return "첫 문장부터 우선순위 대표 업무 기준으로 핵심 일정과 리스크를 요약하고, 이후 문장에서 대표 task를 연결한다.";
        }
        return "첫 문장은 이번 주 일정 전반을 요약하고, 이후 문장에서 주요 일정과 리스크를 연결한다.";
    }

    private boolean hasPartialCoverage(List<SummaryTaskSnapshot> tasks, int totalTaskCount) {
        return totalTaskCount > 0 && tasks.size() < totalTaskCount;
    }

    private List<Map<String, Object>> taskPayload(List<SummaryTaskSnapshot> tasks, LocalDate weekStart, LocalDate weekEnd) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (SummaryTaskSnapshot snapshot : tasks) {
            payload.add(promptTaskSupport.toPromptTaskPayload(snapshot, weekStart, weekEnd));
        }

        return payload;
    }

    private String compressDescription(Task task) {
        return promptTaskSupport.compressDescription(task);
    }

    private List<String> descriptionSignals(String description) {
        return promptTaskSupport.descriptionSignals(description);
    }

    private Map<String, Object> responseJsonSchema() {
        Map<String, Object> sectionSchema = sectionSchema();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("synced", sectionSchema);
        properties.put("unsynced", sectionSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("synced", "unsynced"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> sectionSchema() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "string");

        Map<String, Object> listItem = Map.of("type", "string");

        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("type", "array");
        highlights.put("items", listItem);
        highlights.put("maxItems", 3);

        Map<String, Object> risks = new LinkedHashMap<>();
        risks.put("type", "array");
        risks.put("items", listItem);
        risks.put("maxItems", 3);

        Map<String, Object> nextActions = new LinkedHashMap<>();
        nextActions.put("type", "array");
        nextActions.put("items", listItem);
        nextActions.put("maxItems", 3);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", summary);
        properties.put("highlights", highlights);
        properties.put("risks", risks);
        properties.put("nextActions", nextActions);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("summary", "highlights", "risks", "nextActions"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private WeeklySummarySectionsResult parseResponse(JsonNode root,
                                                     int syncedTotalTaskCount,
                                                     int unsyncedTotalTaskCount) throws IOException {
        JsonNode textNode = root.at("/candidates/0/content/parts/0/text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            JsonNode blockReason = root.at("/promptFeedback/blockReason");
            String reason = blockReason.isMissingNode() ? "unknown" : blockReason.asText();
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain summary text. blockReason=" + reason,
                    false
            );
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(textNode.asText());
        } catch (IOException e) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response payload was not valid JSON",
                    false
            );
        }

        return WeeklySummarySectionsResult.of(
                parseSection(payload.path("synced"), SummaryBucket.SYNCED, syncedTotalTaskCount),
                parseSection(payload.path("unsynced"), SummaryBucket.UNSYNCED, unsyncedTotalTaskCount)
        );
    }

    private WeeklySummaryResult parseSection(JsonNode sectionNode,
                                             SummaryBucket bucket,
                                             int totalTaskCount) {
        if (totalTaskCount == 0) {
            return WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions());
        }
        if (sectionNode.isMissingNode() || sectionNode.isNull()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain " + bucket.name().toLowerCase(Locale.ROOT) + " section",
                    false
            );
        }

        String summary = sectionNode.path("summary").asText("").trim();
        if (summary.isBlank()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini " + bucket.name().toLowerCase(Locale.ROOT) + " summary payload was empty",
                    false
            );
        }

        return WeeklySummaryResult.of(
                summary,
                toStringList(sectionNode.path("highlights")),
                toStringList(sectionNode.path("risks")),
                toStringList(sectionNode.path("nextActions")),
                properties.getModel()
        );
    }

    private List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();

        if (!node.isArray()) {
            return values;
        }

        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }

        return values;
    }

    private WeeklySummaryGenerationException classifyUpstreamFailure(Project project,
                                                                    LocalDate weekStart,
                                                                    LocalDate weekEnd,
                                                                    int statusCode,
                                                                    Map<String, List<String>> responseHeaders,
                                                                    String responseBody,
                                                                    long latencyMs,
                                                                    PromptMetrics metrics) {
        ErrorCode errorCode;
        boolean fallbackEligible;
        String message;
        String classificationSource = null;
        String retryAfter = firstHeaderValueIgnoreCase(responseHeaders, "Retry-After");
        String upstreamStatus = null;
        List<String> reasonHints = List.of();

        if (statusCode == 429) {
            Classified429 classified429 = classify429(responseHeaders, responseBody);
            errorCode = classified429.errorCode();
            fallbackEligible = true;
            message = classified429.message();
            classificationSource = classified429.classificationSource();
            retryAfter = classified429.retryAfter();
            upstreamStatus = classified429.upstreamStatus();
            reasonHints = classified429.reasonHints();
        } else if (statusCode == 400 || statusCode == 404) {
            errorCode = ErrorCode.LLM_CONFIG_INVALID;
            fallbackEligible = false;
            message = "Gemini configuration is invalid";
        } else if (statusCode >= 500) {
            errorCode = ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE;
            fallbackEligible = true;
            message = "Gemini temporary failure";
        } else {
            errorCode = ErrorCode.LLM_CONFIG_INVALID;
            fallbackEligible = false;
            message = "Gemini request failed";
        }

        log.warn("Gemini summary request failed. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, statusCode={}, errorCode={}, latencyMs={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, classificationSource={}, retryAfter={}, upstreamStatus={}, upstreamReasonHints={}, body={}",
                project.getId(),
                properties.getModel(),
                weekStart,
                weekEnd,
                statusCode,
                errorCode.getCode(),
                latencyMs,
                metrics.getRequestBodyLength(),
                metrics.getPromptInputFingerprint(),
                metrics.getSyncedIncludedTaskCount(),
                metrics.getUnsyncedIncludedTaskCount(),
                metrics.getSyncedDescBriefChars(),
                metrics.getUnsyncedDescBriefChars(),
                classificationSource != null ? classificationSource : "UNKNOWN",
                retryAfter,
                upstreamStatus,
                reasonHints,
                abbreviate(responseBody));

        return new WeeklySummaryGenerationException(
                errorCode,
                message + ": " + abbreviate(responseBody),
                fallbackEligible,
                classificationSource,
                retryAfter,
                upstreamStatus,
                reasonHints
        );
    }

    private Classified429 classify429(Map<String, List<String>> responseHeaders, String responseBody) {
        String retryAfter = firstHeaderValueIgnoreCase(responseHeaders, "Retry-After");
        if (retryAfter != null && !retryAfter.isBlank()) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    "HEADER",
                    retryAfter,
                    null,
                    List.of("retry-after")
            );
        }

        ParsedUpstream429 parsed = parseUpstream429(responseBody);
        if (containsQuotaHint(parsed.reasonHints(), parsed.message(), parsed.upstreamStatus())) {
            return new Classified429(
                    ErrorCode.LLM_QUOTA_EXHAUSTED,
                    "Gemini quota exhausted",
                    "BODY",
                    retryAfter,
                    parsed.upstreamStatus(),
                    parsed.reasonHints()
            );
        }
        if (containsRateLimitHint(parsed.reasonHints(), parsed.message(), parsed.upstreamStatus())) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    "BODY",
                    retryAfter,
                    parsed.upstreamStatus(),
                    parsed.reasonHints()
            );
        }

        String normalizedMessage = normalizeHintText(parsed.message().isBlank() ? responseBody : parsed.message());
        if (containsQuotaKeyword(normalizedMessage)) {
            return new Classified429(
                    ErrorCode.LLM_QUOTA_EXHAUSTED,
                    "Gemini quota exhausted",
                    "MESSAGE",
                    retryAfter,
                    parsed.upstreamStatus(),
                    parsed.reasonHints()
            );
        }
        if (containsRateLimitKeyword(normalizedMessage)) {
            return new Classified429(
                    ErrorCode.LLM_RATE_LIMITED_TEMPORARY,
                    "Gemini rate limit is temporary",
                    "MESSAGE",
                    retryAfter,
                    parsed.upstreamStatus(),
                    parsed.reasonHints()
            );
        }

        return new Classified429(
                ErrorCode.LLM_429_UNKNOWN,
                "Gemini 429 classification unknown",
                "UNKNOWN",
                retryAfter,
                parsed.upstreamStatus(),
                parsed.reasonHints()
        );
    }

    private ParsedUpstream429 parseUpstream429(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new ParsedUpstream429("", null, List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            String message = firstNonBlank(
                    errorNode.path("message").asText(null),
                    root.path("message").asText(null),
                    responseBody
            );
            String upstreamStatus = firstNonBlank(
                    errorNode.path("status").asText(null),
                    root.path("status").asText(null)
            );
            List<String> reasonHints = extractReasonHints(errorNode);
            return new ParsedUpstream429(message == null ? "" : message, upstreamStatus, reasonHints);
        } catch (IOException e) {
            return new ParsedUpstream429(responseBody, null, List.of());
        }
    }

    private List<String> extractReasonHints(JsonNode errorNode) {
        if (errorNode == null || errorNode.isMissingNode() || errorNode.isNull()) {
            return List.of();
        }

        Set<String> hints = new LinkedHashSet<>();
        collectReasonHints(errorNode.path("details"), hints);
        collectReasonHints(errorNode.path("errors"), hints);
        collectReasonHints(errorNode.path("violations"), hints);
        return List.copyOf(hints);
    }

    private void collectReasonHints(JsonNode node, Set<String> hints) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectReasonHints(item, hints);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        addHintValue(hints, node.path("reason").asText(null));
        addHintValue(hints, node.path("errorType").asText(null));
        addHintValue(hints, node.path("@type").asText(null));
        collectReasonHints(node.path("metadata"), hints);
        collectReasonHints(node.path("violations"), hints);
    }

    private void addHintValue(Set<String> hints, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        hints.add(value);
    }

    private boolean containsQuotaHint(List<String> reasonHints, String message, String upstreamStatus) {
        if (containsQuotaKeyword(normalizeHintText(upstreamStatus))) {
            return true;
        }
        for (String hint : reasonHints) {
            if (containsQuotaKeyword(normalizeHintText(hint))) {
                return true;
            }
        }
        return containsQuotaKeyword(normalizeHintText(message));
    }

    private boolean containsRateLimitHint(List<String> reasonHints, String message, String upstreamStatus) {
        if (containsRateLimitKeyword(normalizeHintText(upstreamStatus))) {
            return true;
        }
        for (String hint : reasonHints) {
            if (containsRateLimitKeyword(normalizeHintText(hint))) {
                return true;
            }
        }
        return containsRateLimitKeyword(normalizeHintText(message));
    }

    private boolean containsQuotaKeyword(String normalized) {
        return containsAnyKeyword(normalized,
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

    private boolean containsRateLimitKeyword(String normalized) {
        return containsAnyKeyword(normalized,
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

    private boolean containsAnyKeyword(String normalized, String... keywords) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHintText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
    }

    private String firstHeaderValueIgnoreCase(Map<String, List<String>> responseHeaders, String headerName) {
        if (responseHeaders == null || responseHeaders.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
                return firstNonBlank(entry.getValue());
            }
        }
        return null;
    }

    private String firstNonBlank(Collection<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void logUsage(Project project,
                          LocalDate weekStart,
                          LocalDate weekEnd,
                          JsonNode root,
                          long latencyMs,
                          PromptMetrics metrics) {
        JsonNode usage = root.path("usageMetadata");
        int promptTokens = usage.path("promptTokenCount").asInt(0);
        int candidateTokens = usage.path("candidatesTokenCount").asInt(0);
        int totalTokens = usage.path("totalTokenCount").asInt(0);

        log.info("Gemini summary request succeeded. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, latencyMs={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, promptTokens={}, candidateTokens={}, totalTokens={}",
                project.getId(),
                properties.getModel(),
                weekStart,
                weekEnd,
                latencyMs,
                metrics.getRequestBodyLength(),
                metrics.getPromptInputFingerprint(),
                metrics.getSyncedIncludedTaskCount(),
                metrics.getUnsyncedIncludedTaskCount(),
                metrics.getSyncedDescBriefChars(),
                metrics.getUnsyncedDescBriefChars(),
                promptTokens,
                candidateTokens,
                totalTokens);
    }

    private PromptMetrics buildPromptMetrics(String requestBody,
                                             List<SummaryTaskSnapshot> syncedTasks,
                                             List<SummaryTaskSnapshot> unsyncedTasks) {
        return new PromptMetrics(
                requestBody.getBytes(StandardCharsets.UTF_8).length,
                sha256(requestBody),
                syncedTasks.size(),
                unsyncedTasks.size(),
                totalDescBriefChars(syncedTasks),
                totalDescBriefChars(unsyncedTasks)
        );
    }

    private int totalDescBriefChars(List<SummaryTaskSnapshot> tasks) {
        int total = 0;
        for (SummaryTaskSnapshot snapshot : tasks) {
            total += promptTaskSupport.descBriefChars(snapshot.getTask());
        }
        return total;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 300) {
            return value;
        }
        return value.substring(0, 300) + "...";
    }

    static final class PromptMetrics {
        private final int requestBodyLength;
        private final String promptInputFingerprint;
        private final int syncedIncludedTaskCount;
        private final int unsyncedIncludedTaskCount;
        private final int syncedDescBriefChars;
        private final int unsyncedDescBriefChars;

        private PromptMetrics(int requestBodyLength,
                              String promptInputFingerprint,
                              int syncedIncludedTaskCount,
                              int unsyncedIncludedTaskCount,
                              int syncedDescBriefChars,
                              int unsyncedDescBriefChars) {
            this.requestBodyLength = requestBodyLength;
            this.promptInputFingerprint = promptInputFingerprint;
            this.syncedIncludedTaskCount = syncedIncludedTaskCount;
            this.unsyncedIncludedTaskCount = unsyncedIncludedTaskCount;
            this.syncedDescBriefChars = syncedDescBriefChars;
            this.unsyncedDescBriefChars = unsyncedDescBriefChars;
        }

        int getRequestBodyLength() {
            return requestBodyLength;
        }

        String getPromptInputFingerprint() {
            return promptInputFingerprint;
        }

        int getSyncedIncludedTaskCount() {
            return syncedIncludedTaskCount;
        }

        int getUnsyncedIncludedTaskCount() {
            return unsyncedIncludedTaskCount;
        }

        int getSyncedDescBriefChars() {
            return syncedDescBriefChars;
        }

        int getUnsyncedDescBriefChars() {
            return unsyncedDescBriefChars;
        }
    }

    private static final class PreparedRequest {
        private final String requestBody;
        private final PromptMetrics metrics;

        private PreparedRequest(String requestBody, PromptMetrics metrics) {
            this.requestBody = requestBody;
            this.metrics = metrics;
        }

        private String getRequestBody() {
            return requestBody;
        }

        private PromptMetrics getMetrics() {
            return metrics;
        }
    }

    private static final class ParsedUpstream429 {
        private final String message;
        private final String upstreamStatus;
        private final List<String> reasonHints;

        private ParsedUpstream429(String message, String upstreamStatus, List<String> reasonHints) {
            this.message = message;
            this.upstreamStatus = upstreamStatus;
            this.reasonHints = reasonHints;
        }

        private String message() {
            return message;
        }

        private String upstreamStatus() {
            return upstreamStatus;
        }

        private List<String> reasonHints() {
            return reasonHints;
        }
    }

    private static final class Classified429 {
        private final ErrorCode errorCode;
        private final String message;
        private final String classificationSource;
        private final String retryAfter;
        private final String upstreamStatus;
        private final List<String> reasonHints;

        private Classified429(ErrorCode errorCode,
                              String message,
                              String classificationSource,
                              String retryAfter,
                              String upstreamStatus,
                              List<String> reasonHints) {
            this.errorCode = errorCode;
            this.message = message;
            this.classificationSource = classificationSource;
            this.retryAfter = retryAfter;
            this.upstreamStatus = upstreamStatus;
            this.reasonHints = reasonHints;
        }

        private ErrorCode errorCode() {
            return errorCode;
        }

        private String message() {
            return message;
        }

        private String classificationSource() {
            return classificationSource;
        }

        private String retryAfter() {
            return retryAfter;
        }

        private String upstreamStatus() {
            return upstreamStatus;
        }

        private List<String> reasonHints() {
            return reasonHints;
        }
    }
}
