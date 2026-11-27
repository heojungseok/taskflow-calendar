package com.taskflow.assistant.recommendation.generator;

import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmClient;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmJsonReader;
import com.taskflow.assistant.llm.LlmRequest;
import com.taskflow.assistant.llm.LlmResponse;
import com.taskflow.assistant.recommendation.GeminiRecommendationProperties;
import com.taskflow.assistant.recommendation.exception.TaskRecommendationGenerationException;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTaskRecommendationGenerator implements TaskRecommendationGenerator {

    private final GeminiRecommendationProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskFlowMetrics metrics;
    private final LlmClient llmClient;

    private final RecommendationPromptTaskSupport promptTaskSupport = new RecommendationPromptTaskSupport();

    @Override
    public TaskRecommendationGenerationResult generate(
            Project project, List<TaskSyncSnapshot> candidates, int recommendationCount, LocalDate today) {
        return metrics.observeGeminiCall(
                "recommendation",
                llmClient.providerName(LlmFeature.RECOMMENDATION),
                () -> generateUnobserved(project, candidates, recommendationCount, today));
    }

    private TaskRecommendationGenerationResult generateUnobserved(
            Project project, List<TaskSyncSnapshot> candidates, int recommendationCount, LocalDate today) {
        LlmRequest request = new LlmRequest(
                LlmFeature.RECOMMENDATION,
                systemInstruction(),
                userPrompt(candidates, recommendationCount, today),
                responseJsonSchema(),
                properties.getTemperature(),
                null,
                null);
        long startedAt = System.currentTimeMillis();
        LlmResponse response;
        try {
            response = llmClient.generate(request);
        } catch (LlmCallException e) {
            log.warn(
                    "Gemini task recommendation request failed. statusCode={}, errorCode={}, latencyMs={}",
                    e.getStatusCode(),
                    e.getErrorCode().getCode(),
                    e.getLatencyMs());
            throw new TaskRecommendationGenerationException(
                    e.getErrorCode(), "Gemini recommendation request failed: " + e.getMessage());
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        log.info(
                "Gemini task recommendation request succeeded. projectId={}, model={}, latencyMs={}, requestBodyLength={}, candidateCount={}, recommendationCount={}, promptTokens={}, candidateTokens={}, totalTokens={}",
                project.getId(),
                response.model(),
                latencyMs,
                request.systemInstruction().length() + request.userPrompt().length(),
                candidates.size(),
                recommendationCount,
                response.promptTokens(),
                response.outputTokens(),
                response.totalTokens());
        return parseResponse(response, candidates, recommendationCount);
    }

    private String systemInstruction() {
        return """
               당신은 후보 Task를 비교해 우선 확인 순서를 정하는 추천 보조자다.
               응답은 반드시 JSON 스키마를 따르며, reason·primaryTag·secondaryTag 문자열은 한국어로 작성하라.
               프로젝트 전체 요약은 하지 말고, 제공된 후보와 입력 사실만 사용하라.\
               """;
    }

    private String userPrompt(List<TaskSyncSnapshot> candidates, int recommendationCount, LocalDate today) {
        List<Map<String, Object>> candidatePayload = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            candidatePayload.add(promptTaskSupport.toPromptTaskPayload(candidates.get(i), i + 1));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("today", today);
        payload.put("take", recommendationCount);
        payload.put("candidates", candidatePayload);

        return "아래 candidates만 비교해 지금 먼저 확인할 task를 고르라.\n"
                + "items 배열 순서가 rank이며, take 개수만 반환하고 id를 중복하지 마라.\n"
                + "seed는 참고값이지만 due, status, desc, sync, outbox 근거가 더 강하면 자유롭게 재정렬하라.\n"
                + "reason은 입력 사실만 근거로 1문장, 필요하면 최대 2문장으로 작성하라.\n"
                + "primaryTag는 핵심 맥락의 짧은 명사구, secondaryTag는 보조 맥락이 분명할 때만 작성하고 없으면 null로 둬라.\n"
                + "태그는 description 표현을 우선하고, 부족할 때만 title 키워드를 사용하며, 긴급·중요·위험 같은 일반 태그는 가능하면 피하라.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> responseJsonSchema() {
        Map<String, Object> stringField = Map.of("type", "string");
        Map<String, Object> nullableStringField = new LinkedHashMap<>();
        nullableStringField.put("type", List.of("string", "null"));

        Map<String, Object> taskId = Map.of("type", "integer");

        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("taskId", taskId);
        itemProperties.put("primaryTag", stringField);
        itemProperties.put("secondaryTag", nullableStringField);
        itemProperties.put("reason", stringField);

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", List.of("taskId", "primaryTag", "secondaryTag", "reason"));
        itemSchema.put("additionalProperties", false);

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "array");
        items.put("items", itemSchema);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("items", items);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("items"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private TaskRecommendationGenerationResult parseResponse(
            LlmResponse response, List<TaskSyncSnapshot> candidates, int recommendationCount) {
        if (response.text() == null || response.text().isBlank()) {
            throw new TaskRecommendationGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini recommendation response did not contain text");
        }

        JsonNode payload;
        try {
            payload = new LlmJsonReader(objectMapper).read(response.text());
        } catch (JacksonException e) {
            throw new TaskRecommendationGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini recommendation payload was not valid JSON");
        }

        JsonNode itemsNode = payload.path("items");
        if (!itemsNode.isArray()) {
            throw new TaskRecommendationGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini recommendation payload did not contain items array");
        }

        Set<Long> allowedTaskIds = new LinkedHashSet<>();
        for (TaskSyncSnapshot candidate : candidates) {
            allowedTaskIds.add(candidate.getTask().getId());
        }

        List<TaskRecommendationItemResult> items = new ArrayList<>();
        Set<Long> seenTaskIds = new LinkedHashSet<>();
        for (JsonNode itemNode : itemsNode) {
            long taskId = itemNode.path("taskId").asLong(-1);
            String primaryTag = itemNode.path("primaryTag").asString("").trim();
            String secondaryTag = itemNode.path("secondaryTag").isNull()
                    ? null
                    : itemNode.path("secondaryTag").asString("").trim();
            String reason = itemNode.path("reason").asString("").trim();

            if (taskId <= 0 || !allowedTaskIds.contains(taskId) || !seenTaskIds.add(taskId)) {
                continue;
            }
            if (primaryTag.isBlank() || reason.isBlank()) {
                continue;
            }

            items.add(TaskRecommendationItemResult.of(
                    taskId, primaryTag, secondaryTag == null || secondaryTag.isBlank() ? null : secondaryTag, reason));
        }

        if (recommendationCount > 0 && items.isEmpty()) {
            throw new TaskRecommendationGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini recommendation payload did not contain valid recommendation items");
        }

        if (items.size() > recommendationCount) {
            items = items.subList(0, recommendationCount);
        }

        return TaskRecommendationGenerationResult.of(items);
    }
}
