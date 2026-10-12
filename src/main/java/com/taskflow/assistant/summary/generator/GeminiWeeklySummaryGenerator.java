package com.taskflow.assistant.summary.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskflow.assistant.GeminiCallException;
import com.taskflow.assistant.GeminiClient;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.common.ErrorCode;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GeminiWeeklySummaryGenerator implements WeeklySummaryGenerator {

    private final GeminiSummaryProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskFlowMetrics metrics;
    private final GeminiClient geminiClient;

    private final WeeklySummaryPromptBuilder promptBuilder;
    private final WeeklySummaryResponseFormat responseFormat;

    public GeminiWeeklySummaryGenerator(
            GeminiSummaryProperties properties,
            ObjectMapper objectMapper,
            TaskFlowMetrics metrics,
            Clock clock,
            GeminiClient geminiClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.geminiClient = geminiClient;
        this.promptBuilder = new WeeklySummaryPromptBuilder(properties, objectMapper, clock);
        this.responseFormat = new WeeklySummaryResponseFormat(properties, objectMapper);
    }

    @Override
    public WeeklySummarySectionsResult generate(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd) {
        return generateWithTelemetry(
                        project,
                        syncedTasks,
                        syncedTotalTaskCount,
                        unsyncedTasks,
                        unsyncedTotalTaskCount,
                        weekStart,
                        weekEnd)
                .getSections();
    }

    SummaryGenerationTelemetry generateWithTelemetry(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd) {
        return metrics.observeGeminiCall(
                "weekly_summary",
                () -> generateUnobserved(
                        project,
                        syncedTasks,
                        syncedTotalTaskCount,
                        unsyncedTasks,
                        unsyncedTotalTaskCount,
                        weekStart,
                        weekEnd));
    }

    private SummaryGenerationTelemetry generateUnobserved(
            Project project,
            List<TaskSyncSnapshot> syncedTasks,
            int syncedTotalTaskCount,
            List<TaskSyncSnapshot> unsyncedTasks,
            int unsyncedTotalTaskCount,
            LocalDate weekStart,
            LocalDate weekEnd) {
        validateConfiguration();

        WeeklySummaryPromptBuilder.PreparedRequest preparedRequest = promptBuilder.prepareRequest(
                project, syncedTasks, syncedTotalTaskCount, unsyncedTasks, unsyncedTotalTaskCount, weekStart, weekEnd);
        long startedAt = System.currentTimeMillis();
        String responseBody;
        try {
            responseBody = geminiClient.post(
                    properties, properties.getModel() + ":generateContent", preparedRequest.getRequestBody());
        } catch (GeminiCallException e) {
            throw upstreamFailure(project, weekStart, weekEnd, e, preparedRequest.getMetrics());
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            WeeklySummaryResponseFormat.UsageMetrics usageMetrics = responseFormat.usageMetrics(root);
            logUsage(project, weekStart, weekEnd, latencyMs, preparedRequest.getMetrics(), usageMetrics);
            return SummaryGenerationTelemetry.of(
                    responseFormat.parseResponse(root, syncedTotalTaskCount, unsyncedTotalTaskCount),
                    preparedRequest.getMetrics().getRequestBodyLength(),
                    usageMetrics.promptTokens,
                    usageMetrics.candidateTokens,
                    usageMetrics.totalTokens);
        } catch (IOException e) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_UPSTREAM_TEMPORARY_FAILURE,
                    "Gemini response was not readable: " + e.getMessage(),
                    true);
        }
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new WeeklySummaryGenerationException(
                    ErrorCode.LLM_API_KEY_MISSING, "GEMINI_SUMMARY_API_KEY is not configured", false);
        }
    }

    private WeeklySummaryGenerationException upstreamFailure(
            Project project, LocalDate weekStart, LocalDate weekEnd, GeminiCallException e, PromptMetrics metrics) {
        log.warn(
                "Gemini summary request failed. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, statusCode={}, errorCode={}, latencyMs={}, temperature={}, topK={}, topP={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, classificationSource={}, retryAfter={}, upstreamStatus={}, upstreamReasonHints={}, message={}",
                project.getId(),
                properties.getModel(),
                weekStart,
                weekEnd,
                e.getStatusCode(),
                e.getErrorCode().getCode(),
                e.getLatencyMs(),
                properties.getTemperature(),
                promptBuilder.effectiveTopK(),
                promptBuilder.effectiveTopP(),
                metrics.getRequestBodyLength(),
                metrics.getPromptInputFingerprint(),
                metrics.getSyncedIncludedTaskCount(),
                metrics.getUnsyncedIncludedTaskCount(),
                metrics.getSyncedDescBriefChars(),
                metrics.getUnsyncedDescBriefChars(),
                e.getClassificationSource() != null ? e.getClassificationSource() : "UNKNOWN",
                e.getRetryAfter(),
                e.getUpstreamStatus(),
                e.getReasonHints(),
                abbreviate(e.getMessage()));
        return new WeeklySummaryGenerationException(
                e.getErrorCode(),
                e.getMessage(),
                e.isTemporary(),
                e.getClassificationSource(),
                e.getRetryAfter(),
                e.getUpstreamStatus(),
                e.getReasonHints());
    }

    private void logUsage(
            Project project,
            LocalDate weekStart,
            LocalDate weekEnd,
            long latencyMs,
            PromptMetrics metrics,
            WeeklySummaryResponseFormat.UsageMetrics usageMetrics) {
        log.info(
                "Gemini summary request succeeded. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, latencyMs={}, temperature={}, topK={}, topP={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, promptTokens={}, candidateTokens={}, totalTokens={}",
                project.getId(),
                properties.getModel(),
                weekStart,
                weekEnd,
                latencyMs,
                properties.getTemperature(),
                promptBuilder.effectiveTopK(),
                promptBuilder.effectiveTopP(),
                metrics.getRequestBodyLength(),
                metrics.getPromptInputFingerprint(),
                metrics.getSyncedIncludedTaskCount(),
                metrics.getUnsyncedIncludedTaskCount(),
                metrics.getSyncedDescBriefChars(),
                metrics.getUnsyncedDescBriefChars(),
                usageMetrics.promptTokens,
                usageMetrics.candidateTokens,
                usageMetrics.totalTokens);
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
