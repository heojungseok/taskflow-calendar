package com.taskflow.assistant.summary.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.assistant.summary.SummaryBucket;
import com.taskflow.assistant.summary.dto.WeeklySummaryResult;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.common.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 주간 요약 응답의 JSON 스키마와 그 응답을 읽는 규칙. */
final class WeeklySummaryResponseFormat {

    private final GeminiSummaryProperties properties;
    private final ObjectMapper objectMapper;

    WeeklySummaryResponseFormat(GeminiSummaryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
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

    WeeklySummarySectionsResult parseResponse(JsonNode root, int syncedTotalTaskCount, int unsyncedTotalTaskCount)
            throws IOException {
        JsonNode textNode = root.at("/candidates/0/content/parts/0/text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            JsonNode blockReason = root.at("/promptFeedback/blockReason");
            String reason = blockReason.isMissingNode() ? "unknown" : blockReason.asText();
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain summary text. blockReason=" + reason,
                    false);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(textNode.asText());
        } catch (IOException e) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE, "Gemini response payload was not valid JSON", false);
        }

        return WeeklySummarySectionsResult.of(
                parseSection(payload.path("synced"), SummaryBucket.SYNCED, syncedTotalTaskCount),
                parseSection(payload.path("unsynced"), SummaryBucket.UNSYNCED, unsyncedTotalTaskCount));
    }

    private WeeklySummaryResult parseSection(JsonNode sectionNode, SummaryBucket bucket, int totalTaskCount) {
        if (totalTaskCount == 0) {
            return WeeklySummaryResult.empty(bucket.getEmptySummary(), bucket.getEmptyNextActions());
        }
        if (sectionNode.isMissingNode() || sectionNode.isNull()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_INVALID_RESPONSE,
                    "Gemini response did not contain " + bucket.name().toLowerCase(Locale.ROOT) + " section",
                    false);
        }

        String summary = sectionNode.path("summary").asText("").trim();
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
                properties.getModel());
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

    UsageMetrics usageMetrics(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        return new UsageMetrics(
                usage.path("promptTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("totalTokenCount").asInt(0));
    }

    static final class UsageMetrics {
        final int promptTokens;
        final int candidateTokens;
        final int totalTokens;

        private UsageMetrics(int promptTokens, int candidateTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.candidateTokens = candidateTokens;
            this.totalTokens = totalTokens;
        }
    }
}
