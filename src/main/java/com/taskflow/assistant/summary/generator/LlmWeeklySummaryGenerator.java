package com.taskflow.assistant.summary.generator;

import com.taskflow.assistant.llm.LlmCallException;
import com.taskflow.assistant.llm.LlmClient;
import com.taskflow.assistant.llm.LlmFeature;
import com.taskflow.assistant.llm.LlmResponse;
import com.taskflow.assistant.summary.GeminiSummaryProperties;
import com.taskflow.assistant.summary.dto.WeeklySummarySectionsResult;
import com.taskflow.assistant.summary.exception.WeeklySummaryGenerationException;
import com.taskflow.calendarsync.outbox.TaskSyncSnapshot;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.planning.project.Project;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class LlmWeeklySummaryGenerator implements WeeklySummaryGenerator {

    private final GeminiSummaryProperties properties;
    private final TaskFlowMetrics metrics;
    private final LlmClient llmClient;

    private final WeeklySummaryPromptBuilder promptBuilder;
    private final WeeklySummaryResponseFormat responseFormat;

    public LlmWeeklySummaryGenerator(
            GeminiSummaryProperties properties,
            ObjectMapper objectMapper,
            TaskFlowMetrics metrics,
            Clock clock,
            LlmClient llmClient) {
        this.properties = properties;
        this.metrics = metrics;
        this.llmClient = llmClient;
        this.promptBuilder = new WeeklySummaryPromptBuilder(properties, objectMapper, clock);
        this.responseFormat = new WeeklySummaryResponseFormat(objectMapper);
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
        WeeklySummaryPromptBuilder.PreparedRequest preparedRequest = promptBuilder.prepareRequest(
                project, syncedTasks, syncedTotalTaskCount, unsyncedTasks, unsyncedTotalTaskCount, weekStart, weekEnd);
        long startedAt = System.currentTimeMillis();
        LlmResponse response;
        try {
            response = llmClient.generate(preparedRequest.getRequest());
        } catch (LlmCallException e) {
            throw upstreamFailure(project, weekStart, weekEnd, e, preparedRequest.getMetrics());
        }
        long latencyMs = System.currentTimeMillis() - startedAt;

        logUsage(project, weekStart, weekEnd, latencyMs, preparedRequest.getMetrics(), response);
        return SummaryGenerationTelemetry.of(
                responseFormat.parseResponse(response, syncedTotalTaskCount, unsyncedTotalTaskCount),
                preparedRequest.getMetrics().getRequestBodyLength(),
                response.promptTokens(),
                response.outputTokens(),
                response.totalTokens());
    }

    private WeeklySummaryGenerationException upstreamFailure(
            Project project, LocalDate weekStart, LocalDate weekEnd, LlmCallException e, PromptMetrics metrics) {
        log.warn(
                "Gemini summary request failed. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, statusCode={}, errorCode={}, latencyMs={}, temperature={}, topK={}, topP={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, classificationSource={}, retryAfter={}, upstreamStatus={}, upstreamReasonHints={}, message={}",
                project.getId(),
                llmClient.modelId(LlmFeature.WEEKLY_SUMMARY),
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
            LlmResponse response) {
        log.info(
                "Gemini summary request succeeded. projectId={}, model={}, weekStart={}, weekEnd={}, cacheStatus=LIVE, latencyMs={}, temperature={}, topK={}, topP={}, requestBodyLength={}, promptInputFingerprint={}, syncedIncludedTasks={}, unsyncedIncludedTasks={}, syncedDescBriefChars={}, unsyncedDescBriefChars={}, promptTokens={}, candidateTokens={}, totalTokens={}",
                project.getId(),
                response.model(),
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
                response.promptTokens(),
                response.outputTokens(),
                response.totalTokens());
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
