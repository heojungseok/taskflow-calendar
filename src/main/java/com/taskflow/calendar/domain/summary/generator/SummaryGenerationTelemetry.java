package com.taskflow.calendar.domain.summary.generator;

import com.taskflow.calendar.domain.summary.dto.WeeklySummarySectionsResult;

public class SummaryGenerationTelemetry {

    private final WeeklySummarySectionsResult sections;
    private final int requestBodyLength;
    private final int promptTokens;
    private final int candidateTokens;
    private final int totalTokens;

    private SummaryGenerationTelemetry(WeeklySummarySectionsResult sections,
                                       int requestBodyLength,
                                       int promptTokens,
                                       int candidateTokens,
                                       int totalTokens) {
        this.sections = sections;
        this.requestBodyLength = requestBodyLength;
        this.promptTokens = promptTokens;
        this.candidateTokens = candidateTokens;
        this.totalTokens = totalTokens;
    }

    public static SummaryGenerationTelemetry of(WeeklySummarySectionsResult sections,
                                                int requestBodyLength,
                                                int promptTokens,
                                                int candidateTokens,
                                                int totalTokens) {
        return new SummaryGenerationTelemetry(sections, requestBodyLength, promptTokens, candidateTokens, totalTokens);
    }

    public WeeklySummarySectionsResult getSections() {
        return sections;
    }

    public int getRequestBodyLength() {
        return requestBodyLength;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCandidateTokens() {
        return candidateTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
