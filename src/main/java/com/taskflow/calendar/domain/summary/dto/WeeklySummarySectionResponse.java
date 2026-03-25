package com.taskflow.calendar.domain.summary.dto;

import java.util.List;

public class WeeklySummarySectionResponse {

    private final int totalTaskCount;
    private final int includedTaskCount;
    private final String summary;
    private final List<String> highlights;
    private final List<String> risks;
    private final List<String> nextActions;
    private final String model;

    private WeeklySummarySectionResponse(int totalTaskCount,
                                         int includedTaskCount,
                                         String summary,
                                         List<String> highlights,
                                         List<String> risks,
                                         List<String> nextActions,
                                         String model) {
        this.totalTaskCount = totalTaskCount;
        this.includedTaskCount = includedTaskCount;
        this.summary = summary;
        this.highlights = highlights;
        this.risks = risks;
        this.nextActions = nextActions;
        this.model = model;
    }

    public static WeeklySummarySectionResponse of(int totalTaskCount,
                                                  int includedTaskCount,
                                                  WeeklySummaryResult result) {
        return new WeeklySummarySectionResponse(
                totalTaskCount,
                includedTaskCount,
                result.getSummary(),
                result.getHighlights(),
                result.getRisks(),
                result.getNextActions(),
                result.getModel()
        );
    }

    public int getTotalTaskCount() {
        return totalTaskCount;
    }

    public int getIncludedTaskCount() {
        return includedTaskCount;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getHighlights() {
        return highlights;
    }

    public List<String> getRisks() {
        return risks;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public String getModel() {
        return model;
    }
}
