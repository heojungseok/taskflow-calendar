package com.taskflow.assistant.summary.generator;

import com.taskflow.assistant.llm.LlmJsonReader;
import com.taskflow.assistant.llm.LlmResponse;
import com.taskflow.assistant.summary.SummaryBucket;
import com.taskflow.assistant.summary.dto.WeeklySummaryResult;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 주간 요약 응답의 JSON 스키마와 그 응답을 읽는 규칙. */
final class WeeklySummaryResponseFormat {

    private final ObjectMapper objectMapper;

    WeeklySummaryResponseFormat(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static Map<String, Object> responseJsonSchema() {
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

    private static Map<String, Object> sectionSchema() {
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

    WeeklySummarySectionsResult parseResponse(
            LlmResponse response, int syncedTotalTaskCount, int unsyncedTotalTaskCount) {
        if (response.text() == null || response.text().isBlank()) {
            String reason = response.finishReason() == null ? "unknown" : response.finishReason();
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain summary text. finishReason=" + reason,
                    false);
        }

        JsonNode payload;
        try {
            payload = new LlmJsonReader(objectMapper).read(response.text());
        } catch (JacksonException e) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini response payload was not valid JSON", false);
        }

        return WeeklySummarySectionsResult.of(
                parseSection(payload.path("synced"), SummaryBucket.SYNCED, syncedTotalTaskCount, response.model()),
                parseSection(
                        payload.path("unsynced"), SummaryBucket.UNSYNCED, unsyncedTotalTaskCount, response.model()));
    }

    private WeeklySummaryResult parseSection(
            JsonNode sectionNode, SummaryBucket bucket, int totalTaskCount, String model) {
        if (totalTaskCount == 0) {
            return WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions());
        }
        if (sectionNode.isMissingNode() || sectionNode.isNull()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain " + bucket.name().toLowerCase(Locale.ROOT) + " section",
                    false);
        }

        String summary = sectionNode.path("summary").asString("").trim();
        if (summary.isBlank()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini " + bucket.name().toLowerCase(Locale.ROOT) + " summary payload was empty",
                    false);
        }

        return WeeklySummaryResult.of(
                summary,
                toStringList(sectionNode.path("highlights")),
                toStringList(sectionNode.path("risks")),
                toStringList(sectionNode.path("nextActions")),
                model);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> values = new ArrayList<>();

        if (!node.isArray()) {
            return values;
        }

        for (JsonNode item : node) {
            String value = item.asString("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }

        return values;
    }
}
