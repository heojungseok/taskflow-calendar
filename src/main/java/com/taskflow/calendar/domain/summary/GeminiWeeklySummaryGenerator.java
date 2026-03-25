package com.taskflow.calendar.domain.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.calendar.domain.outbox.OutboxOpType;
import com.taskflow.calendar.domain.outbox.OutboxStatus;
import com.taskflow.calendar.domain.project.Project;
import com.taskflow.calendar.domain.summary.dto.WeeklySummaryResult;
import com.taskflow.calendar.domain.task.Task;
import com.taskflow.calendar.domain.task.TaskStatus;
import com.taskflow.config.GeminiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiWeeklySummaryGenerator implements WeeklySummaryGenerator {
    private static final int DEFAULT_EVENT_DURATION_HOURS = 1;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final List<String> URGENCY_KEYWORDS = List.of(
            "긴급", "urgent", "asap", "즉시", "오늘", "오늘 안", "today", "critical"
    );
    private static final List<String> RISK_KEYWORDS = List.of(
            "리스크", "risk", "실패", "failure", "누락", "지연", "문제", "오류", "retry"
    );
    private static final List<String> DEPENDENCY_KEYWORDS = List.of(
            "의존", "dependency", "승인", "검토", "외부", "협업", "대기", "blocked", "차단"
    );
    private static final List<String> DELIVERABLE_KEYWORDS = List.of(
            "발표", "문서", "체크리스트", "보고", "정리", "캡처", "데모", "산출물", "공유"
    );

    @Override
    public WeeklySummaryResult generate(Project project,
                                        List<SummaryTaskSnapshot> tasks,
                                        LocalDate weekStart,
                                        LocalDate weekEnd,
                                        int totalTaskCount,
                                        SummaryBucket bucket) {
        validateConfiguration();

        String requestBody = buildRequestBody(project, tasks, weekStart, weekEnd, totalTaskCount, bucket);
        String endpoint = properties.getBaseUrl().replaceAll("/$", "")
                + "/models/" + properties.getModel() + ":generateContent";

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("x-goog-api-key", properties.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Gemini API request failed: "
                        + response.statusCode() + " " + abbreviate(response.body()));
            }

            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini API request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Gemini API request failed", e);
        }
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }
    }

    private String buildRequestBody(Project project,
                                    List<SummaryTaskSnapshot> tasks,
                                    LocalDate weekStart,
                                    LocalDate weekEnd,
                                    int totalTaskCount,
                                    SummaryBucket bucket) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("system_instruction", createContent(systemInstruction()));
            requestBody.put("contents", List.of(createContent(userPrompt(project, tasks, weekStart, weekEnd, totalTaskCount, bucket))));

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", properties.getTemperature());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", responseJsonSchema());
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", 0));
            requestBody.put("generationConfig", generationConfig);

            return objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Gemini request body", e);
        }
    }

    private Map<String, Object> createContent(String text) {
        return Map.of("parts", List.of(Map.of("text", text)));
    }

    private String systemInstruction() {
        return "당신은 프로젝트 매니저를 돕는 업무 요약 보조자다.\n"
                + "반드시 제공된 Task 데이터만 사용하고, 없는 사실을 추측하지 마라.\n"
                + "응답은 한국어로 작성하라.\n"
                + "핵심 요약은 이번 주 관점에서 작성하되, 지연되었거나 차단된 업무는 위험으로 반영하라.";
    }

    private String userPrompt(Project project,
                              List<SummaryTaskSnapshot> tasks,
                              LocalDate weekStart,
                              LocalDate weekEnd,
                              int totalTaskCount,
                              SummaryBucket bucket) {
        try {
            String taskPayload = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(taskPayload(tasks, weekStart, weekEnd));

            Map<TaskStatus, Long> statusCounts = statusCounts(tasks);

            return "프로젝트명: " + project.getName() + "\n"
                    + "요약 그룹: " + bucket.getDisplayName() + "\n"
                    + "그룹 설명: " + bucket.getPromptDescription() + "\n"
                    + "초점: " + bucket.getPromptFocus() + "\n"
                    + "오늘 날짜: " + LocalDate.now() + "\n"
                    + "이번 주 범위: " + weekStart + " ~ " + weekEnd + "\n"
                    + "이 그룹 전체 Task 수: " + totalTaskCount + "\n"
                    + "LLM 입력 Task 수: " + tasks.size() + "\n"
                    + "상태 집계: REQUESTED=" + statusCounts.get(TaskStatus.REQUESTED)
                    + ", IN_PROGRESS=" + statusCounts.get(TaskStatus.IN_PROGRESS)
                    + ", BLOCKED=" + statusCounts.get(TaskStatus.BLOCKED)
                    + ", DONE=" + statusCounts.get(TaskStatus.DONE) + "\n\n"
                    + "요청 사항:\n"
                    + "1. summary는 2~4문장으로 이번 주 핵심 흐름을 정리한다.\n"
                    + "2. highlights는 중요한 업무 0~3개를 짧게 정리한다.\n"
                    + "3. risks는 지연, 차단, 일정 리스크 0~3개를 정리한다.\n"
                    + "4. nextActions는 이번 주 바로 실행할 행동 0~3개를 적는다.\n"
                    + "5. 제목보다 description에 담긴 목적, 제약, 산출물, 위험 신호를 우선 반영한다.\n"
                    + "6. descriptionSignals(urgency/risk/dependency/deliverable)을 판단 근거로 반드시 활용한다.\n"
                    + "7. eventStartAt/eventEndAt로 이번 주 교집합을 우선 판단하고 deadlineAt은 마감 위험 판단에 활용한다.\n"
                    + "8. 모든 문장은 반드시 이 그룹의 Task만 기준으로 작성한다.\n"
                    + "9. " + bucket.getPromptFocus() + "\n"
                    + "10. 제공된 Task 외 정보는 쓰지 않는다.\n"
                    + "11. 이번 주에 뚜렷한 우선 업무가 없으면 그 사실을 명확히 적는다.\n\n"
                    + "Task 데이터(JSON):\n"
                    + taskPayload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build Gemini prompt", e);
        }
    }

    private List<Map<String, Object>> taskPayload(List<SummaryTaskSnapshot> tasks, LocalDate weekStart, LocalDate weekEnd) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (SummaryTaskSnapshot snapshot : tasks) {
            Task task = snapshot.getTask();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("title", task.getTitle());
            item.put("description", task.getDescription());
            item.put("status", task.getStatus().name());
            item.put("assigneeName", task.getAssignee() != null ? task.getAssignee().getName() : null);
            item.put("startAt", task.getStartAt());
            item.put("dueAt", task.getDueAt());
            item.put("deadlineAt", task.getDueAt());
            item.put("calendarSyncEnabled", task.getCalendarSyncEnabled());
            item.put("calendarEventId", task.getCalendarEventId());
            item.put("eventStartAt", effectiveEventStartAt(task));
            item.put("eventEndAt", effectiveEventEndAt(task));
            item.put("isCalendarSynced", isCalendarSynced(task));
            item.put("syncState", snapshot.getSyncState().name());
            item.put("syncStateDescription", snapshot.getSyncState().getDescription());
            item.put("lastOutboxStatus", enumName(snapshot.getLatestOutboxStatus()));
            item.put("lastOutboxOpType", enumName(snapshot.getLatestOutboxOpType()));
            item.put("lastOutboxError", snapshot.getLatestOutboxError());
            item.put("descriptionSignals", descriptionSignals(task.getDescription()));
            item.put("isOverdue", isOverdue(task));
            item.put("isDeadlineThisWeek", isDeadlineThisWeek(task, weekStart, weekEnd));
            item.put("isEventOverlappingThisWeek", isEventOverlappingThisWeek(task, weekStart, weekEnd));
            payload.add(item);
        }

        return payload;
    }

    private Object effectiveEventStartAt(Task task) {
        if (task.getStartAt() != null) {
            return task.getStartAt();
        }
        if (task.getDueAt() != null) {
            return task.getDueAt().minusHours(DEFAULT_EVENT_DURATION_HOURS);
        }
        return null;
    }

    private Object effectiveEventEndAt(Task task) {
        return task.getDueAt();
    }

    private boolean isCalendarSynced(Task task) {
        return Boolean.TRUE.equals(task.getCalendarSyncEnabled())
                && task.getCalendarEventId() != null
                && !task.getCalendarEventId().isBlank();
    }

    private String enumName(OutboxStatus status) {
        return status != null ? status.name() : null;
    }

    private String enumName(OutboxOpType opType) {
        return opType != null ? opType.name() : null;
    }

    private Map<String, Object> descriptionSignals(String description) {
        if (description == null || description.isBlank()) {
            return Map.of(
                    "hasDescription", false,
                    "urgencyKeywords", List.of(),
                    "riskKeywords", List.of(),
                    "dependencyKeywords", List.of(),
                    "deliverableKeywords", List.of(),
                    "descriptionLength", 0
            );
        }

        String normalized = description.toLowerCase(Locale.ROOT);
        List<String> urgencyHits = findKeywordHits(normalized, URGENCY_KEYWORDS);
        List<String> riskHits = findKeywordHits(normalized, RISK_KEYWORDS);
        List<String> dependencyHits = findKeywordHits(normalized, DEPENDENCY_KEYWORDS);
        List<String> deliverableHits = findKeywordHits(normalized, DELIVERABLE_KEYWORDS);

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("hasDescription", true);
        signals.put("urgencyKeywords", urgencyHits);
        signals.put("riskKeywords", riskHits);
        signals.put("dependencyKeywords", dependencyHits);
        signals.put("deliverableKeywords", deliverableHits);
        signals.put("descriptionLength", description.length());
        return signals;
    }

    private List<String> findKeywordHits(String normalizedDescription, List<String> keywords) {
        List<String> hits = new ArrayList<>();
        for (String keyword : keywords) {
            if (normalizedDescription.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits.add(keyword);
            }
        }
        return hits;
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

    private Map<TaskStatus, Long> statusCounts(List<SummaryTaskSnapshot> tasks) {
        Map<TaskStatus, Long> counts = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, tasks.stream().filter(snapshot -> snapshot.getTask().getStatus() == status).count());
        }
        return counts;
    }

    private Map<String, Object> responseJsonSchema() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", "string");
        summary.put("description", "이번 주 핵심 업무 흐름을 2~4문장으로 설명한다.");

        Map<String, Object> listItem = Map.of("type", "string");

        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("type", "array");
        highlights.put("description", "이번 주 중요한 업무 0~3개");
        highlights.put("items", listItem);
        highlights.put("maxItems", 3);

        Map<String, Object> risks = new LinkedHashMap<>();
        risks.put("type", "array");
        risks.put("description", "지연, 차단, 일정 위험 요소 0~3개");
        risks.put("items", listItem);
        risks.put("maxItems", 3);

        Map<String, Object> nextActions = new LinkedHashMap<>();
        nextActions.put("type", "array");
        nextActions.put("description", "이번 주 바로 실행할 행동 0~3개");
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

    private WeeklySummaryResult parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode textNode = root.at("/candidates/0/content/parts/0/text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            JsonNode blockReason = root.at("/promptFeedback/blockReason");
            String reason = blockReason.isMissingNode() ? "unknown" : blockReason.asText();
            throw new IllegalStateException("Gemini response did not contain summary text. blockReason=" + reason);
        }

        JsonNode payload = objectMapper.readTree(textNode.asText());
        String summary = payload.path("summary").asText("").trim();

        if (summary.isBlank()) {
            throw new IllegalStateException("Gemini summary payload was empty");
        }

        return WeeklySummaryResult.of(
                summary,
                toStringList(payload.path("highlights")),
                toStringList(payload.path("risks")),
                toStringList(payload.path("nextActions")),
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

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 300) {
            return value;
        }
        return value.substring(0, 300) + "...";
    }
}
