package com.taskflow.calendar.domain.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiProperties;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiWeeklySummaryGenerator implements WeeklySummaryGenerator {

    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;
    private static final int RECENT_UPDATE_HOURS = 24;
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private static final List<String> URGENCY_KEYWORDS = List.of(
            "긴급", "urgent", "asap", "즉시", "오늘", "오늘 안", "today", "critical", "반드시"
    );
    private static final List<String> RISK_KEYWORDS = List.of(
            "리스크", "risk", "실패", "failure", "누락", "지연", "문제", "오류", "retry", "차단", "blocked"
    );
    private static final List<String> DEPENDENCY_KEYWORDS = List.of(
            "의존", "dependency", "승인", "검토", "외부", "협업", "대기"
    );
    private static final List<String> DELIVERABLE_KEYWORDS = List.of(
            "발표", "문서", "체크리스트", "보고", "정리", "캡처", "데모", "산출물", "공유"
    );
    private static final List<String> CONFIG_KEYWORDS = List.of(
            "oauth", "redirect uri", "api key", "gemini", "환경 변수", "env", "config", "설정"
    );
    private static final List<String> DEMO_KEYWORDS = List.of(
            "sprint review", "review", "데모", "화면 캡처", "캡처"
    );
    private static final List<String> DOC_KEYWORDS = List.of(
            "문서", "체크리스트", "가이드", "정리", "공유", "보고"
    );
    private static final List<String> PRESERVED_KEYWORDS = List.of(
            "Google OAuth", "Gemini API key", "OAuth", "API key", "배포", "Sprint Review", "체크리스트", "캡처", "데모"
    );

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

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
                throw classifyUpstreamFailure(project, weekStart, weekEnd, response.statusCode(), response.body(), latencyMs, preparedRequest.getMetrics());
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
                    "GEMINI_API_KEY is not configured",
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
        payload.put("instructions", List.of(
                "이번 주 관점으로만 요약한다.",
                "synced는 캘린더에 반영된 일정 흐름과 일정상 위험에 집중한다.",
                "unsynced는 캘린더 미반영 업무의 누락 위험과 반영 필요성에 집중한다.",
                "summary는 2~4문장, highlights/risks/nextActions는 각 0~3개만 작성한다.",
                "리스크와 차단 상태는 risks에 분명히 반영한다.",
                "nextActions는 바로 실행 가능한 행동만 적는다.",
                "Task 원문 description 대신 descBrief와 descSignals를 우선 근거로 사용한다."
        ));
        payload.put("synced", sectionPayload(SummaryBucket.SYNCED, syncedTasks, syncedTotalTaskCount, weekStart, weekEnd));
        payload.put("unsynced", sectionPayload(SummaryBucket.UNSYNCED, unsyncedTasks, unsyncedTotalTaskCount, weekStart, weekEnd));

        return "아래 JSON 입력을 기반으로 synced와 unsynced 두 섹션을 모두 반환하라.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> sectionPayload(SummaryBucket bucket,
                                               List<SummaryTaskSnapshot> tasks,
                                               int totalTaskCount,
                                               LocalDate weekStart,
                                               LocalDate weekEnd) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("label", bucket.getDisplayName());
        section.put("focus", bucket.getPromptFocus());
        section.put("totalTaskCount", totalTaskCount);
        section.put("includedTaskCount", tasks.size());
        section.put("tasks", taskPayload(tasks, weekStart, weekEnd));
        return section;
    }

    private List<Map<String, Object>> taskPayload(List<SummaryTaskSnapshot> tasks, LocalDate weekStart, LocalDate weekEnd) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (SummaryTaskSnapshot snapshot : tasks) {
            Task task = snapshot.getTask();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("title", task.getTitle());
            item.put("status", task.getStatus().name());
            item.put("startAt", task.getStartAt());
            item.put("dueAt", task.getDueAt());
            item.put("updatedAt", task.getUpdatedAt());
            item.put("recentlyUpdated", isRecentlyUpdated(task));
            item.put("calendarSyncEnabled", task.getCalendarSyncEnabled());
            item.put("syncState", snapshot.getSyncState().name());
            item.put("lastOutboxStatus", enumName(snapshot.getLatestOutboxStatus()));
            item.put("lastOutboxError", snapshot.getLatestOutboxError());
            item.put("isOverdue", isOverdue(task));
            item.put("isDeadlineThisWeek", isDeadlineThisWeek(task, weekStart, weekEnd));
            item.put("isEventOverlappingThisWeek", isEventOverlappingThisWeek(task, weekStart, weekEnd));
            item.put("descBrief", compressDescription(task));
            item.put("descSignals", descriptionSignals(task.getDescription()));
            payload.add(item);
        }

        return payload;
    }

    private String compressDescription(Task task) {
        String description = task.getDescription();
        if (description == null || description.isBlank()) {
            return null;
        }

        int maxChars = maxDescriptionLength(task.getStatus());
        int maxSentences = allowSecondSentence(task, description) ? 2 : 1;
        List<SentenceCandidate> candidates = sentenceCandidates(description);

        if (candidates.isEmpty()) {
            return truncate(description, maxChars);
        }

        List<SentenceCandidate> selected = candidates.stream()
                .sorted(Comparator.comparingInt(SentenceCandidate::getScore).reversed()
                        .thenComparingInt(SentenceCandidate::getIndex))
                .limit(maxSentences)
                .sorted(Comparator.comparingInt(SentenceCandidate::getIndex))
                .collect(java.util.stream.Collectors.toList());

        String combined = selected.stream()
                .map(SentenceCandidate::getSentence)
                .collect(java.util.stream.Collectors.joining(" "));
        String normalized = normalizeWhitespace(combined);
        if (normalized.isBlank()) {
            normalized = normalizeWhitespace(description);
        }
        return truncate(normalized, maxChars);
    }

    private int maxDescriptionLength(TaskStatus status) {
        if (status == TaskStatus.BLOCKED || status == TaskStatus.IN_PROGRESS) {
            return 140;
        }
        if (status == TaskStatus.DONE) {
            return 80;
        }
        return 120;
    }

    private boolean allowSecondSentence(Task task, String description) {
        if (task.getStatus() != TaskStatus.BLOCKED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            return false;
        }
        return descriptionSignals(description).size() >= 2;
    }

    private List<SentenceCandidate> sentenceCandidates(String description) {
        String normalized = normalizeWhitespace(description);
        if (normalized.isBlank()) {
            return List.of();
        }

        String[] rawSentences = SENTENCE_SPLIT_PATTERN.split(normalized);
        List<SentenceCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (String rawSentence : rawSentences) {
            String sentence = normalizeWhitespace(rawSentence);
            if (sentence.isBlank()) {
                continue;
            }
            candidates.add(new SentenceCandidate(index++, sentence, scoreSentence(sentence)));
        }

        if (candidates.isEmpty()) {
            candidates.add(new SentenceCandidate(0, normalized, scoreSentence(normalized)));
        }
        return candidates;
    }

    private int scoreSentence(String sentence) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        int score = 0;

        score += containsAny(normalized, RISK_KEYWORDS) ? 50 : 0;
        score += containsAny(normalized, URGENCY_KEYWORDS) ? 40 : 0;
        score += containsAny(normalized, DELIVERABLE_KEYWORDS) ? 30 : 0;
        score += containsAny(normalized, DEPENDENCY_KEYWORDS) ? 25 : 0;
        score += countHits(normalized, CONFIG_KEYWORDS) * 30;
        score += containsAny(normalized, DEMO_KEYWORDS) ? 18 : 0;
        score += containsAny(normalized, DOC_KEYWORDS) ? 15 : 0;
        score += countHits(normalized, PRESERVED_KEYWORDS) * 25;
        score += containsScheduleSignal(normalized) ? 15 : 0;

        return score;
    }

    private boolean containsScheduleSignal(String normalized) {
        return normalized.contains("월") || normalized.contains("화") || normalized.contains("수")
                || normalized.contains("목") || normalized.contains("금") || normalized.contains("토")
                || normalized.contains("일") || normalized.contains("마감") || normalized.contains("오전")
                || normalized.contains("오후") || normalized.contains("배포");
    }

    private boolean containsAny(String normalized, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private int countHits(String normalized, List<String> keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        int end = maxChars;
        while (end > 0 && !Character.isWhitespace(normalized.charAt(end - 1))) {
            end--;
        }
        if (end < maxChars / 2) {
            end = maxChars;
        }

        return normalized.substring(0, end).trim();
    }

    private List<String> descriptionSignals(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }

        String normalized = description.toLowerCase(Locale.ROOT);
        Set<String> signals = new LinkedHashSet<>();

        if (containsAny(normalized, URGENCY_KEYWORDS)) {
            signals.add("urgent");
        }
        if (containsAny(normalized, RISK_KEYWORDS)) {
            signals.add("risk");
        }
        if (containsAny(normalized, DEPENDENCY_KEYWORDS)) {
            signals.add("dependency");
        }
        if (containsAny(normalized, DELIVERABLE_KEYWORDS)) {
            signals.add("deliverable");
        }
        if (containsAny(normalized, CONFIG_KEYWORDS)) {
            signals.add("config");
        }
        if (containsAny(normalized, DEMO_KEYWORDS)) {
            signals.add("demo");
        }
        if (containsAny(normalized, DOC_KEYWORDS)) {
            signals.add("doc");
        }

        return List.copyOf(signals);
    }

    private String enumName(OutboxStatus status) {
        return status != null ? status.name() : null;
    }

    private boolean isRecentlyUpdated(Task task) {
        if (task.getUpdatedAt() == null) {
            return false;
        }
        return task.getUpdatedAt().isAfter(LocalDateTime.now().minusHours(RECENT_UPDATE_HOURS));
    }

    private boolean isOverdue(Task task) {
        return task.getDueAt() != null
                && task.getDueAt().toLocalDate().isBefore(LocalDate.now())
                && task.getStatus() != TaskStatus.DONE;
    }

    private boolean isDeadlineThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        LocalDate dueDate = task.getDueAt().toLocalDate();
        return !dueDate.isBefore(weekStart) && !dueDate.isAfter(weekEnd);
    }

    private boolean isEventOverlappingThisWeek(Task task, LocalDate weekStart, LocalDate weekEnd) {
        if (task.getDueAt() == null) {
            return false;
        }

        if (task.getStartAt() != null && task.getStartAt().isAfter(task.getDueAt())) {
            return false;
        }

        LocalDate eventStartDate = task.getStartAt() != null
                ? task.getStartAt().toLocalDate()
                : task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS).toLocalDate();
        LocalDate eventEndDate = task.getDueAt().toLocalDate();
        return !eventStartDate.isAfter(weekEnd) && !eventEndDate.isBefore(weekStart);
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
                                                                    String responseBody,
                                                                    long latencyMs,
                                                                    PromptMetrics metrics) {
        ErrorCode errorCode;
        boolean fallbackEligible;
        String message;

        if (statusCode == 429) {
            errorCode = ErrorCode.LLM_QUOTA_EXCEEDED;
            fallbackEligible = true;
            message = "Gemini quota exceeded";
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

        log.warn("Gemini summary request failed. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, statusCode={}, errorCode={}, latencyMs={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, body={}",
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
                abbreviate(responseBody));

        return new WeeklySummaryGenerationException(
                errorCode,
                message + ": " + abbreviate(responseBody),
                fallbackEligible
        );
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
            String descBrief = compressDescription(snapshot.getTask());
            if (descBrief != null) {
                total += descBrief.length();
            }
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

    private static final class SentenceCandidate {
        private final int index;
        private final String sentence;
        private final int score;

        private SentenceCandidate(int index, String sentence, int score) {
            this.index = index;
            this.sentence = sentence;
            this.score = score;
        }

        private int getIndex() {
            return index;
        }

        private String getSentence() {
            return sentence;
        }

        private int getScore() {
            return score;
        }
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
}
