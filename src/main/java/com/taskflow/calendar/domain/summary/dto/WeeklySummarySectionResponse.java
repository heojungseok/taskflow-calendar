package com.taskflow.calendar.domain.summary.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class WeeklySummarySectionResponse {

    private final int totalTaskCount;
    private final int includedTaskCount;
    private final String summary;
    private final List<String> highlights;
    private final List<String> risks;
    private final List<String> nextActions;
    private final String model;

    @JsonCreator
    private WeeklySummarySectionResponse(@JsonProperty("totalTaskCount") int totalTaskCount,
                                         @JsonProperty("includedTaskCount") int includedTaskCount,
                                         @JsonProperty("summary") String summary,
                                         @JsonProperty("highlights") List<String> highlights,
                                         @JsonProperty("risks") List<String> risks,
                                         @JsonProperty("nextActions") List<String> nextActions,
                                         @JsonProperty("model") String model) {
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
