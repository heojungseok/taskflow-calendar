package com.taskflow.calendar.domain.summary.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskflow.calendar.domain.project.Project;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class WeeklySummaryResponse {

    private final Long projectId;
    private final String projectName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate weekStart;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private final LocalDate weekEnd;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private final LocalDateTime generatedAt;

    private final int totalTaskCount;
    private final int includedTaskCount;
    private final String summary;
    private final List<String> highlights;
    private final List<String> risks;
    private final List<String> nextActions;
    private final String model;

    private WeeklySummaryResponse(Long projectId,
                                  String projectName,
                                  LocalDate weekStart,
                                  LocalDate weekEnd,
                                  LocalDateTime generatedAt,
                                  int totalTaskCount,
                                  int includedTaskCount,
                                  String summary,
                                  List<String> highlights,
                                  List<String> risks,
                                  List<String> nextActions,
                                  String model) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.weekStart = weekStart;
        this.weekEnd = weekEnd;
        this.generatedAt = generatedAt;
        this.totalTaskCount = totalTaskCount;
        this.includedTaskCount = includedTaskCount;
        this.summary = summary;
        this.highlights = highlights;
        this.risks = risks;
        this.nextActions = nextActions;
        this.model = model;
    }

    public static WeeklySummaryResponse of(Project project,
                                           LocalDate weekStart,
                                           LocalDate weekEnd,
                                           LocalDateTime generatedAt,
                                           int totalTaskCount,
                                           int includedTaskCount,
                                           WeeklySummaryResult result) {
        return new WeeklySummaryResponse(
                project.getId(),
                project.getName(),
                weekStart,
                weekEnd,
                generatedAt,
                totalTaskCount,
                includedTaskCount,
                result.getSummary(),
                result.getHighlights(),
                result.getRisks(),
                result.getNextActions(),
                result.getModel()
        );
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public LocalDate getWeekEnd() {
        return weekEnd;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
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
